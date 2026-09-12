package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import com.crabit.backend.balance.*;
import com.crabit.backend.demo.DemoSimulationCashService;
import com.crabit.backend.wish.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SimulationDomainRuntimeIT {
    private static final String DATASET="sha256:"+"b".repeat(64);
    private static final Instant START=SimulationCashOracle.START;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realServicesKeepHistoryClockIdempotencyAndPreDepositCommitBoundaries(boolean owner) {
        UUID academy=UUID.randomUUID(), student=owner ? com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID : UUID.randomUUID(),
            account=owner ? com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID : UUID.randomUUID();
        try(var runtime=new SimulationDomainRuntime()) {
            runtime.executeAt(START,s->{
                assertThat(s.context().getBeansOfType(Clock.class)).hasSize(1);
                assertThat(s.context().getBeansOfType(DemoHttpCardBalanceProvider.class)).isEmpty();
                assertThatThrownBy(()->runtime.close()).hasMessage("SIMULATION_STEP_ACTIVE");
                assertThatThrownBy(()->runtime.executeAt(START,nested->null)).hasMessage("SIMULATION_NESTED_STEP");
                assertThat(s.context().containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor")).isFalse();
                var j=s.jdbc();
                j.update("INSERT INTO academy(id,name) VALUES (?,'replay')",academy);
                j.update("INSERT INTO student(id,nickname,age,age_provenance) VALUES (?,'synthetic',9,'PROVIDED')",student);
                j.update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,?)",UUID.randomUUID(),student,academy,Timestamp.from(START));
                j.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",account,student,academy,Timestamp.from(START));
                j.update("INSERT INTO demo_simulation_dataset(dataset_id,manifest_digest,state,starts_at,ends_at) VALUES (?,?,'BUILDING',?,?)",DATASET,DATASET,Timestamp.from(START),Timestamp.from(SimulationCashOracle.END));
                j.update("INSERT INTO demo_simulation_account(account_id,dataset_id,logical_student_id,logical_account_id,grade,is_owner) VALUES (?,?,'student-1','account-1',3,?)",account,DATASET,owner);
                return null;
            });
            Instant grantAt=START.plusSeconds(60);
            runtime.executeAt(grantAt,s->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(s.service(DemoSimulationCashService.class).apply(DATASET,"grant",account,DemoSimulationCashService.Kind.GRANT,10000,s.clock().instant())).isEqualTo(10000);
                assertThat(s.service(CardBalanceSyncService.class).refresh(account,BalanceLookupMethod.USER_REQUESTED)).isInstanceOf(CardBalanceSyncResult.Success.class);
                assertThat(s.jdbc().queryForObject("SELECT observed_at FROM balance_observation WHERE account_id=?",Timestamp.class,account).toInstant()).isEqualTo(grantAt);
                assertThat(s.jdbc().queryForObject("SELECT source_kind FROM balance_observation WHERE account_id=?",String.class,account)).isEqualTo("SIMULATION");
                return null;
            });
            Instant createdAt=START.plusSeconds(120);
            var created=runtime.executeAt(createdAt,s->s.service(WishLifecycleService.class).create(student,academy,account,"create","test goal",20000,(LocalDate)null));
            assertThat(created.wish().createdAt()).isEqualTo(createdAt);
            var duplicate=runtime.executeAt(createdAt.plusSeconds(1),s->s.service(WishLifecycleService.class).create(student,academy,account,"create","test goal",20000,(LocalDate)null));
            assertThat(duplicate.replayed()).isTrue(); assertThat(duplicate.wish().id()).isEqualTo(created.wish().id());
            Instant allocatedAt=START.plusSeconds(180);
            var allocation=runtime.executeAt(allocatedAt,s->s.service(WishFundMovementService.class).deposit(student,academy,account,created.wish().id(),"deposit",6000,created.wish().version()));
            assertThat(allocation.wish().amount()).isEqualTo(6000);
            runtime.executeAt(allocatedAt,s->{
                assertThat(s.jdbc().queryForObject("SELECT card_funds FROM demo_simulation_account WHERE account_id=?",Long.class,account)).isEqualTo(10000);
                assertThat(s.jdbc().queryForObject("SELECT max(applied_at) FROM historical_balance_checkpoint WHERE account_id=?",Timestamp.class,account).toInstant()).isEqualTo(allocatedAt);
                assertThat(s.jdbc().queryForObject("SELECT max(valid_from) FROM feed_source_history WHERE source_kind='wish' AND source_id=?",Timestamp.class,created.wish().id()).toInstant()).isEqualTo(allocatedAt);
                return null;
            });
            // Insufficient unallocated funds fails AFTER a fresh PRE_DEPOSIT observation commits.
            Instant rejectedAt=START.plusSeconds(240);
            assertThatThrownBy(()->runtime.executeAt(rejectedAt,s->s.service(WishFundMovementService.class).deposit(student,academy,account,created.wish().id(),"too-much",5000,allocation.wish().version())))
                .isInstanceOf(WishLifecycleException.class);
            runtime.executeAt(rejectedAt,s->{
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM balance_observation WHERE account_id=? AND observed_at=? AND lookup_method='PRE_DEPOSIT'",Long.class,account,Timestamp.from(rejectedAt))).isEqualTo(1);
                assertThat(s.jdbc().queryForObject("SELECT wish_amount FROM wish WHERE id=?",Long.class,created.wish().id())).isEqualTo(6000);
                assertThat(s.jdbc().queryForObject("SELECT card_funds FROM demo_simulation_account WHERE account_id=?",Long.class,account)).isEqualTo(10000);
                return null;
            });
            Instant purchaseAt=START.plusSeconds(300);
            runtime.executeAt(purchaseAt,s->{
                assertThat(s.service(DemoSimulationCashService.class).apply(DATASET,"purchase",account,DemoSimulationCashService.Kind.PURCHASE,6000,s.clock().instant())).isEqualTo(4000);
                assertThat(s.jdbc().queryForObject("SELECT wish_amount FROM wish WHERE id=?",Long.class,created.wish().id())).isEqualTo(6000);
                s.service(CardBalanceSyncService.class).refresh(account,BalanceLookupMethod.USER_REQUESTED);
                assertThat(s.service(BalanceAdjustmentPolicy.class).isOpen(account)).isTrue();
                return null;
            });
            Instant adjustedAt=START.plusSeconds(360);
            var adjusted=runtime.executeAt(adjustedAt,s->s.service(WishFundMovementService.class).withdraw(student,academy,account,created.wish().id(),"adjust-withdraw",2500,allocation.wish().version()));
            assertThat(adjusted.wish().amount()).isEqualTo(3500);
            runtime.executeAt(adjustedAt,s->{assertThat(s.service(BalanceAdjustmentPolicy.class).isOpen(account)).isFalse(); return null;});
            var abandoned=runtime.executeAt(START.plusSeconds(420),s->s.service(WishLifecycleService.class).abandon(student,academy,account,created.wish().id(),"abandon",adjusted.wish().version()));
            assertThat(abandoned.wish().abandonmentAmount()).isEqualTo(3500);
            assertThat(abandoned.wish().amount()).isZero();
            runtime.executeAt(START.plusSeconds(420),s->{
                assertThat(s.jdbc().queryForObject("SELECT card_funds FROM demo_simulation_account WHERE account_id=?",Long.class,account)).isEqualTo(4000);
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM demo_simulation_cash_event WHERE account_id=?",Long.class,account)).isEqualTo(2);
                return null;
            });
            assertThatThrownBy(()->runtime.executeAt(START.plusSeconds(480),s->s.service(WishLifecycleService.class).create(UUID.randomUUID(),academy,account,"foreign","forbidden",1000,(LocalDate)null)))
                .isInstanceOf(WishLifecycleException.class);
            runtime.executeAt(START.plusSeconds(480),s->{assertThat(s.jdbc().queryForObject("SELECT count(*) FROM wish",Long.class)).isEqualTo(1);return null;});
            runtime.executeAt(START.plusSeconds(540),s->{
                s.service(CardBalanceObservationService.class).recordFailure(account,BalanceLookupMethod.USER_REQUESTED,"TEST_FAILURE",s.clock().instant());
                return null;
            });
            runtime.executeAt(START.plusSeconds(600),s->{
                s.service(CardBalanceSyncService.class).refresh(account,BalanceLookupMethod.USER_REQUESTED);
                var observationState=SimulationObservationState.capture(s.jdbc(),DATASET);
                var allocationState=SimulationAllocationState.capture(s.jdbc(),DATASET);
                var checked=SimulationObservationState.verify(observationState,allocationState,DATASET,
                    java.util.Map.of("ACCOUNT:account-1",account),s.clock().instant());
                assertThat(checked.succeeded()).isEqualTo(5);
                assertThat(checked.failed()).isEqualTo(1);
                assertThat(checked.depositLinks()).isEqualTo(1);
                assertThat(checked.changeLinks()).isEqualTo(2);
                assertThat(observationState.accounts().getFirst().lookupVersion()).isEqualTo(6);
                return null;
            });
        }
    }
}
