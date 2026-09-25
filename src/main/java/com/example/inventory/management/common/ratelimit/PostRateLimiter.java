package com.example.inventory.management.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

    /**
     * Must be longer than the time an empty bucket takes to refill (capacity / refillPerSecond). If a
     * half-empty bucket were evicted earlier, the client would get a new full bucket and bypass the limit.
     */
    private static final Duration IDLE_EXPIRY = Duration.ofMinutes(10);

    /** Caps memory when many different clients call; the least recently used buckets are evicted first. */
    private static final long MAX_CLIENTS = 100_000;

    private final Bandwidth bandwidth;
    private final TimeMeter timeMeter;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(IDLE_EXPIRY)
            .maximumSize(MAX_CLIENTS)
            .build();

    /**
     * @throws IllegalArgumentException if capacity or refillPerSecond is not positive, so a bad setting
     *                                  fails at startup instead of on every request
     */
    public PostRateLimiter(RateLimitProperties properties, TimeMeter timeMeter) {
        this.bandwidth = Bandwidth.builder()
                .capacity(properties.capacity())
                .refillGreedy(properties.refillPerSecond(), Duration.ofSeconds(1))
                .build();
        this.timeMeter = timeMeter;
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
