package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import com.crabit.backend.behavior.*;
import com.crabit.backend.demo.DemoSimulationCashService;
import com.crabit.backend.simulation.*;
import com.crabit.backend.wish.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Real services populate the month; raw-row oracle must reject each forged wire metric. */
class SimulationFeedMonthlyMetricsIT {
    @TempDir Path temp;
    static final Instant START=SimulationCashOracle.START;
    long version(SimulationDomainRuntime.Services s,UUID wish) {
        return s.jdbc().queryForObject("SELECT version FROM wish WHERE id=?",Long.class,wish);
    }
    @Test void actualMonthlyActivityMatchesOracleAndPythonWhileEachForgedValueFails() throws Exception {
        var fixture=new SimulationFeedExecutionIT();fixture.temp=temp;
        try(var python=fixture.python();
            var session=new SimulationFeedSession(python.endpoint(),SimulationFeedExecutionIT.TOKEN,temp);
            var runtime=new SimulationDomainRuntime(session)) {
            var viewer=fixture.seed(runtime);
            var author=new SimulationFeedExecutionIT.Owner(viewer.academy(),UUID.randomUUID(),UUID.randomUUID());
            runtime.executeAt(START,s->{fixture.member(s,author,"author");return null;});
            UUID[] wishes=runtime.executeAt(START.plusSeconds(1),s->{
                s.service(WishLifecycleService.class).create(viewer.student(),viewer.academy(),viewer.account(),"viewer-wish","책 모으기",20000,(LocalDate)null);
                s.service(DemoSimulationCashService.class).apply(SimulationFeedExecutionIT.DATASET,"grant",author.account(),DemoSimulationCashService.Kind.GRANT,20000,s.clock().instant());
                var life=s.service(WishLifecycleService.class);
                var first=life.create(author.student(),author.academy(),author.account(),"first","책 모으기",20000,(LocalDate)null).wish();
                var second=life.create(author.student(),author.academy(),author.account(),"second","노트북",20000,(LocalDate)null).wish();
                life.patch(author.student(),author.academy(),author.account(),first.id(),first.version(),new WishPatch(null,null,false,null,WishVisibility.ACADEMY));
                return new UUID[]{first.id(),second.id()};
            });
            int[] days={1,3,6};
            for(int i=0;i<days.length;i++) {
                int n=i;
                runtime.executeAt(START.plus(Duration.ofDays(days[i]-1)).plusSeconds(2),s->{
                    s.service(WishFundMovementService.class).deposit(author.student(),author.academy(),author.account(),wishes[0],"deposit-"+n,(n+1)*1000,version(s,wishes[0]));return null;
                });
            }
            runtime.executeAt(START.plus(Duration.ofDays(19)),s->{
                var funds=s.service(WishFundMovementService.class);
                funds.withdraw(author.student(),author.academy(),author.account(),wishes[0],"withdraw",500,version(s,wishes[0]));
                funds.transfer(author.student(),author.academy(),author.account(),"transfer",wishes[0],wishes[1],100,version(s,wishes[0]),version(s,wishes[1]));
                return null;
            });
            runtime.executeAt(START.plus(Duration.ofDays(20)),s->{
                s.service(WishLifecycleService.class).abandon(author.student(),author.academy(),author.account(),wishes[1],"abandon",version(s,wishes[1]));
                s.service(BehaviorService.class).collect(author.student(),author.academy(),new BehaviorModels.Event(UUID.randomUUID(),"PROFILE_VISIT",s.clock().instant(),viewer.student(),null,null,null,null,null));
                s.service(BehaviorService.class).collect(viewer.student(),viewer.academy(),new BehaviorModels.Event(UUID.randomUUID(),"PROFILE_VISIT",s.clock().instant(),author.student(),null,null,null,null,null));return null;
            });
            runtime.executeAt(Instant.parse("2026-07-01T00:00:00Z"),s->{
                try {
                    session.begin(1);BehaviorModels.FeedResult result=null;
                    try {result=s.service(BehaviorService.class).createResult(viewer.student(),viewer.academy(),null,20);}
                    finally {session.finish(result);}
                    assertThat(result.sortSource()).isEqualTo("RECOMMENDATION");
                }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}return null;
            });
            Path output=temp.resolve("event-1");var json=SimulationFeedExecutionIT.JSON;
            JsonNode request=json.readTree(Files.readAllBytes(output.resolve("request.json")));
            JsonNode source=json.readTree(Files.readAllBytes(output.resolve("input-source.json")));
            JsonNode checked=json.readTree(Files.readAllBytes(output.resolve("input-verification.json")));
            assertThat(checked.get("completeMetricMonthsVerified").asInt()).isEqualTo(2);
            assertThat(checked.get("monthlyMetricValuesVerified").asBoolean()).isTrue();
            assertThat(checked.get("visitSignalsVerified").asBoolean()).isTrue();
            assertThat(checked.get("categoryAndSimilaritiesVerified").asBoolean()).isTrue();
            var features=request.get("candidates").get(0);
            assertThat(features.get("category_id").asString()).isEqualTo("도서");
            assertThat(features.get("basic_similarity").asDouble()).isEqualTo(1);
            assertThat(features.get("title_similarity").asDouble()).isEqualTo(1);
            for(String field:List.of("category_id","basic_similarity","title_similarity")) {
                var forged=request.deepCopy();var c=(ObjectNode)forged.get("candidates").get(0);
                if(field.equals("category_id"))c.put(field,"전자기기");else c.put(field,0.125);
                assertThatThrownBy(()->SimulationFeedInputVerifier.verify(forged,source)).hasMessageStartingWith("FEED_FEATURE_");
            }
            assertThat(request.get("candidates").get(0).get("visited_author_before").asBoolean()).isTrue();
            assertThat(request.get("candidates").get(0).get("visited_category_before").asBoolean()).isTrue();
            var metrics=request.get("candidates").get(0).get("author_previous_month").get("values");
            assertThat(metrics.get("deposit_count").asLong()).isEqualTo(3);
            assertThat(metrics.get("total_savings").asLong()).isEqualTo(5500);
            assertThat(metrics.get("avg_amount").asDouble()).isEqualTo(5500.0/3);
            assertThat(metrics.get("regularity_std").asDouble()).isEqualTo(0.5);
            assertThat(metrics.get("pace_bias").asDouble()).isEqualTo(-6500.0/5500);
            for(String field:List.of("transfer_count","abandon_count","visit_count"))assertThat(metrics.get(field).asLong()).as(field).isEqualTo(1);
            for(String field:metrics.propertyNames()) {
                var forged=request.deepCopy();
                ((ObjectNode)forged.get("candidates").get(0).get("author_previous_month").get("values")).put(field,999);
                assertThatThrownBy(()->SimulationFeedInputVerifier.verify(forged,source)).hasMessage("FEED_METRIC_VALUE:"+field);
            }
            Path durable=Path.of("build/simulation-feed-monthly-metrics");Files.createDirectories(durable);
            try(var files=Files.list(output)){for(var file:files.toList())Files.copy(file,durable.resolve(file.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
        }
    }
}
