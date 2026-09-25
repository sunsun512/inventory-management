package com.example.inventory.management.common.ratelimit;

import io.github.bucket4j.TimeMeter;

import java.time.Duration;

/** A clock the test moves by hand, so token refill can be verified without sleeping. */
class FakeTimeMeter implements TimeMeter {

    private long nanos;

    void advance(Duration duration) {
        nanos += duration.toNanos();
    }

    @Override
    public long currentTimeNanos() {
        return nanos;
    }

    @Override
    public boolean isWallClockBased() {
        return false;
    }
}
