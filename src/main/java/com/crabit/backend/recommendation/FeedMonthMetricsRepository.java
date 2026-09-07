package com.crabit.backend.recommendation;

import com.crabit.backend.recap.RecapAuthorMetrics;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the account-wide, correction-collapsed inputs used by closed feed month metrics. */
@Repository
public class FeedMonthMetricsRepository {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;

    public FeedMonthMetricsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> metrics(UUID accountId, UUID studentId, UUID academyId,
            LocalDate monthStart, Instant snapshotAt) {
        Instant from = monthStart.atStartOfDay(SEOUL).toInstant();
        Instant to = monthStart.plusMonths(1).atStartOfDay(SEOUL).toInstant();
        long abandons = jdbc.queryForObject("""
                SELECT count(*) FROM wish WHERE account_id=? AND state='ABANDONED'
                  AND abandoned_at>=? AND abandoned_at<?
                """, Long.class, accountId, Timestamp.from(from), Timestamp.from(to));
        long visits = jdbc.queryForObject("""
                SELECT count(*) FROM behavior_event
                WHERE academy_id=? AND event_type='PROFILE_VISIT' AND actor_id=?
                  AND occurred_at>=? AND occurred_at<? AND received_at<=?
                """, Long.class, academyId, studentId, Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(snapshotAt));
        return RecapAuthorMetrics.compute(effectiveTransactions(accountId, snapshotAt), monthStart,
                abandons, visits);
    }

    private List<RecapAuthorMetrics.Transaction> effectiveTransactions(UUID accountId, Instant snapshotAt) {
        Map<UUID, Event> events = new LinkedHashMap<>();
        jdbc.query("""
                SELECT e.id,e.event_type,e.account_delta,e.occurred_at,e.correction_of_event_id,
                       x.wish_id,x.wish_delta
                FROM ledger_event e LEFT JOIN ledger_wish_effect x
                  ON x.event_id=e.id AND x.account_id=e.account_id
                WHERE e.account_id=? AND e.occurred_at<=?
                ORDER BY e.occurred_at,e.id,x.wish_id
                """, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            Event event = events.get(id);
            if (event == null) {
                event = new Event(id, rs.getString("event_type"), rs.getLong("account_delta"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getObject("correction_of_event_id", UUID.class), new LinkedHashMap<>());
                events.put(id, event);
            }
            UUID wish = rs.getObject("wish_id", UUID.class);
            if (wish != null && event.effects().put(wish, rs.getLong("wish_delta")) != null)
                throw new IllegalStateException("Duplicate feed ledger effect");
        }, accountId, Timestamp.from(snapshotAt));

        Map<UUID, Integer> children = new HashMap<>();
        for (Event event : events.values()) if (event.parent() != null) {
            if (!events.containsKey(event.parent())) throw new IllegalStateException("Missing feed correction parent");
            if (children.merge(event.parent(), 1, Integer::sum) > 1)
                throw new IllegalStateException("Branched feed correction chain");
        }
        Map<UUID, List<Event>> chains = new LinkedHashMap<>();
        for (Event event : events.values()) {
            Event cursor = event;
            Set<UUID> seen = new HashSet<>();
            while (cursor.parent() != null) {
                if (!seen.add(cursor.id())) throw new IllegalStateException("Cyclic feed correction chain");
                cursor = events.get(cursor.parent());
            }
            chains.computeIfAbsent(cursor.id(), ignored -> new ArrayList<>()).add(event);
        }

        List<RecapAuthorMetrics.Transaction> result = new ArrayList<>();
        for (List<Event> chain : chains.values()) {
            chain.sort(Comparator.comparing(Event::occurredAt).thenComparing(Event::id));
            Event root = chain.stream().filter(event -> event.parent() == null).findFirst().orElseThrow();
            long accountDelta = 0;
            Map<UUID, Long> effects = new LinkedHashMap<>();
            for (Event event : chain) {
                accountDelta = Math.addExact(accountDelta, event.accountDelta());
                event.effects().forEach((wish, delta) -> effects.merge(wish, delta, Math::addExact));
            }
            List<Map.Entry<UUID, Long>> nonzero = effects.entrySet().stream()
                    .filter(entry -> entry.getValue() != 0).sorted(Map.Entry.comparingByKey()).toList();
            if (nonzero.isEmpty()) continue;
            if (root.type().equals("WISH_TRANSFER")) {
                if (accountDelta != 0 || nonzero.size() != 2
                        || Math.addExact(nonzero.get(0).getValue(), nonzero.get(1).getValue()) != 0)
                    throw new IllegalStateException("Ambiguous feed transfer chain");
            } else if (nonzero.size() != 1) {
                throw new IllegalStateException("Ambiguous feed ledger chain");
            }
            for (var effect : nonzero) {
                result.add(new RecapAuthorMetrics.Transaction(root.occurredAt(),
                        Math.abs(effect.getValue()), classify(root.type(), effect.getValue())));
            }
        }
        return List.copyOf(result);
    }

    private static String classify(String rootType, long wishDelta) {
        if (rootType.equals("WISH_TRANSFER")) return wishDelta > 0 ? "TRANSFER_IN" : "TRANSFER_OUT";
        return switch (rootType) {
            case "WISH_DEPOSIT" -> wishDelta > 0 ? "DEPOSIT" : "WITHDRAWAL";
            case "WISH_WITHDRAWAL" -> wishDelta < 0 ? "WITHDRAWAL" : "DEPOSIT";
            case "WISH_COMPLETION_RETURN" -> "COMPLETION_RETURN";
            case "WISH_ABANDONMENT_RETURN" -> "ABANDONMENT_RETURN";
            case "WISH_DELETION_RETURN" -> "DELETION_RETURN";
            default -> throw new IllegalStateException("Ambiguous feed ledger type");
        };
    }

    private record Event(UUID id, String type, long accountDelta, Instant occurredAt, UUID parent,
            Map<UUID, Long> effects) {}
}
