package com.crabit.backend.recommendation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Interest is historical evidence; current candidate authorization remains mandatory separately. */
@Service
public class FeedVisitSignals {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public FeedVisitSignals(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    @Transactional(readOnly=true)
    public Signals at(UUID actor, UUID academy, Instant recommendationAt) {
        var authors=new HashSet<UUID>(); var categories=new HashSet<String>();
        var rows=jdbc.queryForList("""
            SELECT e.target_id,v.evidence_status,v.category_ids::text category_ids
            FROM behavior_event e LEFT JOIN feed_visit_evidence v
              ON v.actor_id=e.actor_id AND v.event_id=e.event_id
            WHERE e.actor_id=? AND e.academy_id=? AND e.event_type='PROFILE_VISIT'
              AND e.occurred_at>=? AND e.occurred_at<=? AND e.received_at<=?
            """,actor,academy,Timestamp.from(recommendationAt.minus(Duration.ofDays(90))),
                Timestamp.from(recommendationAt),Timestamp.from(recommendationAt));
        for (var row:rows) {
            authors.add((UUID)row.get("target_id"));
            if ("COMPLETE".equals(row.get("evidence_status")))
                json.readTree((String)row.get("category_ids")).forEach(category -> categories.add(category.stringValue()));
        }
        return new Signals(Set.copyOf(authors),Set.copyOf(categories));
    }
    public record Signals(Set<UUID> authors,Set<String> categories) {}
}
