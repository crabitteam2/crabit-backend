package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FeedRankingRequestAssemblerTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T15:00:00Z"); // Jan 2 Seoul

    @Test
    void preservesPythonAmountBucketBoundaries() {
        long[] values = {0, 9_999, 10_000, 29_999, 30_000, 49_999,
                50_000, 99_999, 100_000, 299_999, 300_000};
        int[] expected = {0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5};
        for (int index = 0; index < values.length; index++)
            assertThat(FeedRankingRequestAssembler.amountBucket(values[index]))
                    .as("amount %s", values[index]).isEqualTo(expected[index]);
    }

    @Test
    void preservesPythonDurationBucketBoundariesAndSeparateNoDeadline() {
        assertThat(bucket(29)).isZero();
        assertThat(bucket(30)).isEqualTo(1);
        assertThat(bucket(89)).isEqualTo(1);
        assertThat(bucket(90)).isEqualTo(2);
        assertThat(bucket(179)).isEqualTo(2);
        assertThat(bucket(180)).isEqualTo(3);
        assertThat(FeedRankingRequestAssembler.deadlineBucket(CREATED, null)).isEqualTo(4);
    }

    private static int bucket(int days) {
        return FeedRankingRequestAssembler.deadlineBucket(CREATED, LocalDate.of(2026, 1, 2).plusDays(days));
    }
}
