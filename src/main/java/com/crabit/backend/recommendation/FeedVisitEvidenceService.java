package com.crabit.backend.recommendation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import com.crabit.backend.behavior.BehaviorException;
import com.crabit.backend.relationship.RelationshipContextAuthorizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Captures one immutable local category set in the accepting event transaction. */
@Service
public class FeedVisitEvidenceService {
    private final JdbcTemplate jdbc;
    private final FeedCategoryClassifier classifier;
    private final ObjectMapper json;
    private final RelationshipContextAuthorizationService access;

    public FeedVisitEvidenceService(JdbcTemplate jdbc, FeedCategoryClassifier classifier, ObjectMapper json,
            RelationshipContextAuthorizationService access) {
        this.jdbc = jdbc; this.classifier = classifier; this.json = json; this.access = access;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(UUID actor, UUID event, UUID target, UUID academy, Instant occurred, Instant received) {
        // Canonical table order. SHARE waits for all source mutations to commit, then prevents
        // new mutations until evidence commits. This reader never locks source rows or takes
        // account/relationship advisory locks, so it cannot invert their existing lock order.
        jdbc.execute("LOCK TABLE academy_membership, card_balance_account, shared_card, student_block, student_follow, wish IN SHARE MODE");
        if (!access.canViewAcademyCard(target, actor, academy))
            throw new BehaviorException("PROFILE_NOT_FOUND", 404);
        Instant captured = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        Instant baseline = jdbc.queryForObject("SELECT started_at FROM feed_history_collection WHERE id=1", Timestamp.class).toInstant();
        String reason = occurred.isAfter(captured) ? "FUTURE_OCCURRED_AT"
                : occurred.isBefore(baseline) ? "BEFORE_BASELINE" : null;
        var categories = new TreeSet<String>();
        var versions = new ArrayList<String>();
        versions.add("baseline:" + baseline);
        if (reason == null) {
            var time = Timestamp.from(occurred);
            var rows = jdbc.queryForList("""
                WITH h AS MATERIALIZED (
                  SELECT * FROM feed_source_history WHERE valid_from <= ? AND (valid_to IS NULL OR ? < valid_to)
                ), memberships AS (SELECT payload p FROM h WHERE source_kind='academy_membership'),
                blocks AS (SELECT payload p FROM h WHERE source_kind='student_block'),
                follows AS (SELECT payload p FROM h WHERE source_kind='student_follow')
                SELECT w.payload->>'purpose' purpose, w.version wish_version, c.version card_version,
                       a.version account_version
                FROM h w JOIN h c ON c.source_kind='shared_card' AND c.payload->>'wish_id'=w.source_id::text
                JOIN h a ON a.source_kind='card_balance_account' AND a.source_id::text=w.payload->>'account_id'
                WHERE w.source_kind='wish' AND a.payload->>'student_id'=? AND w.payload->>'academy_id'=?
                  AND w.payload->>'deleted_at' IS NULL AND a.payload->>'closed_at' IS NULL
                  AND c.payload->>'visibility' IN ('ACADEMY','FOLLOWERS')
                  AND EXISTS(SELECT 1 FROM memberships WHERE p->>'student_id'=? AND p->>'academy_id'=? AND p->>'left_at' IS NULL)
                  AND EXISTS(SELECT 1 FROM memberships WHERE p->>'student_id'=? AND p->>'academy_id'=? AND p->>'left_at' IS NULL)
                  AND NOT EXISTS(SELECT 1 FROM blocks WHERE p->>'released_at' IS NULL AND
                    ((p->>'blocker_id'=? AND p->>'blocked_id'=?) OR (p->>'blocker_id'=? AND p->>'blocked_id'=?)))
                  AND (c.payload->>'visibility'='ACADEMY' OR EXISTS(SELECT 1 FROM follows
                    WHERE p->>'source_id'=? AND p->>'target_id'=? AND p->>'academy_id'=? AND p->>'ended_at' IS NULL))
                """, time, time, target.toString(), academy.toString(), target.toString(), academy.toString(),
                    actor.toString(), academy.toString(), actor.toString(), target.toString(), target.toString(),
                    actor.toString(), actor.toString(), target.toString(), academy.toString());
            // All relevant membership/follow/block versions, including absence, are proven by
            // the collection boundary and the committed history high-water mark.
            Long highWater = jdbc.queryForObject("SELECT coalesce(max(version),0) FROM feed_source_history", Long.class);
            versions.add("history:" + highWater);
            for (var row : rows) {
                categories.add(classifier.classify((String) row.get("purpose")));
                versions.add("wish:" + row.get("wish_version"));
                versions.add("card:" + row.get("card_version"));
                versions.add("account:" + row.get("account_version"));
            }
        }
        // Reuse is possible only strictly after the old immutable signal retention end.
        jdbc.update("DELETE FROM feed_visit_evidence WHERE actor_id=? AND event_id=? AND greatest(received_at,occurred_at)<?",
                actor,event,Timestamp.from(received.minus(java.time.Duration.ofDays(90))));
        jdbc.update("""
            INSERT INTO feed_visit_evidence(actor_id,event_id,target_author_id,academy_id,occurred_at,received_at,
              captured_at,evidence_status,category_ids,classifier_version,history_coverage_start,source_versions,unknown_reason)
            VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?)
            """, actor,event,target,academy,Timestamp.from(occurred),Timestamp.from(received),Timestamp.from(captured),
                reason == null ? "COMPLETE" : "UNKNOWN", reason == null ? json.writeValueAsString(categories) : null,
                classifier.version(),Timestamp.from(baseline),json.writeValueAsString(versions.stream().distinct().toList()),reason);
    }
}
