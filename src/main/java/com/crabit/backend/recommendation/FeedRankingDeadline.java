package com.crabit.backend.recommendation;

import java.time.Duration;
import java.util.function.LongSupplier;

/** One monotonic budget shared by preparation, transport, decoding and validation. */
public final class FeedRankingDeadline {
    private final LongSupplier clock;
    private final long started;
    private final long budget;

    public static FeedRankingDeadline start() {
        return new FeedRankingDeadline(System::nanoTime, Duration.ofMillis(500));
    }

    FeedRankingDeadline(LongSupplier clock, Duration budget) {
        this.clock = clock;
        this.started = clock.getAsLong();
        this.budget = budget.toNanos();
    }

    public long remainingNanos() {
        return Math.max(0, budget - (clock.getAsLong() - started));
    }

    public boolean expired() { return remainingNanos() == 0; }
}
