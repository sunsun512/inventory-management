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
     * @throws IllegalArgumentException if capacity or refillPerSecond is not positive, so a bad setting
     *                                  fails at startup instead of on every request
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
        long capacityNanos = Math.multiplyExact(properties.capacity(), Duration.ofSeconds(1).toNanos());
        // Round up: expiring even 1ns early would hand out a bucket that is not yet full. (Math.ceilDiv is Java 18+.)
        return Duration.ofNanos(-Math.floorDiv(-capacityNanos, properties.refillPerSecond()));
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
