package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import com.crabit.backend.demo.DemoSimulationCashService;
import com.crabit.backend.wish.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimulationRecapPreparationIT {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realSnapshotFreezesAtWeeklyBoundaryAndMonthlyEligibilityDoesNotInventPythonResult(boolean eligible) {
        UUID academy=UUID.randomUUID(),student=UUID.randomUUID(),account=UUID.randomUUID(),week=UUID.randomUUID(),month=UUID.randomUUID();
        String dataset="sha256:"+"a".repeat(64);Instant start=SimulationCashOracle.START;
        try(var runtime=new SimulationDomainRuntime()) {
            runtime.executeAt(start,s->{
                var j=s.jdbc();
                j.update("INSERT INTO academy(id,name) VALUES (?,'recap simulation')",academy);
                j.update("INSERT INTO student(id,nickname,age,age_provenance) VALUES (?,'synthetic',9,'PROVIDED')",student);
                j.update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,?)",UUID.randomUUID(),student,academy,Timestamp.from(start));
                j.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",account,student,academy,Timestamp.from(start));
                j.update("INSERT INTO demo_simulation_dataset(dataset_id,manifest_digest,state,starts_at,ends_at) VALUES (?,?,'BUILDING',?,?)",dataset,dataset,Timestamp.from(start),Timestamp.from(SimulationCashOracle.END));
                j.update("INSERT INTO demo_simulation_account(account_id,dataset_id,logical_student_id,logical_account_id,grade,is_owner) VALUES (?,?,'s','a',3,false)",account,dataset);
                s.service(DemoSimulationCashService.class).apply(dataset,"grant",account,DemoSimulationCashService.Kind.GRANT,20000,start);
                return null;
            });
            var created=runtime.executeAt(start.plusSeconds(1),s->s.service(WishLifecycleService.class).create(student,academy,account,"create","Goal",30000,(LocalDate)null));
            Instant boundary=Instant.parse("2026-06-07T15:00:00Z");
            var deposit=runtime.executeAt(boundary.minusNanos(1000),s->s.service(WishFundMovementService.class).deposit(student,academy,account,created.wish().id(),"deposit",1000,created.wish().version()));
            var frozen=runtime.executeAt(boundary,s->SimulationRecapPreparation.prepare(s,dataset,student,account,week,"CLOSE_WEEK",LocalDate.parse("2026-06-01"),LocalDate.parse("2026-06-08")));
            assertThat(frozen.state()).isEqualTo("PENDING");assertThat(frozen.reused()).isFalse();
            var request=SimulationBundleReader.parse(frozen.requestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(request.get("input").get("effective_transactions")).hasSize(1);
            assertThat(request.get("snapshot_at").asString()).isEqualTo(boundary.toString());
            runtime.executeAt(boundary,s->s.service(WishFundMovementService.class).deposit(student,academy,account,created.wish().id(),"next-week",2000,deposit.wish().version()));
            var again=runtime.executeAt(boundary.plusSeconds(1),s->SimulationRecapPreparation.prepare(s,dataset,student,account,week,"CLOSE_WEEK",LocalDate.parse("2026-06-01"),LocalDate.parse("2026-06-08")));
            assertThat(again.requestJson()).isEqualTo(frozen.requestJson());assertThat(again.reused()).isTrue();
            var later=runtime.executeAt(boundary.plusSeconds(1),s->SimulationRecapPreparation.prepare(s,dataset,student,account,UUID.randomUUID(),"CLOSE_WEEK",LocalDate.parse("2026-06-01"),LocalDate.parse("2026-06-08")));
            // A separately prepared past-period request still excludes the exact end boundary deposit.
            assertThat(SimulationBundleReader.parse(later.requestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("input").get("effective_transactions")).hasSize(1);
            runtime.executeAt(boundary.plusSeconds(1),s->{
                long before=s.jdbc().queryForObject("SELECT count(*) FROM recap_generation",Long.class);
                assertThatThrownBy(()->SimulationRecapPreparation.prepare(s,dataset,UUID.randomUUID(),account,UUID.randomUUID(),"CLOSE_WEEK",LocalDate.parse("2026-06-01"),LocalDate.parse("2026-06-08"))).hasMessage("RECAP_ACTIVE_SIMULATION_OWNER");
                assertThatThrownBy(()->SimulationRecapPreparation.prepare(s,dataset,student,account,week,"CLOSE_WEEK",LocalDate.parse("2026-06-02"),LocalDate.parse("2026-06-09"))).isInstanceOf(RuntimeException.class);
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM recap_generation",Long.class)).isEqualTo(before);
                return null;
            });
            if(eligible) runtime.executeAt(Instant.parse("2026-06-20T00:00:00Z"),s->{
                long version=s.jdbc().queryForObject("SELECT version FROM wish WHERE id=?",Long.class,created.wish().id());
                return s.service(WishFundMovementService.class).deposit(student,academy,account,created.wish().id(),"third",1000,version);
            });
            runtime.executeAt(Instant.parse("2026-06-30T15:00:00Z"),s->{
                var result=SimulationRecapPreparation.prepare(s,dataset,student,account,month,"CLOSE_MONTH",LocalDate.parse("2026-06-01"),LocalDate.parse("2026-07-01"));
                assertThat(result.state()).isEqualTo(eligible?"PENDING":"NOT_ELIGIBLE");
                var row=s.jdbc().queryForMap("SELECT current_version,view_json,internal_metrics_json,request_json FROM recap_generation WHERE id=?",month);
                assertThat(row.get("current_version")).isEqualTo(!eligible);assertThat(row.get("view_json")).isNull();
                assertThat(row.get("internal_metrics_json")).isNull();assertThat(row.get("request_json")).isEqualTo(result.requestJson());
                assertThatThrownBy(()->SimulationRecapPreparation.prepare(s,dataset,student,account,week,"CLOSE_MONTH",LocalDate.parse("2026-06-01"),LocalDate.parse("2026-07-01"))).hasMessage("RECAP_GENERATION_CONFLICT");
                return null;
            });
        }
    }
}
