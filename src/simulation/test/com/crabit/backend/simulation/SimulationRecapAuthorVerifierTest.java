package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SimulationRecapAuthorVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    ObjectNode request(){return (ObjectNode)JSON.readTree("""
      {"academy_id":"academy","snapshot_at":"2026-07-05T15:00:00Z","input":{"success_story_candidates":[
      {"wish_id":"story","author_previous_month":{"metrics_version":"core-metrics-v1","deposit_count":3,
      "total_savings":550,"avg_amount":183.33333333333334,"regularity_std":0.0,"pace_bias":-1.0,
      "abandon_count":1,"transfer_count":1,"visit_count":1}}]}}
      """);}
    ObjectNode metrics(ObjectNode r){return (ObjectNode)r.get("input").get("success_story_candidates").get(0).get("author_previous_month");}
    ObjectNode source(){
        var s=(ObjectNode)JSON.readTree("""
          {"peer_source":{"accounts":[{"id":"account","student_id":"author","academy_id":"academy"}],
          "wish":[{"id":"story","account_id":"account","state":"COMPLETED","completed_at":"2026-07-02T00:00:00Z"},
                  {"id":"other","account_id":"account","state":"ABANDONED","abandoned_at":"2026-06-05T00:00:00Z"}],
          "ledger_event":[],"ledger_wish_effect":[]},"behavior_event":[
          {"academy_id":"academy","event_type":"PROFILE_VISIT","actor_id":"author","target_id":"viewer",
           "occurred_at":"2026-06-02T00:00:00Z","received_at":"2026-07-05T15:00:00Z"}]}
          """);
        add(s,"a","2026-05-31T15:00:00Z","WISH_DEPOSIT",100,"story",null);
        add(s,"b","2026-06-01T15:00:00Z","WISH_DEPOSIT",200,"story",null);
        add(s,"c","2026-06-02T15:00:00Z","WISH_DEPOSIT",300,"other",null);
        add(s,"w","2026-06-03T00:00:00Z","WISH_WITHDRAWAL",-50,"story",null);
        add(s,"t","2026-06-04T00:00:00Z","WISH_TRANSFER",-20,"story",null);
        effect(s,"t","other",20);
        add(s,"return","2026-06-05T00:00:00Z","WISH_ABANDONMENT_RETURN",-300,"other",null);
        add(s,"before","2026-05-31T14:59:59.999999Z","WISH_DEPOSIT",999,"story",null);
        add(s,"after","2026-06-30T15:00:00Z","WISH_DEPOSIT",999,"story",null);
        return s;
    }
    void add(ObjectNode s,String id,String at,String type,long delta,String wish,String parent){
        ((ArrayNode)s.get("peer_source").get("ledger_event")).addObject().put("id",id).put("account_id","account")
            .put("event_type",type).put("occurred_at",at).put("correction_of_event_id",parent);effect(s,id,wish,delta);
    }
    void effect(ObjectNode s,String id,String wish,long delta){((ArrayNode)s.get("peer_source").get("ledger_wish_effect")).addObject()
        .put("event_id",id).put("account_id","account").put("wish_id",wish).put("wish_delta",delta);}
    @Test void reconcilesAccountWideSavingsTransferReturnAndHalfOpenKoreanMonth(){
        assertThat(SimulationRecapAuthorVerifier.verify(request(),source()).authorsVerified()).isEqualTo(1);
    }
    @Test void rejectsEveryForgedMetricAndMissingOrAdditionalFields(){
        for(String name:List.of("deposit_count","total_savings","avg_amount","regularity_std","pace_bias","abandon_count","transfer_count","visit_count")) {
            var r=request();metrics(r).put(name,999);
            assertThatThrownBy(()->SimulationRecapAuthorVerifier.verify(r,source())).hasMessage("RECAP_AUTHOR_METRIC:"+name);
        }
        var r=request();metrics(r).put("metrics_version","invented");assertThatThrownBy(()->SimulationRecapAuthorVerifier.verify(r,source())).hasMessage("RECAP_AUTHOR_METRIC:metrics_version");
        for(boolean extra:List.of(true,false)){var x=request();if(extra)metrics(x).put("extra",1);else metrics(x).remove("pace_bias");assertThatThrownBy(()->SimulationRecapAuthorVerifier.verify(x,source())).hasMessage("RECAP_AUTHOR_METRICS_FIELDS");}
    }
    @Test void correctionReceivedAfterMonthStillFoldsIntoOriginalRootAtClosure(){
        var s=source();add(s,"correction","2026-07-02T00:00:00Z","CORRECTION",-100,"story","a");
        var r=request();metrics(r).put("deposit_count",2).put("total_savings",450).put("avg_amount",225.0);
        assertThat(SimulationRecapAuthorVerifier.verify(r,s).authorsVerified()).isEqualTo(1);
    }
    @Test void oneSavingDayHasNullRegularityAndNonpositiveNetHasNullPace(){
        var s=source();((ArrayNode)s.get("peer_source").get("ledger_event")).removeAll();((ArrayNode)s.get("peer_source").get("ledger_wish_effect")).removeAll();
        add(s,"single","2026-06-01T00:00:00Z","WISH_DEPOSIT",100,"story",null);add(s,"withdraw","2026-06-02T00:00:00Z","WISH_WITHDRAWAL",-150,"story",null);
        var r=request();metrics(r).put("deposit_count",1).put("total_savings",-50).put("avg_amount",-50.0).putNull("regularity_std").putNull("pace_bias").put("transfer_count",0);
        assertThat(SimulationRecapAuthorVerifier.verify(r,s).authorsVerified()).isEqualTo(1);
    }
    @Test void repeatedDayDepositsCountIndividuallyAndUseUniqueDaysForPopulationDeviation(){
        var s=source();add(s,"extra","2026-06-01T16:00:00Z","WISH_DEPOSIT",50,"story",null);
        add(s,"later","2026-06-15T15:00:00Z","WISH_DEPOSIT",100,"story",null);
        var r=request();metrics(r).put("deposit_count",5).put("total_savings",700).put("avg_amount",140.0)
            .put("regularity_std",Math.sqrt(32.0)).put("pace_bias",-500.0/700);
        assertThat(SimulationRecapAuthorVerifier.verify(r,s).authorsVerified()).isEqualTo(1);
    }
    @Test void visitsRequireAuthorAcademyEventTimeAndReceiptCutoff(){
        var s=source();var rows=(ArrayNode)s.get("behavior_event");var original=(ObjectNode)rows.get(0);
        for(String mode:List.of("late","outside","other","academy","type")){
            var v=original.deepCopy();switch(mode){case "late"->v.put("received_at","2026-07-05T15:00:00.000001Z");case "outside"->v.put("occurred_at","2026-06-30T15:00:00Z");case "other"->v.put("actor_id","viewer");case "academy"->v.put("academy_id","else");case "type"->v.put("event_type","FEED_CLICK");}rows.add(v);
        }
        assertThat(SimulationRecapAuthorVerifier.verify(request(),s).authorsVerified()).isEqualTo(1);
    }
    @Test void malformedCorrectionAndForeignEffectFailClosed(){
        for(String mode:List.of("future","missing","branch","owner")) {
            var s=source();switch(mode){
                case "future"->add(s,"new","2026-07-05T15:00:00Z","WISH_DEPOSIT",1,"story",null);
                case "missing"->add(s,"new","2026-07-02T00:00:00Z","CORRECTION",1,"story","absent");
                case "branch"->{add(s,"new","2026-07-02T00:00:00Z","CORRECTION",1,"story","a");add(s,"new2","2026-07-02T00:00:00Z","CORRECTION",1,"story","a");}
                case "owner"->((ObjectNode)s.get("peer_source").get("wish").get(1)).put("account_id","foreign");
            }
            assertThatThrownBy(()->SimulationRecapAuthorVerifier.verify(request(),s)).hasMessageStartingWith("RECAP_AUTHOR_");
        }
    }
}
