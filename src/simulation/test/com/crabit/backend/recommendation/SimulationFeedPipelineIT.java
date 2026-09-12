package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import com.crabit.backend.simulation.*;
import com.crabit.backend.behavior.*;
import com.crabit.backend.wish.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationFeedPipelineIT {
    @TempDir Path temp;
    static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    SimulationFeedExecutionIT fixture(){var f=new SimulationFeedExecutionIT();f.temp=temp;return f;}
    BehaviorModels.FeedResult page(SimulationDomainRuntime runtime,SimulationFeedSession session,
            SimulationFeedExecutionIT.Owner viewer,int sequence,String cursor) {
        return runtime.executeAt(SimulationCashOracle.START.plusSeconds(10+sequence),s->{
            try {
                session.begin(sequence);BehaviorModels.FeedResult result=null;
                try {result=s.service(BehaviorService.class).createResult(viewer.student(),viewer.academy(),cursor,1);return result;}
                finally {session.finish(result);}
            }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        });
    }
    @Test void actualPythonOrderFlowsThroughStoredPageContinuationAndUnmatchedClick() throws Exception {
        var f=fixture();
        try(var python=f.python();var session=new SimulationFeedSession(python.endpoint(),SimulationFeedExecutionIT.TOKEN,temp);
            var runtime=new SimulationDomainRuntime(session)) {
            var viewer=f.seed(runtime);UUID firstCard=f.author(runtime,viewer);
            UUID secondCard=runtime.executeAt(SimulationCashOracle.START.plusSeconds(2),s->{
                UUID account=s.jdbc().queryForObject("SELECT account_id FROM demo_simulation_account WHERE logical_student_id='author'",UUID.class);
                UUID author=s.jdbc().queryForObject("SELECT student_id FROM card_balance_account WHERE id=?",UUID.class,account);
                var life=s.service(WishLifecycleService.class);
                var w=life.create(author,viewer.academy(),account,"second","노트북",10000,(LocalDate)null).wish();
                life.patch(author,viewer.academy(),account,w.id(),0,new WishPatch(null,null,false,null,WishVisibility.ACADEMY));
                return s.jdbc().queryForObject("SELECT id FROM shared_card WHERE wish_id=?",UUID.class,w.id());
            });
            var first=page(runtime,session,viewer,1,null);
            var request=JSON.readTree(Files.readAllBytes(temp.resolve("event-1/request.json")));
            var response=JSON.readTree(Files.readAllBytes(temp.resolve("event-1/response.json")));
            var source=JSON.readTree(Files.readAllBytes(temp.resolve("event-1/input-source.json")));
            assertThat(SimulationFeedInputVerifier.verify(request,source)).containsEntry("candidateCount",2);
            assertThat(JSON.readTree(Files.readAllBytes(temp.resolve("event-1/input-verification.json"))).get("candidateCompletenessVerified").asBoolean()).isTrue();
            var omitted=request.deepCopy();((tools.jackson.databind.node.ArrayNode)omitted.get("candidates")).remove(0);
            assertThatThrownBy(()->SimulationFeedInputVerifier.verify(omitted,source)).hasMessage("FEED_INPUT_CANDIDATE_COUNT");
            List<UUID> ranking=new ArrayList<>();for(var id:response.get("ordered_card_ids"))ranking.add(UUID.fromString(id.asString()));
            assertThat(ranking).containsExactlyInAnyOrder(firstCard,secondCard);
            assertThat(first.sortSource()).isEqualTo("RECOMMENDATION");
            assertThat(first.recommendationResultId()).isEqualTo(request.get("request_id").asString());
            assertThat(first.items().getFirst().sharedCardId()).isEqualTo(ranking.getFirst());
            assertThat(first.nextCursor()).isNotNull();
            var second=page(runtime,session,viewer,2,first.nextCursor());
            assertThat(second.items().getFirst().sharedCardId()).isEqualTo(ranking.get(1));
            assertThat(second.sortSource()).isEqualTo("RECOMMENDATION");
            assertThat(second.recommendationResultId()).isEqualTo(first.recommendationResultId());
            assertThat(temp.resolve("event-2/request.json")).doesNotExist();
            assertThat(JSON.readTree(Files.readAllBytes(temp.resolve("event-2/page.json"))).get("pythonInvoked").booleanValue()).isFalse();
            var repeated=page(runtime,session,viewer,3,first.nextCursor());
            assertThat(repeated.items()).isEqualTo(second.items());
            assertThat(temp.resolve("event-3/request.json")).doesNotExist();
            runtime.executeAt(SimulationCashOracle.START.plusSeconds(15),s->{
                var context=s.jdbc().queryForMap("SELECT * FROM feed_page_context WHERE id=?",UUID.fromString(request.get("context_id").asString()));
                assertThat(context.get("ranking_outcome")).isEqualTo("RECOMMENDATION");
                assertThat(JSON.readTree(context.get("ranked_card_ids").toString())).isEqualTo(response.get("ordered_card_ids"));
                assertThat(context.get("recommendation_request_id").toString()).isEqualTo(first.recommendationResultId());
                UUID click=UUID.randomUUID();
                s.service(BehaviorService.class).collect(viewer.student(),viewer.academy(),new BehaviorModels.Event(click,"FEED_CLICK",s.clock().instant(),null,
                    first.resultContextId(),first.items().getFirst().sharedCardId(),0,UUID.randomUUID(),"AUTHOR_PROFILE"));
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM behavior_event WHERE event_type='FEED_CLICK'",Long.class)).isEqualTo(1);
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM behavior_event WHERE event_type='FEED_EXPOSURE'",Long.class)).isZero();return null;
            });
            runtime.executeAt(SimulationCashOracle.START.plusSeconds(16),s->{
                UUID card=first.items().getFirst().sharedCardId();
                UUID wish=s.jdbc().queryForObject("SELECT wish_id FROM shared_card WHERE id=?",UUID.class,card);
                UUID account=s.jdbc().queryForObject("SELECT account_id FROM wish WHERE id=?",UUID.class,wish);
                UUID author=s.jdbc().queryForObject("SELECT student_id FROM card_balance_account WHERE id=?",UUID.class,account);
                long version=s.jdbc().queryForObject("SELECT version FROM wish WHERE id=?",Long.class,wish);
                s.service(WishLifecycleService.class).patch(author,viewer.academy(),account,wish,version,new WishPatch(null,null,false,null,WishVisibility.PRIVATE));
                assertThatThrownBy(()->s.service(BehaviorService.class).collect(viewer.student(),viewer.academy(),
                    new BehaviorModels.Event(UUID.randomUUID(),"FEED_CLICK",s.clock().instant(),null,first.resultContextId(),card,0,UUID.randomUUID(),"AUTHOR_PROFILE")))
                    .isInstanceOfSatisfying(BehaviorException.class,e->assertThat(e.code()).isEqualTo("SHARED_CARD_NOT_FOUND"));
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM behavior_event WHERE event_type='FEED_CLICK'",Long.class)).isEqualTo(1);
                return null;
            });
            assertThat(JSON.readTree(Files.readAllBytes(temp.resolve("event-1/response.json")))).isEqualTo(response);
            for(int n=1;n<=3;n++) {
                var dir=temp.resolve("event-"+n);
                assertThat(JSON.readTree(Files.readAllBytes(dir.resolve("page-verification.json"))).get("persistedPageVerified").asBoolean()).isTrue();
            }
            assertThat(JSON.readTree(Files.readAllBytes(temp.resolve("event-1/composition-verification.json"))).get("compositionGuaranteesVerified").asBoolean()).isTrue();
            assertThat(JSON.readTree(Files.readAllBytes(temp.resolve("event-1/ranking-verification.json"))).get("rankingAlgorithmVerified").asBoolean()).isTrue();
            var savedPage=JSON.readTree(Files.readAllBytes(temp.resolve("event-2/page.json"))).get("page");
            var savedSource=JSON.readTree(Files.readAllBytes(temp.resolve("event-2/page-source.json")));
            var tampered=savedSource.deepCopy();((ObjectNode)tampered.get("items").get(0)).put("card_id",UUID.randomUUID().toString());
            assertThatThrownBy(()->SimulationFeedPageVerifier.verify(savedPage,tampered)).hasMessage("FEED_PAGE_ITEM_ORDER");
            var missing=savedSource.deepCopy();((tools.jackson.databind.node.ArrayNode)missing.get("items")).removeAll();
            assertThatThrownBy(()->SimulationFeedPageVerifier.verify(savedPage,missing)).hasMessage("FEED_PAGE_COUNT");
            copyEvidence("success");
        }
    }
    @Test void actualUnauthorizedPythonUsesLatestAndRetainsFailureWithoutFalseRecommendation() throws Exception {
        var f=fixture();
        try(var python=f.python();var session=new SimulationFeedSession(python.endpoint(),"wrong-token",temp);
            var runtime=new SimulationDomainRuntime(session)) {
            var viewer=f.seed(runtime);var card=f.author(runtime,viewer);
            var result=page(runtime,session,viewer,1,null);
            assertThat(result.sortSource()).isEqualTo("LATEST");assertThat(result.recommendationResultId()).isNull();
            assertThat(result.items().getFirst().sharedCardId()).isEqualTo(card);
            assertThat(JSON.readTree(Files.readAllBytes(temp.resolve("event-1/http.json"))).get("status").asInt()).isEqualTo(401);
            runtime.executeAt(SimulationCashOracle.START.plusSeconds(20),s->{
                assertThat(s.jdbc().queryForObject("SELECT ranking_outcome FROM feed_page_context",String.class)).isEqualTo("LATEST");
                assertThat(s.jdbc().queryForObject("SELECT recommendation_request_id FROM feed_page_context",UUID.class)).isNull();return null;
            });copyEvidence("unauthorized");
        }
    }
    @Test void emptyCandidatesSkipHttpAndEvidenceCollisionPrecedesAnotherPageWrite() throws Exception {
        var f=fixture();
        try(var session=new SimulationFeedSession(java.net.URI.create("http://127.0.0.1:1/internal/v1/feed-rankings"),"local-test",temp);
            var runtime=new SimulationDomainRuntime(session)) {
            var viewer=f.seed(runtime);var result=page(runtime,session,viewer,1,null);
            assertThat(result.items()).isEmpty();assertThat(result.sortSource()).isEqualTo("LATEST");
            assertThat(temp.resolve("event-1/request.json")).doesNotExist();
            var evidence=JSON.readTree(Files.readAllBytes(temp.resolve("event-1/page.json")));
            assertThat(evidence.get("pythonInvoked").booleanValue()).isFalse();
            assertThat(evidence.get("responseCaptured").booleanValue()).isFalse();
            assertThat(JSON.readTree(Files.readAllBytes(temp.resolve("event-1/page-verification.json"))).get("itemCount").asInt()).isZero();
            assertThat(temp.resolve("event-1/composition-verification.json")).doesNotExist();
            assertThatThrownBy(()->session.begin(1)).isInstanceOf(FileAlreadyExistsException.class);
            runtime.executeAt(SimulationCashOracle.START.plusSeconds(12),s->{
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM feed_page_context",Long.class)).isEqualTo(1);return null;
            });
        }
    }
    ObjectNode event(int n,String kind,String actor,int seconds,Map<String,?> command) {
        var e=JSON.createObjectNode().put("eventId","e"+n).put("sequence",n).put("kind",kind).put("actorStudentId",actor)
            .put("occurredAt",SimulationCashOracle.START.plusSeconds(seconds).toString());
        e.putArray("causes");e.set("command",JSON.valueToTree(command));
        e.putObject("outcome").put("status","APPLIED").put("resultRef","raw/results/"+n+".json");
        var refs=e.putArray("artifactRefs").add("raw/results/"+n+".json");
        command.forEach((k,v)->{if(k.endsWith("Ref"))refs.add(v.toString());});return e;
    }
    @Test void dispatcherRunsRealRankingAndReplaysSameEventWithoutAnotherHttpCall() throws Exception {
        var schema=JSON.readTree(Files.readAllBytes(Path.of("api/demo-simulation-v1.schema.json")));
        var people=JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/bundle-contract-valid/students.json")));
        var f=fixture();
        try(var python=f.python();var session=new SimulationFeedSession(python.endpoint(),SimulationFeedExecutionIT.TOKEN,temp);
            var dispatcher=new SimulationCommandDispatcher(schema,SimulationFeedExecutionIT.DATASET,SimulationFeedExecutionIT.DATASET,people,session)) {
            for(int i=0;i<2;i++)dispatcher.execute(event(i+1,"JOIN","student-3-0"+i,0,Map.of("studentId","student-3-0"+i,"accountId","account-3-0"+i,"academyId","academy-1","grade",3)));
            var create=new HashMap<String,Object>();create.put("accountId","account-3-01");create.put("wishId","wish-1");create.put("idempotencyKey","create");
            create.put("purpose","책 모으기");create.put("targetAmount",5000);create.put("startDate",null);create.put("targetDate",null);create.put("photoId",null);
            dispatcher.execute(event(3,"CREATE","student-3-01",1,create));
            dispatcher.execute(event(4,"SHARE","student-3-01",2,Map.of("accountId","account-3-01","wishId","wish-1","expectedVersion",0,"visibility","ACADEMY")));
            var command=new HashMap<String,Object>();command.put("academyId","academy-1");command.put("limit",1);command.put("cursor",null);
            command.put("resultContextId","page-1");command.put("orderedCardIds",List.of("e4"));command.put("requestRef","raw/feed-request.json");command.put("responseRef","raw/feed-response.json");
            var input=event(5,"FEED_QUERY","student-3-00",3,command);
            var result=dispatcher.execute(input);byte[] original=Files.readAllBytes(temp.resolve("event-5/response.json"));
            assertThat(JSON.readTree(result.rawResult()).get("sortSource").asString()).isEqualTo("RECOMMENDATION");
            assertThat(dispatcher.execute(input).rawResult()).isEqualTo(result.rawResult());
            assertThat(Files.readAllBytes(temp.resolve("event-5/response.json"))).isEqualTo(original);
            var click=new HashMap<String,Object>();click.put("academyId","academy-1");click.put("resultContextId","page-1");click.put("cardId","e4");
            click.put("position",0);click.put("impressionId","impression-1");click.put("clickKind","AUTHOR_PROFILE");
            dispatcher.execute(event(6,"CLICK","student-3-00",4,click));
            copyEvidence("dispatcher");
        }
    }
    void copyEvidence(String name) throws Exception {
        Path target=Path.of("build/simulation-feed-pipeline",name);Files.createDirectories(target);
        try(var files=Files.walk(temp)){for(var path:files.filter(Files::isRegularFile).toList()) {
            if(path.getFileName().toString().equals("python-stderr.txt"))continue;
            Path to=target.resolve(temp.relativize(path));Files.createDirectories(to.getParent());Files.copy(path,to,StandardCopyOption.REPLACE_EXISTING);
        }}
    }
}
