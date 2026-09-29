package com.example.inventory.management.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;

import java.time.Duration;

/**
 * One token bucket per client, kept in this instance's memory (no Redis or other shared store).
 *
 * <p>Because buckets are per instance, with N instances a client can send up to N times the limit in
 * total, and restarting an instance resets its buckets. This is intended: the goal is to stop one
 * client from flooding the API in a short time, not an exact cluster-wide quota.
 */
public class PostRateLimiter {

    /** Caps memory when many different clients call; the least recently used buckets are evicted first. */
    private static final long MAX_CLIENTS = 100_000;

    private final Bandwidth bandwidth;
    private final TimeMeter timeMeter;
    private final Cache<String, Bucket> buckets;

    /**
     * Valid settings: capacity 1..{@link Long#MAX_VALUE}, refillPerSecond 1..1,000,000,000 (Bucket4j's highest
     * refill rate is 1 token per nanosecond).
     *
     * @throws IllegalArgumentException if a setting is outside that range, so a bad setting fails at startup
     *                                  instead of on every request
     */
    public PostRateLimiter(RateLimitProperties properties, TimeMeter timeMeter) {
        this(properties, timeMeter, Ticker.systemTicker());
    }

    /** Tests pass a fake ticker so bucket expiry can be verified without waiting. */
    PostRateLimiter(RateLimitProperties properties, TimeMeter timeMeter, Ticker ticker) {
        this.bandwidth = Bandwidth.builder()
                .capacity(properties.capacity())
                .refillGreedy(properties.refillPerSecond(), Duration.ofSeconds(1))
                .build();
        this.timeMeter = timeMeter;
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(timeToRefill(properties))
                .maximumSize(MAX_CLIENTS)
                .ticker(ticker)
                .build();
    }

    /**
     * How long an empty bucket takes to fill up (capacity / refillPerSecond). A bucket idle this long is
     * already full, so evicting it then is the same as keeping it. Evicting a bucket earlier would hand the
     * client a new full bucket and let it bypass the limit.
     */
    private static Duration timeToRefill(RateLimitProperties properties) {
        long capacity = properties.capacity();
        long refillPerSecond = properties.refillPerSecond();
        // Whole seconds + remainder, so capacity * 10^9 never has to fit in a long (it overflows above ~9.2 billion).
        // remainder < refillPerSecond <= 10^9 (checked by Bucket4j when the Bandwidth is built first), so
        // remainder * 10^9 < 10^18 fits; multiplyExact still fails loudly if that ordering ever changes.
        long remainderNanos = Math.multiplyExact(capacity % refillPerSecond, Duration.ofSeconds(1).toNanos());
        // Round up: expiring even 1ns early would hand out a bucket that is not yet full. (Math.ceilDiv is Java 18+.)
        return Duration.ofSeconds(capacity / refillPerSecond)
                .plusNanos(-Math.floorDiv(-remainderNanos, refillPerSecond));
    }

    public ConsumptionProbe tryConsume(String clientKey) {
        return buckets.get(clientKey, key -> newBucket()).tryConsumeAndReturnRemaining(1);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(bandwidth)
                .withCustomTimePrecision(timeMeter)
                .build();
    }
}
