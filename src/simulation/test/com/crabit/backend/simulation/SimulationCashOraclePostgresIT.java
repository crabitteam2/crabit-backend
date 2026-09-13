package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import static com.crabit.backend.e2e.SeedFixtureCatalog.*;
import static com.crabit.backend.simulation.SimulationCashOracle.*;
import com.crabit.backend.demo.DemoSimulationCashService;
import com.crabit.backend.e2e.*;
import java.sql.Timestamp;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

class SimulationCashOraclePostgresIT {
    @Test void independentlyReconcilesRealServiceLedgerAndRollbackOnDisposablePostgres() {
        try(var pg=new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start();var ds=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
            Flyway.configure().dataSource(ds).load().migrate();var jdbc=new JdbcTemplate(ds);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.executeWithoutResult(s->new SeedFixtureService(jdbc,new SeedFixtureCatalog()).initialize());
            UUID account=UUID.fromString("00000000-0000-0000-0000-000000000302");
            String dataset="sha256:"+"b".repeat(64);
            jdbc.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",
                account,FRIEND_ID,PRIMARY_ACADEMY_ID,Timestamp.from(START));
            jdbc.update("INSERT INTO demo_simulation_dataset(dataset_id,manifest_digest,state,starts_at,ends_at) VALUES (?,?,'BUILDING',?,?)",
                dataset,dataset,Timestamp.from(START),Timestamp.from(END));
            jdbc.update("INSERT INTO demo_simulation_account(account_id,dataset_id,logical_student_id,logical_account_id,grade,is_owner) VALUES (?,?,'s','a',3,false)",account,dataset);
            var service=new DemoSimulationCashService(jdbc);
            List<Command> commands=List.of(
                new Command("grant",1,"a",START,Kind.GRANT,10000,"cash-grant",Outcome.APPLIED,"2026-06",START),
                new Command("purchase",2,"a",START.plusSeconds(1),Kind.PURCHASE,3000,"cash-purchase",Outcome.APPLIED,null,null),
                new Command("reject",3,"a",START.plusSeconds(2),Kind.PURCHASE,8000,"cash-reject",Outcome.REJECTED,null,null),
                new Command("rollback",4,"a",START.plusSeconds(3),Kind.GRANT,1000,"cash-rollback",Outcome.FAILED,"2026-06",START.plusSeconds(3)));
            for(Command c:commands) {
                Runnable effect=()->tx.executeWithoutResult(s->{
                    service.apply(dataset,c.eventId(),account,DemoSimulationCashService.Kind.valueOf(c.kind().name()),c.amountKrw(),c.occurredAt());
                    if(c.outcome()==Outcome.FAILED)throw new IllegalStateException("controlled failure before commit");
                });
                if(c.outcome()==Outcome.APPLIED)effect.run();
                else if(c.outcome()==Outcome.REJECTED)assertThatThrownBy(effect::run).isInstanceOf(IllegalArgumentException.class);
                else assertThatThrownBy(effect::run).isInstanceOf(IllegalStateException.class);
            }
            // Read actual persisted fields. SQL computes the ledger projection independently of the Java oracle.
            var entries=jdbc.query("""
                SELECT event_id, sequence, kind, amount_krw, occurred_at,
                    sum(CASE WHEN kind='GRANT' THEN amount_krw ELSE -amount_krw END)
                    OVER (ORDER BY sequence) AS balance_after
                FROM demo_simulation_cash_event WHERE dataset_id=? AND account_id=? ORDER BY sequence
                """,(rs,row)->new Entry("cash-"+rs.getString("event_id"),rs.getString("event_id"),"a",
                    rs.getLong("sequence"),rs.getTimestamp("occurred_at").toInstant(),Kind.valueOf(rs.getString("kind")),
                    rs.getLong("amount_krw"),rs.getLong("balance_after")),dataset,account);
            var balances=jdbc.query("SELECT card_funds,cash_sequence FROM demo_simulation_account WHERE account_id=?",
                (rs,row)->new Balance("a",rs.getLong("card_funds"),rs.getLong("cash_sequence")),account);
            var oracle=new SimulationCashOracle();
            var result=oracle.verify(List.of(new Account("a",START)),commands,entries,balances);
            assertThat(result.balances().get("a")).isEqualTo(new Balance("a",7000,2));
            assertThat(result.applied()).isEqualTo(2);assertThat(result.rejected()).isEqualTo(1);assertThat(result.failed()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM balance_observation WHERE account_id=?",Long.class,account)).isZero();
            // A changed cache with intact source commands/ledger must be detected, never repaired by the oracle.
            jdbc.update("UPDATE demo_simulation_account SET card_funds=7001 WHERE account_id=?",account);
            var corrupt=jdbc.query("SELECT card_funds,cash_sequence FROM demo_simulation_account WHERE account_id=?",
                (rs,row)->new Balance("a",rs.getLong("card_funds"),rs.getLong("cash_sequence")),account);
            assertThatThrownBy(()->oracle.verify(List.of(new Account("a",START)),commands,entries,corrupt))
                .hasMessageContaining("FINAL_BALANCE_MISMATCH");
            assertThat(jdbc.queryForObject("SELECT card_funds FROM demo_simulation_account WHERE account_id=?",Long.class,account)).isEqualTo(7001);
        }
    }
}
