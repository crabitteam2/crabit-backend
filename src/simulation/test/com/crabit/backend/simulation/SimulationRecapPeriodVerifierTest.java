package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SimulationRecapPeriodVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    ObjectNode source() {return (ObjectNode)JSON.readTree("""
        {"ledger_event":[
          {"id":"root","account_id":"account","event_type":"WISH_DEPOSIT","occurred_at":"2026-06-01T01:00:00Z","correction_of_event_id":null},
          {"id":"correct","account_id":"account","event_type":"CORRECTION","occurred_at":"2026-06-07T14:59:59.999999Z","correction_of_event_id":"root"}],
         "ledger_wish_effect":[
          {"event_id":"root","account_id":"account","wish_id":"wish","wish_delta":1000},
          {"event_id":"correct","account_id":"account","wish_id":"wish","wish_delta":-200}],
         "wish":[{"id":"wish","account_id":"account","purpose":"Goal","state":"IN_PROGRESS","target_amount":2000,
           "created_at":"2026-06-01T00:00:00Z","completed_at":null,"abandoned_at":null,"deleted_at":null}],
         "behavior_event":[
          {"academy_id":"academy","event_type":"PROFILE_VISIT","actor_id":"peer","target_id":"student","occurred_at":"2026-06-07T14:59:59.999999Z","received_at":"2026-06-07T14:59:59.999999Z"},
          {"academy_id":"academy","event_type":"PROFILE_VISIT","actor_id":"peer","target_id":"student","occurred_at":"2026-06-07T15:00:00Z","received_at":"2026-06-07T15:00:00Z"},
          {"academy_id":"academy","event_type":"PROFILE_VISIT","actor_id":"peer","target_id":"student","occurred_at":"2026-06-01T01:00:00Z","received_at":"2026-06-07T15:00:00.000001Z"},
          {"academy_id":"academy","event_type":"PROFILE_VISIT","actor_id":"student","target_id":"peer","occurred_at":"2026-06-01T01:00:00Z","received_at":"2026-06-01T01:00:00Z"}]}
        """);}
    ObjectNode request() {return (ObjectNode)JSON.readTree("""
        {"card_balance_account_id":"account","student_id":"student","academy_id":"academy","snapshot_at":"2026-06-07T15:00:00Z",
         "period":{"start_date":"2026-06-01","end_date_exclusive":"2026-06-08","timezone":"Asia/Seoul"},
         "input":{"representative_wish_id":"wish","effective_transactions":[
          {"root_event_id":"root","wish_id":"wish","occurred_at":"2026-06-01T01:00:00Z","amount":800,"type":"DEPOSIT"}],
          "wishes":[{"wish_id":"wish","title":"Goal","status":"IN_PROGRESS","target_amount":2000,"created_at":"2026-06-01T00:00:00Z",
           "closed_at":null,"deleted_at":null,"is_representative":true,"saved_amount_at_period_end":800}],
          "visit_metrics":{"received_visit_count":1,"unique_received_visitor_count":1,"previous_week_received_visit_count":0,"monthly_outgoing_visit_count":1}}}
        """);}
    @Test void independentlyFoldsCorrectionAndUsesKstExclusiveBoundaryAndReceiptCutoff() {
        var v=SimulationRecapPeriodVerifier.verify(request(),source());
        assertThat(v.periodDeposits()).isEqualTo(1);assertThat(v.periodNetSavings()).isEqualTo(800);
        assertThat(v.receivedVisits()).isEqualTo(1);assertThat(v.outgoingVisits()).isEqualTo(1);
    }
    @Test void rejectsOmittedDuplicatedOrRevaluedTransactionsEvenWhenAggregateCouldMatch() {
        for(String mode:List.of("missing","duplicate","amount","root","time")) {
            var r=request();var tx=(ArrayNode)r.get("input").get("effective_transactions");
            switch(mode) {
                case "missing" -> tx.removeAll();case "duplicate" -> tx.add(tx.get(0).deepCopy());
                case "amount" -> ((ObjectNode)tx.get(0)).put("amount",1000);
                case "root" -> ((ObjectNode)tx.get(0)).put("root_event_id","another");
                case "time" -> ((ObjectNode)tx.get(0)).put("occurred_at","2026-06-07T15:00:00Z");
            }
            assertThatThrownBy(()->SimulationRecapPeriodVerifier.verify(r,source())).hasMessage("RECAP_PERIOD_EFFECTIVE_TRANSACTIONS");
        }
    }
    @Test void doesNotAllowBoundaryCorrectionToRewritePriorPeriod() {
        var s=source();((ObjectNode)s.get("ledger_event").get(1)).put("occurred_at","2026-06-07T15:00:00Z");
        assertThatThrownBy(()->SimulationRecapPeriodVerifier.verify(request(),s)).hasMessage("RECAP_PERIOD_FUTURE_LEDGER");
    }
    @Test void rejectsFutureMissingOrRevaluedWishes() {
        var s=source();((ObjectNode)s.get("wish").get(0)).put("created_at","2026-06-07T15:00:00Z");
        assertThatThrownBy(()->SimulationRecapPeriodVerifier.verify(request(),s)).hasMessage("RECAP_PERIOD_FUTURE_WISH");
        var r=request();((ObjectNode)r.get("input").get("wishes").get(0)).put("saved_amount_at_period_end",1000);
        assertThatThrownBy(()->SimulationRecapPeriodVerifier.verify(r,source())).hasMessage("RECAP_PERIOD_WISH_VALUES");
        var omitted=request();((ArrayNode)omitted.get("input").get("wishes")).removeAll();
        assertThatThrownBy(()->SimulationRecapPeriodVerifier.verify(omitted,source())).hasMessage("RECAP_PERIOD_WISH_SET");
    }
    @Test void rejectsBoundaryAndLateReceivedVisitsCountedInClosedPeriod() {
        var r=request();((ObjectNode)r.get("input").get("visit_metrics")).put("received_visit_count",3);
        assertThatThrownBy(()->SimulationRecapPeriodVerifier.verify(r,source())).hasMessage("RECAP_PERIOD_VISIT_METRICS");
    }
    @Test void rejectsWrongAccountAndBrokenCorrectionAncestry() {
        var s=source();((ObjectNode)s.get("ledger_event").get(0)).put("account_id","foreign");
        assertThatThrownBy(()->SimulationRecapPeriodVerifier.verify(request(),s)).hasMessage("RECAP_PERIOD_LEDGER_OWNER");
        var missing=source();((ObjectNode)missing.get("ledger_event").get(1)).put("correction_of_event_id","unknown");
        assertThatThrownBy(()->SimulationRecapPeriodVerifier.verify(request(),missing)).hasMessage("RECAP_PERIOD_MISSING_CORRECTION");
    }
}
