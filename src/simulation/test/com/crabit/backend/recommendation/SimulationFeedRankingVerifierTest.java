package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.net.http.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SimulationFeedRankingVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final Instant NOW=Instant.parse("2026-03-10T03:00:00Z");
    @TempDir Path temp;
    ObjectNode month(int count) {
        var m=JSON.createObjectNode().put("month","2026-02").put("coverage","COMPLETE").put("metrics_version","core-metrics-v1");
        m.putObject("values").put("deposit_count",count).put("total_savings",count*1000).put("avg_amount",count==0?0:1000)
            .put("regularity_std",1.0).putNull("pace_bias").put("abandon_count",0).put("transfer_count",0).put("visit_count",0);return m;
    }
    ObjectNode request(int count) {
        var r=JSON.createObjectNode().put("schema_version",1).put("request_id",UUID.randomUUID().toString()).put("context_id",UUID.randomUUID().toString())
            .put("viewer_id",UUID.randomUUID().toString()).put("academy_id",UUID.randomUUID().toString()).put("recommendation_at",NOW.toString())
            .put("timezone","Asia/Seoul").put("feature_version","feed-features-v1").put("classifier_version","wish-category-v1@sha256:"+"a".repeat(64));
        r.set("viewer_previous_month",month(0));var a=r.putArray("candidates");
        for(int i=0;i<count;i++) {
            var c=a.addObject().put("card_id",new UUID(0,i+1).toString()).put("author_id",new UUID(1,i+1).toString())
                .put("state","IN_PROGRESS").put("created_at","2026-02-01T00:00:00Z").putNull("target_date").putNull("closed_at")
                .put("content_updated_at",NOW.toString()).put("category_id","도서").put("basic_similarity",0.0).put("title_similarity",0.0)
                .put("visited_author_before",false).put("visited_category_before",false);
            c.set("author_previous_month",month(0));
        }return r;
    }
    ObjectNode card(ObjectNode r,int i){return (ObjectNode)r.get("candidates").get(i);}
    List<String> ids(ObjectNode r,int... positions){return Arrays.stream(positions).mapToObj(i->card(r,i).get("card_id").asString()).toList();}
    ObjectNode response(ObjectNode r,List<String> ids) {
        var out=JSON.createObjectNode().put("schema_version",1).put("model_version","feed-rules-v1");
        out.set("request_id",r.get("request_id"));out.set("context_id",r.get("context_id"));out.set("ordered_card_ids",JSON.valueToTree(ids));return out;
    }
    @Test void exactTiesKeepInputOrderRatherThanUuidOrder() {
        var r=request(3);card(r,0).put("card_id",new UUID(0,99).toString());
        assertThat(SimulationFeedRankingVerifier.expectedOrder(r)).isEqualTo(ids(r,0,1,2));
    }
    @Test void categoryRedundancyCanOutweighHigherBaseScore() {
        var r=request(3);card(r,0).put("basic_similarity",1.0);card(r,1).put("basic_similarity",2.0/3);card(r,2).put("category_id","기타");
        assertThat(SimulationFeedRankingVerifier.expectedOrder(r)).isEqualTo(ids(r,0,2,1));
    }
    @Test void sameAuthorIsPenalizedAcrossDifferentCategories() {
        var r=request(3);card(r,0).put("basic_similarity",1.0);card(r,1).put("basic_similarity",2.0/3).put("category_id","기타").set("author_id",card(r,0).get("author_id"));
        card(r,2).put("category_id","패션");
        assertThat(SimulationFeedRankingVerifier.expectedOrder(r)).isEqualTo(ids(r,0,2,1));
    }
    @Test void categorySpacingMovesAvailableAlternativeAfterTwoSameCategories() {
        var r=request(4);card(r,3).put("category_id","기타");
        // MMR selects the alternative second, so the final output cannot contain three initial books.
        assertThat(SimulationFeedRankingVerifier.expectedOrder(r)).isEqualTo(ids(r,0,3,1,2));
    }
    @Test void newerContentWinsAndFutureRecencyIsClamped() {
        var r=request(3);card(r,0).put("content_updated_at",NOW.minusSeconds(14*86400).toString());
        card(r,2).put("content_updated_at",NOW.plusSeconds(100).toString());
        assertThat(SimulationFeedRankingVerifier.expectedOrder(r)).isEqualTo(ids(r,1,2,0));
    }
    @Test void sameMembershipAndCompositionDoNotHidePermutedRanking() {
        var r=request(3);var wrong=response(r,ids(r,1,0,2));
        assertThat(SimulationFeedCompositionVerifier.verify(r,wrong)).containsEntry("compositionGuaranteesVerified",true);
        assertThatThrownBy(()->SimulationFeedRankingVerifier.verify(r,wrong)).hasMessage("FEED_RANKING_ORDER");
        assertThat(SimulationFeedRankingVerifier.verify(r,response(r,ids(r,0,1,2)))).containsEntry("rankingAlgorithmVerified",true);
    }
    @Test void emptyInputNeedsNoSyntheticCandidates() {
        var r=request(0);assertThat(SimulationFeedRankingVerifier.verify(r,response(r,List.of()))).containsEntry("candidateCount",0);
    }
    @Test void actualPythonMatchesThresholdsPoolTruncationGuaranteesAndMmrAcrossVariedInputs() throws Exception {
        var fixture=new SimulationFeedExecutionIT();fixture.temp=temp;
        try(var python=fixture.python();var client=HttpClient.newHttpClient()) {
            var random=new Random(914006);
            int scenario=0;
            for(int size:List.of(1,3,19,20,39,40,41,60))for(int variation=0;variation<3;variation++) {
                var r=request(size);r.set("viewer_previous_month",month(variation==0?0:variation==1?5:8));
                for(int i=0;i<size;i++) {
                    var c=card(r,i);c.put("basic_similarity",random.nextInt(4)/3.0).put("title_similarity",random.nextInt(11)/10.0)
                        .put("visited_author_before",random.nextBoolean()).put("visited_category_before",random.nextBoolean())
                        .put("category_id",List.of("도서","패션","기타").get(random.nextInt(3)))
                        .put("author_id",new UUID(1,1+random.nextInt(Math.max(1,size/2))).toString())
                        .put("content_updated_at",NOW.minusSeconds(random.nextInt(20*86400)).toString());
                    var m=month(List.of(0,4,5,7,8).get(i%5));var v=(ObjectNode)m.get("values");
                    v.put("avg_amount",i%2==0?1999.0:2000.0).put("regularity_std",i%3==0?3.999:4.0).put("pace_bias",i%2==0?.3:.301);
                    if(i%7==0)m.put("coverage","PARTIAL").putNull("values");c.set("author_previous_month",m);
                    if(i%4==0)c.put("state","COMPLETED").put("closed_at",NOW.minusSeconds(i%8==0?172800:172801).toString());
                }
                byte[] bytes=JSON.writeValueAsBytes(r);
                var reply=client.send(HttpRequest.newBuilder(python.endpoint()).timeout(Duration.ofSeconds(5))
                    .header("Authorization","Bearer "+SimulationFeedExecutionIT.TOKEN).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofByteArray());
                assertThat(reply.statusCode()).as("scenario %s: %s",scenario,new String(reply.body(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200);
                var body=JSON.readTree(reply.body());
                assertThat(SimulationFeedRankingVerifier.verify(r,body)).as("scenario %s",scenario).containsEntry("rankingAlgorithmVerified",true);
                Path evidence=Path.of("build/simulation-feed-ranking/scenario-"+scenario++);Files.createDirectories(evidence);
                Files.write(evidence.resolve("request.json"),bytes);Files.write(evidence.resolve("response.json"),reply.body());
            }
        }
    }
}
