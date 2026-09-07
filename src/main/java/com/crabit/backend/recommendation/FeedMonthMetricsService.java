package com.crabit.backend.recommendation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Produces the closed feed monthly aggregate without mistaking incomplete observation for zero activity. */
@Service
public class FeedMonthMetricsService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final FeedMonthMetricsRepository metrics;

    public FeedMonthMetricsService(JdbcTemplate jdbc, FeedMonthMetricsRepository metrics) {
        this.jdbc = jdbc; this.metrics = metrics;
    }

    public static YearMonth previousMonth(Instant reference) {
        return YearMonth.from(reference.atZone(SEOUL)).minusMonths(1);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> build(UUID accountId, UUID studentId, UUID academyId,
            YearMonth month, Instant asOf) {
        Instant opened = jdbc.queryForObject("""
                SELECT opened_at FROM card_balance_account
                WHERE id=? AND student_id=? AND academy_id=?
                """, Timestamp.class, accountId, studentId, academyId).toInstant();
        Instant collection = jdbc.queryForObject(
                "SELECT started_at FROM behavior_collection WHERE id=1", Timestamp.class).toInstant();
        String coverage = coverage(month, opened, collection, asOf);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month.toString()); result.put("coverage", coverage);
        result.put("metrics_version", "core-metrics-v1");
        Map<String, Object> values = null;
        if (coverage.equals("COMPLETE")) {
            values = new LinkedHashMap<>(metrics.metrics(accountId, studentId, academyId,
                    month.atDay(1), asOf));
            values.remove("metrics_version");
        }
        result.put("values", values);
        return java.util.Collections.unmodifiableMap(result);
    }

    static String coverage(YearMonth month, Instant opened, Instant collection, Instant asOf) {
        Instant from = month.atDay(1).atStartOfDay(SEOUL).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(SEOUL).toInstant();
        // The legacy behavior collector permits five minutes of future clock skew.
        Instant fullFrom = asOf.minus(Duration.ofDays(90)).plusSeconds(300);
        if (opened.isAfter(fullFrom)) fullFrom = opened;
        if (collection.isAfter(fullFrom)) fullFrom = collection;
        if (!from.isBefore(fullFrom) && !to.isAfter(asOf)) return "COMPLETE";
        if (from.isBefore(asOf) && to.isAfter(fullFrom) && fullFrom.isBefore(asOf)) return "PARTIAL";
        return "UNOBSERVED";
    }
}
