package com.crabit.backend.history;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Calendar boundaries are real exclusive instants, never fabricated final nanoseconds. */
public final class HistoricalPeriods {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public enum Granularity { DAY, WEEK, MONTH }
    public record Period(LocalDate start, LocalDate endExclusive, Instant evaluatedAt, boolean completed) {
        public Instant startInstant() { return start.atStartOfDay(SEOUL).toInstant(); }
        public boolean includes(Instant appliedAt) {
            return completed ? appliedAt.isBefore(evaluatedAt) : !appliedAt.isAfter(evaluatedAt);
        }
    }
    private HistoricalPeriods() {}
    public static void validate(LocalDate from, LocalDate to, Granularity granularity) {
        if (from == null || to == null || granularity == null || !from.isBefore(to)
                || ChronoUnit.DAYS.between(from, to) > 366 || from.getYear() < 1 || to.getYear() > 9999)
            throw HistoricalBalanceException.malformed();
        if (granularity == Granularity.WEEK
                && (from.getDayOfWeek() != DayOfWeek.MONDAY || to.getDayOfWeek() != DayOfWeek.MONDAY))
            throw HistoricalBalanceException.malformed();
        if (granularity == Granularity.MONTH && (from.getDayOfMonth() != 1 || to.getDayOfMonth() != 1))
            throw HistoricalBalanceException.malformed();
    }
    public static List<Period> buckets(LocalDate from, LocalDate to, Granularity granularity, Instant horizon) {
        validate(from, to, granularity);
        List<Period> result = new ArrayList<>();
        for (LocalDate start = from; start.isBefore(to); ) {
            LocalDate end = switch (granularity) {
                case DAY -> start.plusDays(1);
                case WEEK -> start.plusWeeks(1);
                case MONTH -> start.plusMonths(1);
            };
            Instant startAt = start.atStartOfDay(SEOUL).toInstant();
            Instant endAt = end.atStartOfDay(SEOUL).toInstant();
            if (startAt.isAfter(horizon)) throw HistoricalBalanceException.malformed();
            boolean completed = !endAt.isAfter(horizon);
            result.add(new Period(start, end, completed ? endAt : horizon, completed));
            start = end;
        }
        return List.copyOf(result);
    }
}
