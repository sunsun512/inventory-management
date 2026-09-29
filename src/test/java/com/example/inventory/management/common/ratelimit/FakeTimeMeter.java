package com.example.inventory.management.common.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import io.github.bucket4j.TimeMeter;

import java.time.Duration;

/**
 * A clock the test moves by hand, so token refill and bucket expiry can be verified without sleeping.
 * It drives both bucket4j (TimeMeter) and the Caffeine cache (Ticker).
 */
class FakeTimeMeter implements TimeMeter, Ticker {

    private long nanos;

    void advance(Duration duration) {
        nanos += duration.toNanos();
    }

    @Override
    public long currentTimeNanos() {
        return nanos;
    }

    @Override
    public long read() {
        return nanos;
    }

    @Override
    public boolean isWallClockBased() {
        return false;
    }
}
