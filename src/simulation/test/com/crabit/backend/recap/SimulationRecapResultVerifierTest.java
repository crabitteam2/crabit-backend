package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SimulationRecapResultVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    ObjectNode request() {
        var r=JSON.createObjectNode().put("kind","WEEKLY").put("algorithm_version","recap-1");
        var a=r.putObject("input").putArray("success_story_candidates");
        for(int i=0;i<2;i++) a.addObject().put("wish_id",UUID.randomUUID().toString()).put("type_title","legacy-"+i).putObject("author_previous_month");
        return r;
    }
    ObjectNode view(ObjectNode r) {
        var v=JSON.createObjectNode();var a=v.putObject("page3_academy_success_stories").putArray("stories");
        for(var c:r.get("input").get("success_story_candidates"))a.addObject().put("wish_id",c.get("wish_id").asString()).put("type_title",c.get("type_title").asString());
        return v;
    }
    @Test void preservesExactOrderAndLegacyTitlesWithoutChangingRawValues() {
        var r=request();var v=view(r);String before=JSON.writeValueAsString(v);
        assertThat(SimulationRecapResultVerifier.verify(r,v).storiesVerified()).isEqualTo(2);
        assertThat(JSON.writeValueAsString(v)).isEqualTo(before);
        var a=(ArrayNode)v.get("page3_academy_success_stories").get("stories");var first=a.remove(0);a.add(first);
        assertThatThrownBy(()->SimulationRecapResultVerifier.verify(r,v)).hasMessage("RECAP_RESULT_SELECTION");
    }
    @Test void rejectsMissingExtraDuplicateAndSubstitutedStories() {
        var r=request();
        for(String change:List.of("missing","extra","duplicate","foreign")) {
            var v=view(r);var a=(ArrayNode)v.get("page3_academy_success_stories").get("stories");
            switch(change) {
                case "missing" -> a.remove(1);
                case "extra" -> a.add(a.get(0).deepCopy());
                case "duplicate" -> a.set(1,a.get(0).deepCopy());
                case "foreign" -> ((ObjectNode)a.get(0)).put("wish_id",UUID.randomUUID().toString());
            }
            assertThatThrownBy(()->SimulationRecapResultVerifier.verify(r,v)).hasMessage("RECAP_RESULT_SELECTION");
        }
    }
    @ParameterizedTest @CsvSource({
        "8,3.999,1000,0.8,불도저형 토끼", "8,4.0,1999,0.8,꾸준형 토끼",
        "5,10,1999,0.8,꾸준형 토끼", "5,10,2000,0.8,탐색형 토끼",
        "4,10,2000,0.300001,단기 집중형 토끼", "4,10,2000,0.3,탐색형 토끼",
        "0,10,0,0,탐색형 토끼"})
    void independentlyChecksVersionedThresholdsAndPriority(int count,double regularity,double avg,double pace,String title) {
        var r=request();var c=(ObjectNode)r.get("input").get("success_story_candidates").get(0);
        c.putObject("author_previous_month").put("metrics_version","core-metrics-v1").put("deposit_count",count)
            .put("regularity_std",regularity).put("avg_amount",avg).put("pace_bias",pace);
        var v=view(r);var story=(ObjectNode)v.get("page3_academy_success_stories").get("stories").get(0);story.put("type_title",title);
        assertThat(SimulationRecapResultVerifier.verify(r,v).storiesVerified()).isEqualTo(2);
        story.put("type_title","forged");assertThatThrownBy(()->SimulationRecapResultVerifier.verify(r,v)).hasMessage("RECAP_RESULT_AUTHOR_TYPE");
    }
    @Test void monthlyDoesNotClaimStoryVerificationAndUnknownAlgorithmFailsClosed() {
        var r=request().put("kind","MONTHLY");assertThat(SimulationRecapResultVerifier.verify(r,JSON.createObjectNode()).weekly()).isFalse();
        r.put("algorithm_version","recap-2");assertThatThrownBy(()->SimulationRecapResultVerifier.verify(r,JSON.createObjectNode())).hasMessage("RECAP_RESULT_ALGORITHM");
    }
}
