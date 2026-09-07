package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.wish.SharedCardQueryRepository;
import com.crabit.backend.wish.WishLifecycleException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Optional in unit runs; the parity script requires a real local Python receiver. */
@SpringBootTest(properties = {
    "spring.main.banner-mode=off", "logging.level.root=warn",
    "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///recap_parity",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test", "spring.datasource.password=test",
    "crabit.e2e.seed.enabled=false", "crabit.recap.generation.enabled=false"
})
@ActiveProfiles("e2e")
@EnabledIfEnvironmentVariable(named="CRABIT_RECAP_PARITY_CONFIG", matches=".+")
class RecapInputParityIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired RecapSnapshotService snapshots;
    @Autowired RecapGenerationCoordinator coordinator;
    @Autowired RecapGenerationRepository generations;
    @Autowired CardBalanceAccountRepository accounts;
    @Autowired SharedCardQueryRepository cards;
    @Autowired PlatformTransactionManager transactions;

    @Test void actualSnapshotPythonHttpCoordinatorPersistenceAndOwnerRetrieval() throws Exception {
        var config=json.readTree(Files.readString(Path.of(System.getenv("CRABIT_RECAP_PARITY_CONFIG"))));
        String base=config.get("url").asText();
        assertThat(java.net.URI.create(base).getHost()).isIn("127.0.0.1","localhost","::1");
        var client=new RecapPythonClient(new RecapServiceSettings(base.replaceAll("/$", "")+
                "/internal/v1/recap-generations",config.get("token").asText()),json);
        UUID academy=UUID.randomUUID(), student=UUID.randomUUID(), account=UUID.randomUUID();
        jdbc.update("insert into academy(id,name) values (?,?)",academy,"Actual Python parity");
        jdbc.update("insert into student(id,nickname,age,age_provenance) values (?,'Parity owner',12,'PROVIDED')",student);
        Instant opened=Instant.parse("2026-01-01T00:00:00Z");
        jdbc.update("insert into academy_membership(id,student_id,academy_id,joined_at) values (?,?,?,?)",UUID.randomUUID(),student,academy,Timestamp.from(opened));
        jdbc.update("insert into card_balance_account(id,student_id,academy_id,opened_at) values (?,?,?,?)",account,student,academy,Timestamp.from(opened));
        for(int day : new int[]{3,15,25}) deposit(account,academy,day);
        var now=Instant.parse("2026-09-08T00:00:00Z");
        var ownerQuery=new RecapQueryService(accounts,generations,cards,json,Clock.fixed(now,ZoneOffset.UTC));
        var output=Path.of("build/recap-input-parity"); Files.createDirectories(output);
        for(var kind : RecapKind.values()) {
            var period=kind==RecapKind.WEEKLY ? new RecapPeriods.Period(LocalDate.parse("2026-08-24"),LocalDate.parse("2026-08-31"))
                    : new RecapPeriods.Period(LocalDate.parse("2026-08-01"),LocalDate.parse("2026-09-01"));
            var reserved=coordinator.reserveScheduled(account,kind,period,now);
            assertThat(reserved.stage()).isEqualTo(RecapGenerationStage.PREPARATION);
            assertThat(reserved.requestJson()).isNull();
            assertThat(coordinator.reserveScheduled(account,kind,period,now).id()).isEqualTo(reserved.id());
            assertThat(coordinator.claim(now)).isEmpty();
            var preparation=coordinator.claimPreparation(now).orElseThrow();
            assertThat(preparation.id()).isEqualTo(reserved.id());
            var snapshot=snapshots.build(preparation.id(),preparation.accountId(),preparation.kind(),preparation.period());
            if(kind==RecapKind.MONTHLY) assertThat(snapshot.effectiveDepositCount()).isEqualTo(3);
            coordinator.prepared(preparation,snapshot,now);
            var frozen=generations.findById(reserved.id()).orElseThrow();
            assertThat(frozen.stage()).isEqualTo(RecapGenerationStage.GENERATION);
            assertThat(frozen.requestJson()).isEqualTo(snapshot.requestJson());
            assertThat(frozen.inputDigest()).isEqualTo(snapshot.inputDigest());
            var claim=coordinator.claim(now).orElseThrow();
            assertThat(claim.id()).isEqualTo(snapshot.generationId());
            assertThat(claim.requestJson()).isEqualTo(snapshot.requestJson());
            var result=client.generate(claim);
            coordinator.succeed(claim,result.viewJson(),result.internalMetricsJson(),now);
            var stored=generations.findById(claim.id()).orElseThrow();
            assertThat(stored.state()).isEqualTo(RecapGenerationState.SUCCEEDED);
            assertThat(stored.currentVersion()).isTrue();
            assertThat(stored.requestJson()).isEqualTo(snapshot.requestJson());
            assertThat(stored.viewJson()).isEqualTo(result.viewJson());
            assertThat(stored.internalMetricsJson()).isEqualTo(result.internalMetricsJson());
            var storedBeforeQueries=jdbc.queryForMap("select * from recap_generation where id=?",claim.id());
            Long generationCountBeforeQueries=jdbc.queryForObject("select count(*) from recap_generation where account_id=?",Long.class,account);
            var response=ownerResponse(ownerQuery,kind,student,academy,account);
            assertThat(response.status()).isEqualTo("SUCCEEDED");
            assertThat(response.result()).isNotNull();
            assertThat(response.kind()).isEqualTo(kind.name());
            assertThat(response.period()).isEqualTo(new RecapQueryService.PeriodView(period.start(),period.endExclusive(),"Asia/Seoul"));
            assertThat(response.generationVersion()).isEqualTo(stored.generationVersion());
            assertThat(response.generatedAt()).isEqualTo(stored.generatedAt());
            String publicJson=json.writeValueAsString(response);
            var publicResult=json.readTree(publicJson).get("result");
            if(kind==RecapKind.WEEKLY) {
                assertThat(publicResult.get("page1LastWeekPerformance").get("achievement").get("netSavings").asLong()).isEqualTo(1000);
                assertThat(publicResult.get("page1LastWeekPerformance").get("achievement").get("saveCount").asInt()).isEqualTo(1);
                assertThat(publicResult.get("page3AcademySuccessStories").get("messageSummary").asText())
                        .isEqualTo(json.readTree(stored.viewJson()).get("page3_academy_success_stories").get("message_summary").asText());
            } else {
                assertThat(publicResult.get("objectivePerformance").get("totalSavings").asLong()).isEqualTo(3000);
                assertThat(publicResult.get("groupComparison").get("habitPercentile").isNull()).isTrue();
                assertThat(publicResult.get("groupComparison").get("achievementPercentile").isNull()).isTrue();
            }
            assertThat(publicJson).doesNotContain("inputDigest","internalMetrics","requestJson","authorPreviousMonth","rootEventId");
            for(int read=0;read<2;read++) {
                var freshOwnerQuery=new RecapQueryService(accounts,generations,cards,json,Clock.fixed(now,ZoneOffset.UTC));
                var repeated=ownerResponse(freshOwnerQuery,kind,student,academy,account);
                assertThat(json.readTree(json.writeValueAsString(repeated))).isEqualTo(json.readTree(publicJson));
            }
            assertThatThrownBy(() -> ownerQuery.monthly(UUID.randomUUID(),academy,account,"2026-08")).isInstanceOf(WishLifecycleException.class);
            String prefix=kind.name().toLowerCase();
            Files.writeString(output.resolve(prefix+"-persisted-request.json"),snapshot.requestJson());
            Files.writeString(output.resolve(prefix+"-persisted-view.json"),stored.viewJson());
            Files.writeString(output.resolve(prefix+"-owner-response.json"),publicJson);
            // A duplicate scheduler pass must also preserve the successful frozen generation.
            var duplicate=coordinator.reserveScheduled(account,kind,period,now.plusSeconds(60));
            assertThat(duplicate.id()).isEqualTo(stored.id());
            assertThat(duplicate.generationVersion()).isEqualTo(stored.generationVersion());
            // Repeated retrieval and scheduling must neither rewrite persisted fields nor create a version.
            assertThat(jdbc.queryForMap("select * from recap_generation where id=?",claim.id())).isEqualTo(storedBeforeQueries);
            assertThat(jdbc.queryForObject("select count(*) from recap_generation where account_id=?",Long.class,account))
                    .isEqualTo(generationCountBeforeQueries);
        }
    }

    private RecapQueryService.Response ownerResponse(RecapQueryService query,RecapKind kind,
            UUID student,UUID academy,UUID account) {
        return kind==RecapKind.WEEKLY ? query.weekly(student,academy,account,"2026-08-24")
                : query.monthly(student,academy,account,"2026-08");
    }

    private void deposit(UUID account,UUID academy,int day) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            UUID wish=UUID.randomUUID(), observation=UUID.randomUUID(), event=UUID.randomUUID();
            var at=Timestamp.from(LocalDate.of(2026,8,day).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant());
            jdbc.update("insert into wish(id,account_id,academy_id,purpose,target_amount,wish_amount,state,visibility,created_at) values (?,?,?,'Private account activity',10000,1000,'IN_PROGRESS','PRIVATE',?)",wish,account,academy,Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
            UUID previous=jdbc.query("select o.id from balance_observation o where account_id=? and status='SUCCEEDED' and not exists (select 1 from balance_observation n where n.previous_successful_observation_id=o.id)",(rs,n)->rs.getObject(1,UUID.class),account).stream().findFirst().orElse(null);
            jdbc.update("insert into balance_observation(id,account_id,status,lookup_method,actual_card_balance,first_successful,previous_successful_observation_id,previous_successful_balance,observed_at) values (?,?,'SUCCEEDED','PRE_DEPOSIT',0,?,?,0,?)",observation,account,previous==null ? true : null,previous,at);
            jdbc.update("insert into ledger_event(id,account_id,event_type,account_delta,occurred_at,deposit_balance_observation_id,deposit_observation_status,deposit_observation_lookup_method) values (?,?,'WISH_DEPOSIT',-1000,?,?,'SUCCEEDED','PRE_DEPOSIT')",event,account,at,observation);
            jdbc.update("insert into ledger_wish_effect(id,event_id,account_id,wish_id,wish_purpose_snapshot,wish_delta) values (?,?,?,?,'Private account activity',1000)",UUID.randomUUID(),event,account,wish);
        });
    }
}
