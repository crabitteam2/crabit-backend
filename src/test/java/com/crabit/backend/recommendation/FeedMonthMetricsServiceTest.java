package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FeedMonthMetricsServiceTest {
    @Test void previousMonthUsesSeoulAndRollsAcrossYears() {
        assertThat(FeedMonthMetricsService.previousMonth(Instant.parse("2025-12-31T14:59:59Z")))
                .isEqualTo(YearMonth.of(2025, 11));
        assertThat(FeedMonthMetricsService.previousMonth(Instant.parse("2025-12-31T15:00:00Z")))
                .isEqualTo(YearMonth.of(2025, 12));
    }

    @Test void observationMustCoverTheEntireMonthIncludingCollectionAndRetention() {
        var month = YearMonth.of(2026, 8);
        var start = Instant.parse("2026-07-31T15:00:00Z");
        var end = Instant.parse("2026-08-31T15:00:00Z");
        assertThat(FeedMonthMetricsService.coverage(month, start, start, end)).isEqualTo("COMPLETE");
        assertThat(FeedMonthMetricsService.coverage(month, start.plusNanos(1), start, end)).isEqualTo("PARTIAL");
        assertThat(FeedMonthMetricsService.coverage(month, start, start.plusNanos(1), end)).isEqualTo("PARTIAL");
        assertThat(FeedMonthMetricsService.coverage(month, start, end, end)).isEqualTo("UNOBSERVED");
        assertThat(FeedMonthMetricsService.coverage(month, start, start,
                Instant.parse("2026-12-01T00:00:00Z"))).isEqualTo("UNOBSERVED");
    }

    @Test void incompleteMonthNeverComputesOrPublishesZeroMetrics() {
        var jdbc = mock(JdbcTemplate.class);
        var metrics = mock(FeedMonthMetricsRepository.class);
        var service = new FeedMonthMetricsService(jdbc, metrics);
        var account = UUID.randomUUID(); var student = UUID.randomUUID(); var academy = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class), eq(account), eq(student), eq(academy)))
                .thenReturn(Timestamp.from(Instant.parse("2026-08-15T00:00:00Z")));
        when(jdbc.queryForObject("SELECT started_at FROM behavior_collection WHERE id=1", Timestamp.class))
                .thenReturn(Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
        assertThat(service.build(account, student, academy, YearMonth.of(2026, 8),
                Instant.parse("2026-09-01T00:00:00Z")))
                .containsEntry("coverage", "PARTIAL").containsEntry("values", null);
        verifyNoInteractions(metrics);
    }

    @Test void completeMonthPreservesSignedAndNullableValuesInClosedWireShape() {
        var jdbc = mock(JdbcTemplate.class);
        var metricsRepository = mock(FeedMonthMetricsRepository.class);
        var service = new FeedMonthMetricsService(jdbc, metricsRepository);
        var account = UUID.randomUUID(); var student = UUID.randomUUID(); var academy = UUID.randomUUID();
        var start = Instant.parse("2026-07-01T00:00:00Z");
        var asOf = Instant.parse("2026-09-01T00:00:00Z");
        when(jdbc.queryForObject(anyString(), eq(Timestamp.class), eq(account), eq(student), eq(academy)))
                .thenReturn(Timestamp.from(start));
        when(jdbc.queryForObject("SELECT started_at FROM behavior_collection WHERE id=1", Timestamp.class))
                .thenReturn(Timestamp.from(start));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("metrics_version", "core-metrics-v1"); metrics.put("total_savings", -100L);
        metrics.put("pace_bias", null);
        when(metricsRepository.metrics(account, student, academy, YearMonth.of(2026, 8).atDay(1), asOf))
                .thenReturn(metrics);
        var result = service.build(account, student, academy, YearMonth.of(2026, 8), asOf);
        assertThat(result).containsOnlyKeys("month", "coverage", "metrics_version", "values");
        assertThat(result).containsEntry("coverage", "COMPLETE");
        assertThat(((Map<?, ?>) result.get("values")).containsKey("metrics_version")).isFalse();
        assertThat(((Map<?, ?>) result.get("values")).get("total_savings")).isEqualTo(-100L);
        assertThat(((Map<?, ?>) result.get("values")).get("pace_bias")).isNull();
        assertThat(metrics).containsKey("metrics_version");
    }
}
