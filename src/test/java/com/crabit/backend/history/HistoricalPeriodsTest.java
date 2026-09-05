package com.crabit.backend.history;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class HistoricalPeriodsTest {
    @Test void completedCalendarEndsExcludeExactApplicationAndCurrentPeriodsFreezeTheirHorizon() {
        Instant horizon=Instant.parse("2026-09-02T01:00:00Z");
        var days=HistoricalPeriods.buckets(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-03"),HistoricalPeriods.Granularity.DAY,horizon);
        assertThat(days).hasSize(2);
        assertThat(days.getFirst().evaluatedAt()).isEqualTo(Instant.parse("2026-09-01T15:00:00Z"));
        assertThat(days.getFirst().includes(days.getFirst().evaluatedAt())).isFalse();
        assertThat(days.getFirst().includes(days.getFirst().evaluatedAt().minusNanos(1))).isTrue();
        assertThat(days.getLast().completed()).isFalse();
        assertThat(days.getLast().evaluatedAt()).isEqualTo(horizon);
        assertThat(days.getLast().includes(horizon)).isTrue();
        assertThat(days.getLast().includes(horizon.plusNanos(1))).isFalse();
    }
    @Test void weekAndMonthUseNaturalSeoulBoundariesAndAllowOnlyTheirCurrentOpenBucket() {
        var week=HistoricalPeriods.buckets(LocalDate.parse("2026-08-31"),LocalDate.parse("2026-09-07"),HistoricalPeriods.Granularity.WEEK,Instant.parse("2026-09-02T00:00:00Z"));
        assertThat(week).hasSize(1);assertThat(week.getFirst().completed()).isFalse();
        var month=HistoricalPeriods.buckets(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-10-01"),HistoricalPeriods.Granularity.MONTH,Instant.parse("2026-09-02T00:00:00Z"));
        assertThat(month).hasSize(1);assertThat(month.getFirst().endExclusive()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThatThrownBy(()->HistoricalPeriods.buckets(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-04"),HistoricalPeriods.Granularity.DAY,Instant.parse("2026-09-02T00:00:00Z"))).isInstanceOf(HistoricalBalanceException.class);
    }
    @Test void rejectsMisalignmentEmptyReversedAndOverlongRanges() {
        for(var g:HistoricalPeriods.Granularity.values()) assertThatThrownBy(()->HistoricalPeriods.validate(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-01"),g)).isInstanceOf(HistoricalBalanceException.class);
        assertThatThrownBy(()->HistoricalPeriods.validate(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-08"),HistoricalPeriods.Granularity.WEEK)).isInstanceOf(HistoricalBalanceException.class);
        assertThatThrownBy(()->HistoricalPeriods.validate(LocalDate.parse("2026-09-02"),LocalDate.parse("2026-10-02"),HistoricalPeriods.Granularity.MONTH)).isInstanceOf(HistoricalBalanceException.class);
        assertThatThrownBy(()->HistoricalPeriods.validate(LocalDate.parse("2026-01-01"),LocalDate.parse("2027-01-03"),HistoricalPeriods.Granularity.DAY)).isInstanceOf(HistoricalBalanceException.class);
        assertThat(HistoricalPeriods.buckets(LocalDate.parse("2024-01-01"),LocalDate.parse("2025-01-01"),HistoricalPeriods.Granularity.DAY,Instant.parse("2026-01-01T00:00:00Z"))).hasSize(366);
    }
}
