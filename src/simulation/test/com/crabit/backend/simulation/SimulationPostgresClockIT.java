package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimulationPostgresClockIT {
    @Test void independentDatabasesDoNotShareTimeAndClosedDatabaseCannotBeReused() {
        Instant realBefore = Instant.now();
        var first = new SimulationPostgresClock();
        try(first; var second = new SimulationPostgresClock()) {
            first.executeAt(Instant.parse("2026-07-31T15:00:00Z"), step -> null);
            second.executeAt(SimulationCashOracle.START, step -> {
                assertThat(step.jdbc().queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant()).isEqualTo(SimulationCashOracle.START);
                return null;
            });
            first.executeAt(SimulationCashOracle.END.minusNanos(1000), step -> {
                assertThat(step.jdbc().queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant()).isEqualTo(SimulationCashOracle.END.minusNanos(1000));
                return null;
            });
            second.executeAt(Instant.parse("2026-06-30T15:00:00Z"), step -> null);
        }
        assertThatThrownBy(()->first.executeAt(SimulationCashOracle.START, step -> null)).hasMessage("SIMULATION_CLOSED");
        // Host wall clock is not moved to the dataset's historical window.
        assertThat(Duration.between(realBefore,Instant.now()).abs()).isLessThan(Duration.ofMinutes(2));
    }

    @Test void processClockControlsMigrationsHistoryMicrosecondsAndRollback() {
        Instant start = SimulationCashOracle.START;
        UUID academy = UUID.randomUUID(), student = UUID.randomUUID(), account = UUID.randomUUID(), wish = UUID.randomUUID();
        try (var db = new SimulationPostgresClock()) {
            db.executeAt(start, step -> {
                var j=step.jdbc();
                assertThat(step.clock().instant()).isEqualTo(start);
                assertThat(step.clock().withZone(ZoneId.of("Asia/Seoul")).instant()).isEqualTo(start);
                assertThat(j.queryForObject("SELECT started_at FROM feed_history_collection", Timestamp.class).toInstant()).isEqualTo(start);
                j.update("INSERT INTO academy(id,name) VALUES (?, 'simulation')",academy);
                j.update("INSERT INTO student(id,nickname,age,age_provenance) VALUES (?, 'synthetic',9,'PROVIDED')",student);
                j.update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,?)",UUID.randomUUID(),student,academy,Timestamp.from(start));
                j.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",account,student,academy,Timestamp.from(start));
                j.update("""
                    INSERT INTO wish(id,account_id,academy_id,purpose,target_amount,wish_amount,state,visibility,created_at,version)
                    VALUES (?,?,?,'synthetic goal',10000,0,'IN_PROGRESS','PRIVATE',?,0)
                    """,wish,account,academy,Timestamp.from(start));
                return null;
            });
            Instant boundary=Instant.parse("2026-06-30T15:00:00.123456Z");
            db.executeAt(boundary, step -> {
                var j=step.jdbc();
                var history = new com.crabit.backend.history.HistoricalBalanceQueryService(step.jdbc())
                    .query(academy,student,account,LocalDate.of(2026,6,1),LocalDate.of(2026,7,1),
                        com.crabit.backend.history.HistoricalPeriods.Granularity.MONTH,null);
                assertThat(history.get("readSnapshotAt")).isEqualTo(boundary.toString());
                // Synthetic SQL mutation tests timestamp triggers only; it is not domain replay evidence.
                j.update("UPDATE wish SET wish_amount=1000,version=version+1 WHERE id=?",wish);
                assertThat(j.queryForObject("SELECT valid_from FROM feed_source_history WHERE source_kind='wish' AND source_id=? AND valid_to IS NULL",Timestamp.class,wish).toInstant()).isEqualTo(boundary);
                assertThat(j.queryForObject("SELECT max(valid_to) FROM feed_source_history WHERE source_kind='wish' AND source_id=?",Timestamp.class,wish).toInstant()).isEqualTo(boundary);
                assertThat(step.clock().instant()).isEqualTo(boundary);
                assertThatThrownBy(()->db.executeServicesAt(boundary, nested->null)).hasMessage("SIMULATION_AMBIENT_TRANSACTION");
                return null;
            });
            // Checkpoint is a deferred constraint trigger; inspect it only after the prior commit.
            db.executeAt(boundary, step -> {
                assertThat(step.jdbc().queryForObject("SELECT max(applied_at) FROM historical_balance_checkpoint WHERE account_id=?",Timestamp.class,account).toInstant()).isEqualTo(boundary);
                return null;
            });
            Instant failedAt=boundary.plusNanos(1000);
            assertThatThrownBy(()->db.executeAt(failedAt, step -> {
                step.jdbc().update("UPDATE wish SET wish_amount=2000,version=version+1 WHERE id=?",wish);
                throw new IllegalStateException("deliberate rollback");
            })).hasMessage("deliberate rollback");
            db.executeAt(failedAt, step -> {
                assertThat(step.jdbc().queryForObject("SELECT wish_amount FROM wish WHERE id=?",Long.class,wish)).isEqualTo(1000);
                assertThat(step.jdbc().queryForObject("SELECT max(applied_at) FROM historical_balance_checkpoint WHERE account_id=?",Timestamp.class,account).toInstant()).isEqualTo(boundary);
                assertThat(step.clock().instant()).isEqualTo(failedAt);
                assertThatThrownBy(()->db.executeAt(failedAt, nested -> null)).hasMessage("SIMULATION_NESTED_STEP");
                return null;
            });
            for(Instant invalid:new Instant[]{start,SimulationCashOracle.END,failedAt.plusNanos(1)})
                assertThatThrownBy(()->db.executeAt(invalid, step -> {throw new AssertionError("must not execute");}))
                    .hasMessage("SIMULATION_TIME_RANGE_OR_PRECISION");
        }
    }
}
