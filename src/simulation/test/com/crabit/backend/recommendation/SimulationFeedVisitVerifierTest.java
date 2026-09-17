package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.*;

class SimulationFeedVisitVerifierTest {
    final SimulationFeedInputVerifierTest.Fixture fixture=new SimulationFeedInputVerifierTest().fixture();
    final Instant at=Instant.parse("2026-07-01T00:00:00Z");
    ObjectNode visit(String id,Instant occurred,Instant received) {
        return ((ArrayNode)fixture.source().get("behavior_event")).addObject().put("event_id",id).put("actor_id","viewer")
            .put("academy_id","academy").put("target_id","author").put("event_type","PROFILE_VISIT")
            .put("occurred_at",occurred.toString()).put("received_at",received.toString());
    }
    ObjectNode detail(String id,String status) {
        var row=((ArrayNode)fixture.source().get("feed_visit_evidence")).addObject().put("event_id",id).put("actor_id","viewer").put("evidence_status",status);
        row.putArray("category_ids").add("도서");return row;
    }
    ObjectNode candidate(){return (ObjectNode)fixture.request().get("candidates").get(0);}
    void verify(){SimulationFeedVisitVerifier.verify(fixture.request(),fixture.source());}
    @Test void exactRetentionAndReceiptCutoffsIncludeHistoricalInterest() {
        visit("visit",at.minus(Duration.ofDays(90)),at);detail("visit","COMPLETE");
        candidate().put("visited_author_before",true).put("visited_category_before",true);verify();
        candidate().put("visited_author_before",false);assertThatThrownBy(this::verify).hasMessage("FEED_VISIT_visited_author_before");
    }
    @Test void olderFutureAndLateReceivedVisitsCannotCreateSignals() {
        visit("old",at.minus(Duration.ofDays(90)).minusNanos(1000),at);
        visit("future",at.plusNanos(1000),at);
        visit("late",at.minusSeconds(1),at.plusNanos(1000));
        for(String id:new String[]{"old","future","late"})detail(id,"COMPLETE");verify();
    }
    @Test void categoryRequiresCompleteEvidenceBoundToSameActorAndEvent() {
        visit("visit",at,at);detail("visit","PARTIAL");
        detail("other-event","COMPLETE");detail("visit","COMPLETE").put("actor_id","other-viewer");
        candidate().put("visited_author_before",true);verify();
        candidate().put("visited_category_before",true);assertThatThrownBy(this::verify).hasMessage("FEED_VISIT_visited_category_before");
    }
    @Test void otherAcademiesActorsAndEventKindsAreExcluded() {
        visit("academy",at,at).put("academy_id","other");visit("actor",at,at).put("actor_id","other");
        visit("click",at,at).put("event_type","FEED_CLICK");verify();
    }
    @Test void duplicateEvidenceAndWrongBooleanTypesFailClosed() {
        candidate().put("visited_author_before","false");assertThatThrownBy(this::verify).hasMessage("FEED_VISIT_visited_author_before");
        candidate().put("visited_author_before",false);detail("visit","COMPLETE");detail("visit","COMPLETE");
        assertThatThrownBy(this::verify).hasMessage("FEED_VISIT_DUPLICATE_EVIDENCE");
    }
    @Test void inputVerifierActuallyRejectsForgedVisitSignal() {
        candidate().put("visited_category_before",true);
        assertThatThrownBy(()->SimulationFeedInputVerifier.verify(fixture.request(),fixture.source())).hasMessage("FEED_VISIT_visited_category_before");
    }
}
