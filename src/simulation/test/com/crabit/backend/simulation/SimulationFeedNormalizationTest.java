package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationFeedNormalizationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    @TempDir Path temp;
    record Fixture(ObjectNode request,ObjectNode response,ObjectNode row,Map<String,UUID> ids,List<JsonNode> events,Path folder){}
    Fixture fixture() throws Exception {
        UUID request=UUID.randomUUID(),context=UUID.randomUUID(),viewer=UUID.randomUUID(),academy=UUID.randomUUID(),card=UUID.randomUUID();
        var req=JSON.createObjectNode().put("request_id",request.toString()).put("context_id",context.toString()).put("viewer_id",viewer.toString()).put("academy_id",academy.toString());
        req.putArray("candidates").addObject().put("card_id",card.toString()).put("author_id",viewer.toString()).put("freeText",card.toString()).put("amount",0).put("basic_similarity",0.125).putNull("closed_at");
        var resp=JSON.createObjectNode().put("schema_version",1).put("request_id",request.toString()).put("context_id",context.toString()).put("model_version","feed-rules-v1");
        resp.put("input_digest",SimulationBundleReader.digest(JSON.writeValueAsBytes(req)));resp.putArray("ordered_card_ids").add(card.toString());
        var row=JSON.createObjectNode().put("id",context.toString()).put("recommendation_request_id",request.toString()).put("viewer_id",viewer.toString()).put("academy_id",academy.toString()).put("model_version","feed-rules-v1").put("ranking_outcome","RECOMMENDATION");
        row.set("ranked_card_ids",resp.get("ordered_card_ids").deepCopy());
        Path folder=Files.createDirectories(temp.resolve("event-1"));
        var page=JSON.createObjectNode();page.putObject("page").put("recommendationResultId",request.toString()).put("sortSource","RECOMMENDATION");
        Files.write(folder.resolve("page.json"),JSON.writeValueAsBytes(page));Files.writeString(folder.resolve("http.json"),"{\"status\":200}");
        var f=new Fixture(req,resp,row,Map.of("FEED_RECOMMENDATION:e1",request,"STUDENT:one",viewer,"ACADEMY:school",academy,"SHARED_CARD:card",card),List.of(JSON.createObjectNode().put("sequence",1).put("eventId","e1").put("kind","FEED_QUERY")),folder);write(f);return f;
    }
    void write(Fixture f) throws Exception {Files.write(f.folder().resolve("request.json"),JSON.writeValueAsBytes(f.request()));Files.write(f.folder().resolve("response.json"),JSON.writeValueAsBytes(f.response()));}
    SimulationFeedNormalization.Verified verify(Fixture f) throws Exception {
        var state=new SimulationRelationalState.State(1,"test","dataset","catalog",Map.of("feed_page_context",List.of(f.row())));
        return SimulationFeedNormalization.verify(f.events(),temp,new SimulationRelationalState.Export(null,state),f.ids());
    }
    @Test void mapsOnlyBoundIdentitiesAndPreservesRawTextNullAndAmounts() throws Exception {
        var f=fixture();byte[] raw=Files.readAllBytes(f.folder().resolve("request.json"));var result=verify(f);
        var request=result.exchanges().get(0).get("request");
        assertThat(request.get("request_id").asString()).isEqualTo("FEED_RECOMMENDATION:e1");
        assertThat(request.get("candidates").get(0).get("card_id").asString()).isEqualTo("SHARED_CARD:card");
        for(String key:List.of("freeText","amount","basic_similarity","closed_at"))assertThat(request.get("candidates").get(0).get(key)).isEqualTo(f.request().get("candidates").get(0).get(key));
        assertThat(Files.readAllBytes(f.folder().resolve("request.json"))).isEqualTo(raw);
    }
    @Test void refusesChangedRequestBytesEvenWithSameParsedJson() throws Exception {
        var f=fixture();Files.writeString(f.folder().resolve("request.json"),JSON.writeValueAsString(f.request())+" ");
        assertThatThrownBy(()->verify(f)).hasMessage("FEED_NORMALIZATION_INPUT_DIGEST");
    }
    @Test void refusesAnotherRequestOrContext() throws Exception {
        var f=fixture();f.response().put("request_id",UUID.randomUUID().toString());write(f);
        assertThatThrownBy(()->verify(f)).hasMessage("FEED_NORMALIZATION_RESPONSE_BINDING");
    }
    @Test void refusesStoredOrderDrift() throws Exception {
        var f=fixture();f.row().putArray("ranked_card_ids");
        assertThatThrownBy(()->verify(f)).hasMessage("FEED_NORMALIZATION_STORAGE_BINDING");
    }
    @Test void refusesUncapturedStoredRecommendation() throws Exception {
        var f=fixture();Files.delete(f.folder().resolve("response.json"));
        assertThatThrownBy(()->verify(f)).hasMessage("FEED_NORMALIZATION_UNVERIFIED_STORED_RECOMMENDATION");
    }
    @Test void malformedHttp200ThatProductionRejectedStaysRawFallbackEvidence() throws Exception {
        var f=fixture();f.row().put("ranking_outcome","LATEST").putNull("recommendation_request_id").putNull("model_version");
        f.row().putArray("ranked_card_ids");Files.writeString(f.folder().resolve("response.json"),"not JSON");
        var result=verify(f);assertThat(result.exchanges()).isEmpty();assertThat(result.recommendationIds()).isEmpty();
        assertThat(Files.readString(f.folder().resolve("response.json"))).isEqualTo("not JSON");
    }
    @Test void rejectsUnknownTypedCandidateInsteadOfErasingIt() throws Exception {
        var f=fixture();((ObjectNode)f.request().get("candidates").get(0)).put("author_id",UUID.randomUUID().toString());
        f.response().put("input_digest",SimulationBundleReader.digest(JSON.writeValueAsBytes(f.request())));write(f);
        assertThatThrownBy(()->verify(f)).hasMessage("FEED_NORMALIZATION_UNKNOWN_ID:STUDENT");
    }
}
