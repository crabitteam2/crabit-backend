package com.crabit.backend.demo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.crabit.backend.e2e.SeedFixtureCatalog.*;
import com.crabit.backend.balance.*;
import com.crabit.backend.e2e.*;
import com.crabit.backend.wish.KrwAmount;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DemoSimulationPostgresIT {
    private static final String DATASET="sha256:"+"a".repeat(64);
    private static final Instant START=Instant.parse("2026-05-31T15:00:00Z");
    @Test void migrationCashRoutingAndPreservationAreTransactional() {
        try(var pg=new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start(); var ds=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
            Flyway.configure().dataSource(ds).load().migrate();
            var jdbc=new JdbcTemplate(ds); var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            var seeds=new SeedFixtureService(jdbc,new SeedFixtureCatalog());
            tx.executeWithoutResult(s -> seeds.initialize());
            UUID account=UUID.fromString("00000000-0000-0000-0000-000000000302");
            jdbc.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",account,FRIEND_ID,PRIMARY_ACADEMY_ID,Timestamp.from(START));
            // Replay fixture account opening precedes the scenario; actual business services remain unchanged.
            Instant time=jdbc.queryForObject("SELECT opened_at FROM card_balance_account WHERE id=?", Timestamp.class,account).toInstant().plusSeconds(1);
            jdbc.update("INSERT INTO demo_simulation_dataset(dataset_id,manifest_digest,state,starts_at,ends_at) VALUES (?,?,'BUILDING',?,?)",
                DATASET,DATASET,Timestamp.from(START),Timestamp.from(Instant.parse("2026-09-10T15:00:00Z")));
            jdbc.update("INSERT INTO demo_simulation_account(account_id,dataset_id,logical_student_id,logical_account_id,grade,is_owner) VALUES (?,?,'student-1','account-1',3,false)",account,DATASET);
            var cash=new DemoSimulationCashService(jdbc);
            assertThat(tx.<Long>execute(s -> cash.apply(DATASET,"grant-1",account,DemoSimulationCashService.Kind.GRANT,10000,time))).isEqualTo(10000L);
            assertThat(tx.<Long>execute(s -> cash.apply(DATASET,"grant-1",account,DemoSimulationCashService.Kind.GRANT,10000,time))).isEqualTo(10000L);
            assertThatThrownBy(() -> tx.execute(s -> cash.apply(DATASET,"grant-1",account,DemoSimulationCashService.Kind.GRANT,9000,time))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> tx.execute(s -> cash.apply(DATASET,"purchase-bad",account,DemoSimulationCashService.Kind.PURCHASE,10001,time))).isInstanceOf(IllegalArgumentException.class);
            assertThat(tx.<Long>execute(s -> cash.apply(DATASET,"purchase-1",account,DemoSimulationCashService.Kind.PURCHASE,3000,time.plusSeconds(1)))).isEqualTo(7000L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_simulation_cash_event",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM balance_observation WHERE account_id=?",Long.class,account)).isZero();
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> seeds.resetAndInitialize())).isInstanceOf(IllegalStateException.class);
            long before=jdbc.queryForObject("SELECT count(*) FROM wish",Long.class);
            seeds.initialize(); assertThat(jdbc.queryForObject("SELECT count(*) FROM wish",Long.class)).isEqualTo(before);
            var owner=mock(DemoHttpCardBalanceProvider.class);var provider=new DemoSimulationBalanceProvider(owner,jdbc);
            assertThat(provider.lookup(account)).isEqualTo(CardBalanceProviderResult.failure());
            jdbc.update("UPDATE demo_simulation_dataset SET state='APPLIED',applied_at=clock_timestamp() WHERE dataset_id=?",DATASET);
            var result=(CardBalanceProviderResult.Success)provider.lookup(account);
            assertThat(result.balance()).isEqualTo(KrwAmount.nonNegative(7000));
            assertThat(result.simulationDatasetId()).isEqualTo(DATASET);
            assertThat(provider.lookup(UUID.randomUUID())).isEqualTo(CardBalanceProviderResult.failure());
            verifyNoInteractions(owner);
            when(owner.lookup(OWNER_ACCOUNT_ID)).thenReturn(CardBalanceProviderResult.failure());
            assertThat(provider.lookup(OWNER_ACCOUNT_ID)).isEqualTo(CardBalanceProviderResult.failure());
            verifyNoInteractions(owner);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM demo_simulation_cash_event")).hasMessageContaining("append-only");
            assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO balance_observation(id,account_id,status,lookup_method,actual_card_balance,
                    first_successful,previous_successful_balance,observed_at,source_kind)
                VALUES (?,?,'SUCCEEDED','USER_REQUESTED',0,true,0,?,'SIMULATION')
                """,UUID.randomUUID(),account,Timestamp.from(time))).hasMessageContaining("ck_observation_source");
            var env=new org.springframework.mock.env.MockEnvironment();
            for(int grade=3;grade<=6;grade++) {
                env.setProperty("CRABIT_DEMO_TOKEN_GRADE_"+grade,"simulation-secret-grade-"+grade);
                for(int index=0;index<25;index++) {
                    if(grade==3 && index==1) continue; // registered friend account above
                    UUID id;
                    if(grade==3 && index==0) id=OWNER_ACCOUNT_ID;
                    else {
                        id=UUID.nameUUIDFromBytes(("account-"+grade+"-"+index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        UUID student=UUID.nameUUIDFromBytes(("student-"+grade+"-"+index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        jdbc.update("INSERT INTO student(id,nickname,age,age_provenance) VALUES (?,?,10,'PROVIDED')",student,"합성 학생");
                        jdbc.update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,?)",UUID.randomUUID(),student,PRIMARY_ACADEMY_ID,Timestamp.from(START));
                        jdbc.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",id,student,PRIMARY_ACADEMY_ID,Timestamp.from(START));
                    }
                    jdbc.update("INSERT INTO demo_simulation_account(account_id,dataset_id,logical_student_id,logical_account_id,grade,is_owner) VALUES (?,?,?,?,?,?)",id,DATASET,"s-"+grade+"-"+index,"a-"+grade+"-"+index,grade,id.equals(OWNER_ACCOUNT_ID));
                    if(index==2) jdbc.update("INSERT INTO demo_simulation_persona VALUES (?,?,?,?)","grade-"+grade,DATASET,id,"학년 대표 "+grade);
                }
            }
            var registry=new DemoRepresentativeRegistry(jdbc,env);
            var tokens=new DemoTokenRegistry("legacy-owner","legacy-friend","legacy-nonfriend","legacy-blocked","legacy-other","legacy-staff");
            tokens.configureRepresentatives(registry);
            for(int grade=3;grade<=6;grade++) assertThat(tokens.resolve("simulation-secret-grade-"+grade).orElseThrow().personaKey()).isEqualTo("grade-"+grade);
            clearInvocations(owner);
            var accounts=jdbc.queryForList("SELECT account_id FROM demo_simulation_account WHERE NOT is_owner",UUID.class);
            assertThat(accounts).hasSize(99);
            for(var id:accounts) assertThat(provider.lookup(id)).isInstanceOf(CardBalanceProviderResult.Success.class);
            verifyNoInteractions(owner);
            provider.lookup(OWNER_ACCOUNT_ID); verify(owner).lookup(OWNER_ACCOUNT_ID);
            clearInvocations(owner);
            // A corrupted cache must not be reported as a successful virtual observation.
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE demo_simulation_account SET card_funds=7001 WHERE account_id=?",account);
                assertThat(provider.lookup(account)).isEqualTo(CardBalanceProviderResult.failure());
                status.setRollbackOnly();
            });
            assertThat(provider.lookup(account)).isInstanceOf(CardBalanceProviderResult.Success.class);
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE demo_simulation_dataset SET state='RESTORED' WHERE dataset_id=?",DATASET);
                assertThat(provider.lookup(OWNER_ACCOUNT_ID)).isEqualTo(CardBalanceProviderResult.failure());
                assertThat(provider.lookup(account)).isEqualTo(CardBalanceProviderResult.failure());
                status.setRollbackOnly();
            });
            verifyNoInteractions(owner);
            assertThatThrownBy(() -> tx.execute(s -> cash.apply(DATASET,"backdated-live",account,
                DemoSimulationCashService.Kind.GRANT,100,time))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> tx.execute(s -> cash.applyCurrent(DATASET,"owner-live",OWNER_ACCOUNT_ID,
                DemoSimulationCashService.Kind.GRANT,100))).isInstanceOf(IllegalArgumentException.class);
            long eventCount=jdbc.queryForObject("SELECT count(*) FROM demo_simulation_cash_event",Long.class);
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                cash.applyCurrent(DATASET,"rollback-grant",account,DemoSimulationCashService.Kind.GRANT,100);
                throw new IllegalStateException("forced transaction rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_simulation_cash_event",Long.class)).isEqualTo(eventCount);
            assertThat(jdbc.queryForObject("SELECT card_funds FROM demo_simulation_account WHERE account_id=?",Long.class,account)).isEqualTo(7000L);
            var beforeLive=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var start=new java.util.concurrent.CountDownLatch(1);
                var left=pool.submit(() -> { start.await(); return tx.execute(s -> cash.applyCurrent(DATASET,"concurrent-grant",account,DemoSimulationCashService.Kind.GRANT,500)); });
                var right=pool.submit(() -> { start.await(); return tx.execute(s -> cash.applyCurrent(DATASET,"concurrent-grant",account,DemoSimulationCashService.Kind.GRANT,500)); });
                start.countDown();
                assertThat(left.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(7500L);
                assertThat(right.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(7500L);
            } catch (Exception failure) { throw new AssertionError(failure); }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_simulation_cash_event WHERE event_id='concurrent-grant'",Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT occurred_at FROM demo_simulation_cash_event WHERE event_id='concurrent-grant'",Timestamp.class).toInstant()).isAfterOrEqualTo(beforeLive);
            assertThatThrownBy(() -> tx.execute(s -> cash.applyCurrent(DATASET,"concurrent-grant",accounts.stream().filter(id -> !id.equals(account)).findFirst().orElseThrow(),
                DemoSimulationCashService.Kind.GRANT,500))).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT card_funds FROM demo_simulation_account WHERE account_id=?",Long.class,account)).isEqualTo(7500L);
            env.setProperty("CRABIT_DEMO_TOKEN_GRADE_4","simulation-secret-grade-3");
            assertThatThrownBy(() -> new DemoRepresentativeRegistry(jdbc,env)).isInstanceOf(IllegalStateException.class);

        }
    }
}
