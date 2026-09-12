package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

class SimulationFeedMetricVerifierTest {
    private final SimulationFeedInputVerifierTest fixtures=new SimulationFeedInputVerifierTest();
    private final ObjectNode source=fixtures.fixture().source();
    private final JsonNode account=source.get("card_balance_account").get(1);
    private static final YearMonth MONTH=YearMonth.of(2026,6);
    private static final Instant CUTOFF=Instant.parse("2026-07-01T00:00:00Z");
    private ObjectNode ledger(String id,String type,String at,String parent,long delta,String wish) {
        var event=((ArrayNode)source.get("ledger_event")).addObject().put("id",id).put("account_id","author-account")
            .put("event_type",type).put("occurred_at",at).put("account_delta",0);
        if(parent==null)event.putNull("correction_of_event_id");else event.put("correction_of_event_id",parent);
        effect(id,wish,delta);return event;
    }
    private void effect(String id,String wish,long delta) {
        ((ArrayNode)source.get("ledger_wish_effect")).addObject().put("event_id",id).put("account_id","author-account").put("wish_id",wish).put("wish_delta",delta);
    }
    private JsonNode values(){return SimulationFeedMetricVerifier.compute(MONTH,account,CUTOFF,source);}
    private void richHistory() {
        ledger("first","WISH_DEPOSIT","2026-06-01T00:00:00Z",null,1000,"wish");
        ledger("second","WISH_DEPOSIT","2026-06-03T00:00:00Z",null,2000,"wish");
        ledger("third","WISH_DEPOSIT","2026-06-06T00:00:00Z",null,3000,"wish");
        ledger("withdraw","WISH_WITHDRAWAL","2026-06-20T00:00:00Z",null,-500,"wish");
        ledger("correction","CORRECTION","2026-06-30T15:00:00Z","first",500,"wish");
        var other=source.get("wish").get(0).deepCopy();((ObjectNode)other).put("id","other-wish");((ArrayNode)source.get("wish")).add(other);
        ledger("transfer","WISH_TRANSFER","2026-06-20T00:00:00Z",null,-100,"wish");effect("transfer","other-wish",100);
        ledger("returned","WISH_COMPLETION_RETURN","2026-06-21T00:00:00Z",null,-100,"other-wish");
        ((ObjectNode)source.get("wish").get(1)).put("state","ABANDONED").put("abandoned_at","2026-06-22T00:00:00Z");
        visit("2026-06-20T00:00:00Z",CUTOFF.toString(),"author","academy");
        visit("2026-06-20T00:00:00Z",CUTOFF.plusSeconds(1).toString(),"author","academy");
        visit("2026-06-20T00:00:00Z",CUTOFF.toString(),"viewer","academy");
        visit("2026-06-30T15:00:00Z",CUTOFF.toString(),"author","academy");
    }
    private void visit(String at,String received,String actor,String academy) {
        ((ArrayNode)source.get("behavior_event")).addObject().put("event_type","PROFILE_VISIT").put("actor_id",actor)
            .put("academy_id",academy).put("occurred_at",at).put("received_at",received);
    }
    @Test void recalculatesCorrectedSavingsDistinctDepositDayIntervalsAndCounts() {
        richHistory();var v=values();
        assertThat(v.get("deposit_count").asLong()).isEqualTo(3);
        assertThat(v.get("total_savings").asLong()).isEqualTo(6000);
        assertThat(v.get("avg_amount").asDouble()).isEqualTo(2000.0);
        assertThat(v.get("regularity_std").asDouble()).isEqualTo(0.5);
        assertThat(v.get("pace_bias").asDouble()).isEqualTo(-7.0/6.0);
        for(String field:new String[]{"transfer_count","abandon_count","visit_count"})assertThat(v.get(field).asLong()).as(field).isEqualTo(1);
        SimulationFeedMetricVerifier.verify(v,MONTH,account,CUTOFF,source);
    }
    @Test void tamperingAnyNumericMetricOrAddingAnExtraFieldIsRejected() {
        richHistory();var expected=values();
        for(String field:expected.propertyNames()) {
            var forged=(ObjectNode)expected.deepCopy();forged.put(field,999);
            assertThatThrownBy(()->SimulationFeedMetricVerifier.verify(forged,MONTH,account,CUTOFF,source)).hasMessage("FEED_METRIC_VALUE:"+field);
        }
        var extra=(ObjectNode)expected.deepCopy();extra.put("unexpected",0);
        assertThatThrownBy(()->SimulationFeedMetricVerifier.verify(extra,MONTH,account,CUTOFF,source)).hasMessage("FEED_METRIC_FIELDS");
    }
    @Test void futureCorrectionCannotChangeEarlierMonthAndSameInstantCorrectionCan() {
        ledger("root","WISH_DEPOSIT","2026-06-01T00:00:00Z",null,1000,"wish");
        var correction=ledger("fix","CORRECTION",CUTOFF.plusSeconds(1).toString(),"root",500,"wish");
        assertThat(values().get("total_savings").asLong()).isEqualTo(1000);
        correction.put("occurred_at",CUTOFF.toString());assertThat(values().get("total_savings").asLong()).isEqualTo(1500);
    }
    @Test void seoulMonthBoundaryAndZeroNetKeepNullableMetrics() {
        ledger("excluded","WISH_DEPOSIT","2026-05-31T14:59:59Z",null,9999,"wish");
        ledger("included","WISH_DEPOSIT","2026-05-31T15:00:00Z",null,1000,"wish");
        ledger("withdraw","WISH_WITHDRAWAL","2026-06-30T14:59:59Z",null,-1000,"wish");
        ledger("later","WISH_DEPOSIT","2026-06-30T15:00:00Z",null,9999,"wish");
        var v=values();assertThat(v.get("total_savings").asLong()).isZero();
        assertThat(v.get("deposit_count").asLong()).isEqualTo(1);
        assertThat(v.get("regularity_std").isNull()).isTrue();assertThat(v.get("pace_bias").isNull()).isTrue();
    }
    @Test void missingAndBranchedCorrectionParentsAreRejected() {
        var fix=ledger("fix","CORRECTION","2026-06-03T00:00:00Z","missing",100,"wish");
        assertThatThrownBy(this::values).hasMessage("FEED_METRIC_MISSING_CORRECTION_PARENT");
        ledger("root","WISH_DEPOSIT","2026-06-01T00:00:00Z",null,1000,"wish");fix.put("correction_of_event_id","root");
        ledger("fix2","CORRECTION","2026-06-04T00:00:00Z","root",100,"wish");
        assertThatThrownBy(this::values).hasMessage("FEED_METRIC_BRANCHED_CORRECTION");
    }
    @Test void malformedTransferAndDuplicateEffectsAreRejected() {
        ledger("transfer","WISH_TRANSFER","2026-06-01T00:00:00Z",null,-100,"wish");
        assertThatThrownBy(this::values).hasMessage("FEED_METRIC_TRANSFER_CHAIN");
        effect("transfer","wish",100);assertThatThrownBy(this::values).hasMessage("FEED_METRIC_DUPLICATE_EFFECT");
    }
    @Test void missingSourceTableNeverBecomesZeroActivity() {
        source.remove("ledger_event");assertThatThrownBy(this::values).hasMessage("FEED_METRIC_SOURCE_TABLE:ledger_event");
    }
    @Test void capturedCompleteMonthValuesAreActuallyCheckedByInputVerifier() {
        var f=fixtures.fixture();((ObjectNode)f.source().get("behavior_collection").get(0)).put("started_at","2026-05-01T00:00:00Z");
        f.request().set("viewer_previous_month",fixtures.monthly("2026-06","COMPLETE"));
        ((ObjectNode)f.request().get("candidates").get(0)).set("author_previous_month",fixtures.monthly("2026-06","COMPLETE"));
        assertThat(SimulationFeedInputVerifier.verify(f.request(),f.source())).containsEntry("completeMetricMonthsVerified",2);
        ((ObjectNode)f.request().get("candidates").get(0).get("author_previous_month").get("values")).put("deposit_count",1);
        assertThatThrownBy(()->SimulationFeedInputVerifier.verify(f.request(),f.source())).hasMessage("FEED_METRIC_VALUE:deposit_count");
    }
}
