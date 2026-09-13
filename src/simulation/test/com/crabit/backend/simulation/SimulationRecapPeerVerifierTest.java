package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SimulationRecapPeerVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    ObjectNode request(){return (ObjectNode)JSON.readTree("""
      {"student_id":"viewer","card_balance_account_id":"a0","academy_id":"academy","snapshot_at":"2026-06-07T15:00:00Z",
       "period":{"end_date_exclusive":"2026-06-08"},"input":{"representative_wish_id":null,
       "peer_metrics":{"habit_active_weeks":[1,0],"achievement_rates":[40.0]}}}
      """);}
    ObjectNode source(){return (ObjectNode)JSON.readTree("""
      {"accounts":[
        {"id":"a0","student_id":"viewer","academy_id":"academy","closed_at":null},
        {"id":"a1","student_id":"peer","academy_id":"academy","closed_at":null},
        {"id":"a2","student_id":"empty","academy_id":"academy","closed_at":null}],
       "students":[{"id":"viewer","age":10,"age_provenance":"PROVIDED"},
        {"id":"peer","age":12,"age_provenance":"PROVIDED"},{"id":"empty","age":8,"age_provenance":"PROVIDED"}],
       "memberships":[{"student_id":"viewer","academy_id":"academy","joined_at":"2026-06-01T00:00:00Z","left_at":null},
        {"student_id":"peer","academy_id":"academy","joined_at":"2026-06-01T00:00:00Z","left_at":null},
        {"student_id":"empty","academy_id":"academy","joined_at":"2026-06-01T00:00:00Z","left_at":null}],
       "representative_wish_selection":[],
       "wish":[{"id":"w1","account_id":"a1","created_at":"2026-06-01T00:00:00Z","state":"IN_PROGRESS","deleted_at":null,"target_amount":2000}],
       "ledger_event":[{"id":"r","account_id":"a1","event_type":"WISH_DEPOSIT","occurred_at":"2026-06-01T01:00:00Z","correction_of_event_id":null},
        {"id":"c","account_id":"a1","event_type":"CORRECTION","occurred_at":"2026-06-07T14:59:59.999999Z","correction_of_event_id":"r"}],
       "ledger_wish_effect":[{"event_id":"r","account_id":"a1","wish_id":"w1","wish_delta":1000},
        {"event_id":"c","account_id":"a1","wish_id":"w1","wish_delta":-200}]}
      """);}
    @Test void includesAgeEdgesAndZeroActivityButNoRateWithoutRepresentative(){
        var v=SimulationRecapPeerVerifier.verify(request(),source());assertThat(v.peers()).isEqualTo(2);assertThat(v.achievementRates()).isEqualTo(1);
    }
    @Test void excludesClosedLeftUnknownAgeAndOutsideAgeRange(){
        for(String mode:List.of("closed","left","unknown","age")){
            var s=source();switch(mode){
                case "closed" -> ((ObjectNode)s.get("accounts").get(1)).put("closed_at","2026-06-02T00:00:00Z");
                case "left" -> ((ObjectNode)s.get("memberships").get(1)).put("left_at","2026-06-02T00:00:00Z");
                case "unknown" -> ((ObjectNode)s.get("students").get(1)).put("age_provenance","DEFAULT");
                case "age" -> ((ObjectNode)s.get("students").get(1)).put("age",13);
            }
            var r=request();var p=(ObjectNode)r.get("input").get("peer_metrics");p.putArray("habit_active_weeks").add(0);p.putArray("achievement_rates");
            assertThat(SimulationRecapPeerVerifier.verify(r,s).peers()).isEqualTo(1);
        }
    }
    @Test void unknownViewerAgeHasNoPeerComparison(){
        var s=source();((ObjectNode)s.get("students").get(0)).put("age_provenance","DEFAULT");var r=request();
        var p=(ObjectNode)r.get("input").get("peer_metrics");p.putArray("habit_active_weeks");p.putArray("achievement_rates");
        assertThat(SimulationRecapPeerVerifier.verify(r,s).peers()).isZero();
    }
    @Test void rejectsAlteredOrOmittedPeerValues(){
        var r=request();((ObjectNode)r.get("input").get("peer_metrics")).putArray("habit_active_weeks").add(1);
        assertThatThrownBy(()->SimulationRecapPeerVerifier.verify(r,source())).hasMessage("RECAP_PEER_HABIT_WEEKS");
        var r2=request();((ObjectNode)r2.get("input").get("peer_metrics")).putArray("achievement_rates").add(50.0);
        assertThatThrownBy(()->SimulationRecapPeerVerifier.verify(r2,source())).hasMessage("RECAP_PEER_ACHIEVEMENT_RATES");
    }
    @Test void checksRepresentativeChoiceEvenWhenRequestFlagsAreInternallyConsistent(){
        var r=request();((ObjectNode)r.get("input")).put("representative_wish_id","w1");
        assertThatThrownBy(()->SimulationRecapPeerVerifier.verify(r,source())).hasMessage("RECAP_PEER_VIEWER_REPRESENTATIVE");
        var s=source();((ArrayNode)s.get("representative_wish_selection")).addObject().put("account_id","a0").put("wish_id","w1");
        assertThatThrownBy(()->SimulationRecapPeerVerifier.verify(request(),s)).hasMessage("RECAP_PEER_REPRESENTATIVE_OWNER");
    }
    @Test void rejectsBoundaryCorrectionAndBrokenParent(){
        var s=source();((ObjectNode)s.get("ledger_event").get(1)).put("occurred_at","2026-06-07T15:00:00Z");
        assertThatThrownBy(()->SimulationRecapPeerVerifier.verify(request(),s)).hasMessage("RECAP_PEER_FUTURE_LEDGER");
        var s2=source();((ObjectNode)s2.get("ledger_event").get(1)).put("correction_of_event_id","missing");
        assertThatThrownBy(()->SimulationRecapPeerVerifier.verify(request(),s2)).hasMessage("RECAP_PEER_MISSING_CORRECTION");
    }
    @Test void oldDepositsAffectAchievementButNot52WeekHabit(){
        var s=source();((ObjectNode)s.get("ledger_event").get(0)).put("occurred_at","2025-06-08T14:59:59.999999Z");
        var r=request();((ObjectNode)r.get("input").get("peer_metrics")).putArray("habit_active_weeks").add(0).add(0);
        SimulationRecapPeerVerifier.verify(r,s);
    }
    @Test void sameWeekDepositsCountOnceAndRateIsNotCappedAt100(){
        var s=source();((ObjectNode)s.get("ledger_wish_effect").get(1)).put("wish_delta",2000);
        var r=request();((ObjectNode)r.get("input").get("peer_metrics")).putArray("achievement_rates").add(150.0);
        SimulationRecapPeerVerifier.verify(r,s);
    }
}
