package com.crabit.backend.behavior;

import static com.crabit.backend.behavior.BehaviorService.ts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class BehaviorMetricsService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final BehaviorService behavior;

    public BehaviorMetricsService(JdbcTemplate jdbc, Clock clock, BehaviorService behavior) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.behavior = behavior;
    }

    // The same snapshot serves all totals, daily metrics, and current authorization.
    private static final String ELIGIBLE =
            """
WITH eligible AS (
  SELECT e.* FROM behavior_event e
  WHERE e.academy_id=? AND e.occurred_at>=? AND e.occurred_at<? AND e.occurred_at<=? AND e.received_at>?
  AND EXISTS(SELECT 1 FROM academy_membership m WHERE m.student_id=e.actor_id AND m.academy_id=e.academy_id AND m.left_at IS NULL)
  AND EXISTS(SELECT 1 FROM academy_membership m WHERE m.student_id=e.target_id AND m.academy_id=e.academy_id AND m.left_at IS NULL)
  AND NOT EXISTS(SELECT 1 FROM student_block b WHERE b.released_at IS NULL AND
    ((b.blocker_id=e.actor_id AND b.blocked_id=e.target_id) OR (b.blocker_id=e.target_id AND b.blocked_id=e.actor_id)))
  AND (e.event_type='PROFILE_VISIT' OR EXISTS(
    SELECT 1 FROM shared_card c JOIN wish w ON w.id=c.wish_id JOIN card_balance_account a ON a.id=w.account_id
    WHERE c.id=e.card_id AND a.student_id=e.target_id AND w.academy_id=e.academy_id
    AND a.closed_at IS NULL AND w.deleted_at IS NULL AND w.state<>'ABANDONED'
    AND (c.visibility='ACADEMY' OR (c.visibility='FOLLOWERS' AND EXISTS(
      SELECT 1 FROM student_follow f WHERE f.academy_id=e.academy_id AND f.source_id=e.actor_id
        AND f.target_id=e.target_id AND f.ended_at IS NULL)))))
)
""";

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> profile(
            UUID academy, UUID student, UUID author, LocalDate from, LocalDate to) {
        var period = period(from, to);
        Instant now = BehaviorService.micros(clock.instant());
        behavior.requireProfile(student, student, academy);
        if (author != null) behavior.requireProfile(student, author, academy);
        String where =
                author == null
                        ? "event_type='PROFILE_VISIT' AND target_id=?"
                        : "event_type='PROFILE_VISIT' AND actor_id=? AND target_id=?";
        var args = new ArrayList<Object>(args(academy, period, now));
        args.add(student);
        if (author != null) args.add(author);
        var totals =
                jdbc.queryForMap(
                        ELIGIBLE
                                + "SELECT count(*) AS visits, count(DISTINCT actor_id) AS visitors"
                                + " FROM eligible WHERE "
                                + where,
                        args.toArray());
        var days =
                jdbc.queryForList(
                        ELIGIBLE
                                + "SELECT (occurred_at AT TIME ZONE 'Asia/Seoul')::date AS day,"
                                + " count(*) AS visits, count(DISTINCT actor_id) AS visitors FROM"
                                + " eligible WHERE "
                                + where
                                + " GROUP BY day",
                        args.toArray());
        Map<LocalDate, Map<String, Object>> byDate = new HashMap<>();
        for (var d : days) byDate.put(((java.sql.Date) d.get("day")).toLocalDate(), d);
        long visits = ((Number) totals.get("visits")).longValue();
        Instant started = started();
        var coverage = coverage(period.start, period.end, now, started, visits > 0);
        var result = base(academy, period, now, coverage);
        result.put("studentId", student);
        if (author != null) result.put("authorStudentId", author);
        String count = author == null ? "visitCount" : "profileVisitCount";
        boolean none = coverage.get("status").equals("NONE");
        result.put(count, none ? null : visits);
        if (author == null)
            result.put("distinctVisitorCount", none ? null : totals.get("visitors"));
        List<Map<String, Object>> daily = new ArrayList<>();
        for (LocalDate day = from; day.isBefore(to); day = day.plusDays(1)) {
            var d = byDate.get(day);
            long n = d == null ? 0 : ((Number) d.get("visits")).longValue();
            var c =
                    coverage(
                            day.atStartOfDay(SEOUL).toInstant(),
                            day.plusDays(1).atStartOfDay(SEOUL).toInstant(),
                            now,
                            started,
                            n > 0);
            boolean unavailable = c.get("status").equals("NONE");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", day);
            item.put("coverageStatus", c.get("status"));
            item.put(count, unavailable ? null : n);
            if (author == null)
                item.put(
                        "distinctVisitorCount",
                        unavailable ? null : (d == null ? 0 : d.get("visitors")));
            daily.add(item);
        }
        result.put("daily", daily);
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> feed(UUID academy, LocalDate from, LocalDate to) {
        var period = period(from, to);
        Instant now = BehaviorService.micros(clock.instant());
        if (Boolean.FALSE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM academy WHERE id=?)", Boolean.class, academy)))
            throw new BehaviorException("ACADEMY_NOT_FOUND", 404);
        var rows =
                jdbc.queryForList(
                        ELIGIBLE
                                + """
SELECT 'LATEST' AS "sortSource", e.position,
 count(*) FILTER(WHERE e.event_type='FEED_EXPOSURE') AS "exposureCount",
 count(*) FILTER(WHERE e.event_type='FEED_CLICK') AS "clickCount",
 count(*) FILTER(WHERE e.event_type='FEED_EXPOSURE' AND EXISTS(SELECT 1 FROM eligible c
    WHERE c.actor_id=e.actor_id AND c.impression_id=e.impression_id AND c.event_type='FEED_CLICK')) AS "clickedExposedImpressionCount",
 count(*) FILTER(WHERE e.event_type='FEED_CLICK' AND NOT EXISTS(SELECT 1 FROM eligible x
    WHERE x.actor_id=e.actor_id AND x.impression_id=e.impression_id AND x.event_type='FEED_EXPOSURE')) AS "unmatchedClickCount"
FROM eligible e WHERE e.event_type<>'PROFILE_VISIT' GROUP BY e.position ORDER BY e.position
""",
                        args(academy, period, now).toArray());
        for (var row : rows) {
            long denominator = ((Number) row.get("exposureCount")).longValue();
            row.put(
                    "ctr",
                    denominator == 0
                            ? null
                            : ((Number) row.get("clickedExposedImpressionCount")).doubleValue()
                                    / denominator);
        }
        var result =
                base(
                        academy,
                        period,
                        now,
                        coverage(period.start, period.end, now, started(), !rows.isEmpty()));
        result.put("items", rows);
        return result;
    }

    private Instant started() {
        return jdbc.queryForObject(
                        "SELECT started_at FROM behavior_collection WHERE id=1",
                        java.sql.Timestamp.class)
                .toInstant();
    }

    static Map<String, Object> coverage(
            Instant from, Instant to, Instant now, Instant started, boolean observed) {
        Instant cutoff = now.minus(Duration.ofDays(90)), retained = cutoff.plusSeconds(300);
        Instant full = started.isAfter(retained) ? started : retained;
        boolean overlap = to.isAfter(full) && from.isBefore(now) && full.isBefore(now);
        String status =
                !from.isBefore(full) && !to.isAfter(now)
                        ? "COMPLETE"
                        : overlap || observed ? "PARTIAL" : "NONE";
        List<String> reasons = new ArrayList<>();
        if (from.isBefore(started)) reasons.add("BEFORE_COLLECTION");
        if (from.isBefore(retained)) reasons.add("RETENTION_EXPIRED");
        if (to.isAfter(now)) reasons.add("OPEN_PERIOD");
        return Map.of(
                "status",
                status,
                "collectionStartedAt",
                started,
                "retentionCutoffReceivedAt",
                cutoff,
                "fullyRetainedFrom",
                full,
                "availableThrough",
                now,
                "reasons",
                reasons,
                "countsMeaning",
                "RECORDED_ELIGIBLE_EVENTS_ONLY");
    }

    private static Map<String, Object> base(
            UUID academy, Period p, Instant now, Map<String, Object> coverage) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("academyId", academy);
        out.put(
                "period",
                Map.of(
                        "fromDate",
                        p.from,
                        "toDate",
                        p.to,
                        "timezone",
                        "Asia/Seoul",
                        "fromInclusive",
                        p.start,
                        "toExclusive",
                        p.end));
        out.put("asOf", now);
        out.put("coverage", coverage);
        return out;
    }

    private static List<Object> args(UUID academy, Period p, Instant now) {
        return List.of(
                academy, ts(p.start), ts(p.end), ts(now), ts(now.minus(Duration.ofDays(90))));
    }

    private Period period(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 1
                || days > 90
                || to.isAfter(LocalDate.ofInstant(clock.instant(), SEOUL).plusDays(1)))
            throw BehaviorException.malformed();
        return new Period(
                from, to, from.atStartOfDay(SEOUL).toInstant(), to.atStartOfDay(SEOUL).toInstant());
    }

    private record Period(LocalDate from, LocalDate to, Instant start, Instant end) {}
}
