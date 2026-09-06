package com.crabit.backend.behavior;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

@Component
public class BehaviorRetention {
    private static final Logger log = LoggerFactory.getLogger(BehaviorRetention.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public BehaviorRetention(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(
            initialDelayString = "${crabit.behavior.cleanup-delay-ms:3600000}",
            fixedDelayString = "${crabit.behavior.cleanup-delay-ms:3600000}")
    public void cleanup() {
        // 100 transactions of at most 1000 records per table: finite work per hourly run.
        for (int batch = 0; batch < 100; batch++) {
            Integer removed = transactions.execute(status -> batch());
            if (removed == null || removed == 0) break;
        }
        var cutoff = BehaviorService.ts(clock.instant().minus(Duration.ofDays(90)));
        Long pending =
                jdbc.queryForObject(
                        "SELECT count(*) FROM behavior_event WHERE received_at<=?",
                        Long.class,
                        cutoff);
        if (pending != null && pending > 0)
            log.warn(
                    "Behavior retention backlog: {} expired events; physical deletion target is 24"
                            + " hours",
                    pending);
    }

    private int batch() {
        var now = clock.instant();
        int events =
                jdbc.update(
                        """
DELETE FROM behavior_event WHERE (actor_id,event_id) IN
(SELECT actor_id,event_id FROM behavior_event WHERE received_at<=? ORDER BY received_at LIMIT 1000 FOR UPDATE SKIP LOCKED)
""",
                        BehaviorService.ts(now.minus(Duration.ofDays(90))));
        int impressions =
                jdbc.update(
                        """
WITH expired_contexts AS MATERIALIZED (
  SELECT c.id FROM behavior_result_context c WHERE c.created_at<=?
  AND EXISTS(SELECT 1 FROM behavior_impression p WHERE p.context_id=c.id
    AND NOT EXISTS(SELECT 1 FROM behavior_event e WHERE e.actor_id=p.actor_id AND e.impression_id=p.impression_id))
  ORDER BY c.created_at LIMIT 1000 FOR UPDATE OF c SKIP LOCKED
)
DELETE FROM behavior_impression i WHERE (i.actor_id,i.impression_id) IN
 (SELECT p.actor_id,p.impression_id FROM behavior_impression p JOIN behavior_result_context c ON c.id=p.context_id
  WHERE c.id IN (SELECT id FROM expired_contexts) AND NOT EXISTS(SELECT 1 FROM behavior_event e WHERE e.actor_id=p.actor_id AND e.impression_id=p.impression_id)
  LIMIT 1000 FOR UPDATE OF p SKIP LOCKED)
""",
                        BehaviorService.ts(now.minus(Duration.ofHours(24))));
        int contexts =
                jdbc.update(
                        """
DELETE FROM behavior_result_context WHERE id IN
 (SELECT c.id FROM behavior_result_context c WHERE c.created_at<=?
  AND NOT EXISTS(SELECT 1 FROM behavior_impression i WHERE i.context_id=c.id)
  LIMIT 1000 FOR UPDATE SKIP LOCKED)
""",
                        BehaviorService.ts(now.minus(Duration.ofHours(24))));
        return events + impressions + contexts;
    }
}
