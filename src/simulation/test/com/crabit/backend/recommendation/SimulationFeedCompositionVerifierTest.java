package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SimulationFeedCompositionVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final Instant NOW=Instant.parse("2026-08-01T00:00:00Z");
    ObjectNode request() {
        var r=JSON.createObjectNode().put("request_id","r").put("context_id","c").put("recommendation_at",NOW.toString());
        var a=r.putArray("candidates");
        for(int i=0;i<25;i++) {
            var c=a.addObject().put("card_id","c"+i).put("state","IN_PROGRESS");
            c.putObject("author_previous_month").put("coverage","PARTIAL").putNull("values");
        }
        return r;
    }
    ObjectNode response(int... replacements) {
        var r=JSON.createObjectNode().put("schema_version",1).put("model_version","feed-rules-v1").put("request_id","r").put("context_id","c");
        var a=r.putArray("ordered_card_ids");for(int i=0;i<20;i++)a.add("c"+i);
        for(int i=0;i<replacements.length;i+=2)a.set(replacements[i],JSON.valueToTree("c"+replacements[i+1]));return r;
    }
    ObjectNode completed(ObjectNode request,int i,long age) {
        var c=(ObjectNode)request.get("candidates").get(i);c.put("state","COMPLETED").put("closed_at",NOW.minusSeconds(age).toString());return c;
    }
    void role(ObjectNode c,long count,double average,Double regularity) {
        var m=c.putObject("author_previous_month").put("coverage","COMPLETE").putObject("values");
        m.put("deposit_count",count).put("avg_amount",average);
        if(regularity==null)m.putNull("regularity_std");else m.put("regularity_std",regularity);
    }
    @Test void validOrdinarySelectionDoesNotClaimExactAlgorithmVerification() {
        assertThat(SimulationFeedCompositionVerifier.verify(request(),response())).containsEntry("compositionGuaranteesVerified",true).containsEntry("rankingAlgorithmVerified",false);
    }
    @Test void recentCompletionBoundaryIsInclusiveAndCannotBeDropped() {
        for(long age:List.of(0L,172800L)) {
            var r=request();completed(r,24,age);
            assertThatThrownBy(()->SimulationFeedCompositionVerifier.verify(r,response())).hasMessage("FEED_COMPOSITION_RECENT_COMPLETION");
            assertThat(SimulationFeedCompositionVerifier.verify(r,response(19,24))).containsEntry("recentCompletionRequired",true);
        }
        for(long age:List.of(-1L,172801L)) {
            var r=request();completed(r,24,age);
            assertThat(SimulationFeedCompositionVerifier.verify(r,response())).containsEntry("recentCompletionRequired",false);
        }
    }
    @Test void rolesMustSurviveTopTwentyAndAppearInTopTen() {
        var r=request();role(completed(r,23,200000),8,3000,3.99);role(completed(r,24,200000),5,1999.99,null);
        for(var bad:List.of(response(),response(18,23,19,24),response(0,23)))
            assertThatThrownBy(()->SimulationFeedCompositionVerifier.verify(r,bad)).hasMessage("FEED_COMPOSITION_TOP_TEN_ROLE_MODELS");
        assertThat(SimulationFeedCompositionVerifier.verify(r,response(0,23,9,24))).containsEntry("requiredTopTenRoleModels",2L);
    }
    @Test void roleThresholdsAndUnavailableMetricsDoNotInventGuarantees() {
        var r=request();role(completed(r,24,200000),8,2000,4.0);
        assertThat(SimulationFeedCompositionVerifier.verify(r,response())).containsEntry("requiredTopTenRoleModels",0L);
        ((ObjectNode)r.get("candidates").get(24).get("author_previous_month")).put("coverage","UNOBSERVED");
        assertThat(SimulationFeedCompositionVerifier.verify(r,response())).containsEntry("requiredTopTenRoleModels",0L);
    }
    @Test void oneCardCanSatisfyRecentAndRoleGuaranteesAndSmallPopulationsNeedNoPadding() {
        var r=request();role(completed(r,0,0),8,3000,0.0);
        var a=(ArrayNode)r.get("candidates");while(a.size()>1)a.remove(a.size()-1);
        var out=response();var ids=(ArrayNode)out.get("ordered_card_ids");while(ids.size()>1)ids.remove(ids.size()-1);
        assertThat(SimulationFeedCompositionVerifier.verify(r,out)).containsEntry("requiredTopTenRoleModels",1L).containsEntry("selectedCount",1);
        a.removeAll();ids.removeAll();assertThat(SimulationFeedCompositionVerifier.verify(r,out)).containsEntry("selectedCount",0);
    }
    @Test void malformedIdentityCountAndMembershipAreRejected() {
        var r=request();var bad=response();bad.put("context_id","other");
        assertThatThrownBy(()->SimulationFeedCompositionVerifier.verify(r,bad)).hasMessage("FEED_COMPOSITION_IDENTITY");
        assertThatThrownBy(()->SimulationFeedCompositionVerifier.verify(r,response(0,1))).hasMessage("FEED_COMPOSITION_MEMBERSHIP");
        assertThatThrownBy(()->SimulationFeedCompositionVerifier.verify(r,response(0,99))).hasMessage("FEED_COMPOSITION_MEMBERSHIP");
        var shortResponse=response();((ArrayNode)shortResponse.get("ordered_card_ids")).remove(0);
        assertThatThrownBy(()->SimulationFeedCompositionVerifier.verify(r,shortResponse)).hasMessage("FEED_COMPOSITION_COUNT");
    }
}
