package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationSemanticGraphBoundaryIT {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String DATASET=SimulationFeedReplayIT.DATASET;
    private SimulationCommandDispatcher replay() throws Exception {
        var f=new SimulationFeedReplayIT();
        var d=new SimulationCommandDispatcher(f.schema(),DATASET,DATASET,f.people());
        try {
            for(byte[] bytes:f.events(true))d.execute(JSON.readTree(bytes));
            var commands=new SimulationFeedContinuationIT();
            var visit=new HashMap<String,Object>();visit.put("academyId","academy-1");visit.put("targetStudentId","student-3-01");
            visit.put("source","DIRECT");visit.put("sourceEventId",null);
            d.execute(commands.event(10,"PROFILE_VISIT","student-3-00",8,visit));
            d.execute(commands.event(11,"VISIBILITY_CHANGE","student-3-01",9,Map.of("accountId","account-3-01","wishId","wish-1","expectedVersion",1,"visibility","PRIVATE")));
            d.execute(commands.event(12,"GRANT","student-3-01",10,Map.of("accountId","account-3-01","cashEntryId","cash-1","amountKrw",10000,"budgetMonth","2026-06","scheduledAt",SimulationCashOracle.START.toString())));
            d.execute(commands.event(13,"DEPOSIT","student-3-01",11,Map.of("accountId","account-3-01","wishId","wish-0","amount",2000,"expectedVersion",1,"idempotencyKey","deposit")));
            return d;
        }catch(Exception e){d.close();throw e;}
    }
    private Map<String,List<JsonNode>> all(SimulationRelationalState.Export e) {
        var result=new TreeMap<String,List<JsonNode>>();
        for(var k:e.catalog().keys())if(k.primary()) {
            var keys=new ArrayList<JsonNode>();for(JsonNode row:e.state().tables().get(k.table())) {
                var key=JSON.createObjectNode();for(String c:k.columns())key.set(c,row.get(c));keys.add(key);
            }result.put(k.table(),keys);
        }return result;
    }
    private SimulationRelationalState.Export change(SimulationRelationalState.Export e,Consumer<Map<String,List<JsonNode>>> change) {
        var rows=new TreeMap<String,List<JsonNode>>();e.state().tables().forEach((t,r)->rows.put(t,new ArrayList<>(r.stream().map(JsonNode::deepCopy).toList())));
        change.accept(rows);var state=e.state();return new SimulationRelationalState.Export(e.catalog(),
            new SimulationRelationalState.State(1,state.schemaKind(),state.datasetId(),state.catalogDigest(),rows));
    }
    @Test void historicalCardsVisitsPageArraysAndCheckpointReferencesCrossExactCutsWithoutMutatingEvidence() throws Exception {
        try(var d=replay()) {
            var e=d.relationalState();var before=d.preservationFingerprint();String original=JSON.writeValueAsString(e);
            var keys=all(e);var full=d.inspectSemanticGraphBoundary(e,keys);
            assertThat(full.coveredReferencesClosed()).isTrue();assertThat(full.checkedReferences()).isGreaterThan(30);
            assertThat(full.readyForApplication()).isFalse();assertThat(full.remainingCoverage()).isNotEmpty();
            var visit=d.inspectSemanticGraphBoundary(e,Map.of("feed_visit_evidence",keys.get("feed_visit_evidence")));
            assertThat(visit.foreignKeys().foreignKeyClosed()).isTrue();assertThat(visit.coveredReferencesClosed()).isFalse();
            assertThat(visit.crossings()).anySatisfy(c->{assertThat(c.reference()).isEqualTo("source_versions.history");assertThat(c.target()).isEqualTo("feed_source_history");});
            assertThat(visit.crossings()).anySatisfy(c->{assertThat(c.reference()).isEqualTo("actor_id,event_id");assertThat(c.target()).isEqualTo("behavior_event");});
            var history=d.inspectSemanticGraphBoundary(e,Map.of("feed_source_history",keys.get("feed_source_history")));
            assertThat(history.crossings()).anySatisfy(c->{assertThat(c.table()).isEqualTo("feed_visit_evidence");assertThat(c.direction()).isEqualTo("RETAINED_TO_SELECTED");});
            assertThat(history.crossings()).anySatisfy(c->{assertThat(c.reference()).isEqualTo("card_id.history");assertThat(c.table()).isEqualTo("behavior_result_item");});
            var checkpoints=d.inspectSemanticGraphBoundary(e,Map.of("historical_balance_checkpoint",keys.get("historical_balance_checkpoint")));
            assertThat(checkpoints.crossings()).anySatisfy(c->assertThat(c.reference()).isEqualTo("active_wishes.wishId"));
            var page=d.inspectSemanticGraphBoundary(e,Map.of("feed_page_state",keys.get("feed_page_state")));
            assertThat(page.crossings()).anySatisfy(c->assertThat(c.reference()).isEqualTo("returned_card_ids.history"));
            var reversed=new TreeMap<String,List<JsonNode>>();keys.forEach((t,rows)->{var r=new ArrayList<>(rows);Collections.reverse(r);reversed.put(t,r);});
            assertThat(d.inspectSemanticGraphBoundary(e,reversed)).isEqualTo(full);
            assertThat(JSON.writeValueAsString(visit)).doesNotContain(e.state().tables().get("student").getFirst().get("id").asString());
            assertThat(JSON.writeValueAsString(e)).isEqualTo(original);assertThat(d.preservationFingerprint()).isEqualTo(before);
            Path out=Path.of("build/simulation-semantic-graph");Files.createDirectories(out);
            Files.writeString(out.resolve("visit-cut.json"),JSON.writeValueAsString(visit));
            Files.writeString(out.resolve("history-cut.json"),JSON.writeValueAsString(history));
            Files.writeString(out.resolve("page-cut.json"),JSON.writeValueAsString(page));
            Files.writeString(out.resolve("full-cut.json"),JSON.writeValueAsString(full));
        }
    }
    @Test void danglingWrongTypedAndForgedNonForeignKeyReferencesFailClosed() throws Exception {
        try(var d=replay()) {
            var e=d.relationalState();
            var unknownCard=change(e,rows->((ObjectNode)rows.get("feed_page_state").getFirst()).putArray("returned_card_ids").add(UUID.randomUUID().toString()));
            assertThat(d.verifyRelationalState(unknownCard).tables()).isEqualTo(38);
            assertThatThrownBy(()->d.inspectSemanticGraphBoundary(unknownCard,Map.of())).hasMessage("SEMANTIC_GRAPH_REFERENCE_MISSING");
            var wrongType=change(e,rows->((ObjectNode)rows.get("feed_page_state").getFirst()).putArray("returned_card_ids").add(123));
            assertThatThrownBy(()->d.inspectSemanticGraphBoundary(wrongType,Map.of())).hasMessage("SEMANTIC_GRAPH_CARD_ID");
            var wrongEvent=change(e,rows->((ObjectNode)rows.get("feed_visit_evidence").getFirst()).put("event_id",UUID.randomUUID().toString()));
            assertThatThrownBy(()->d.inspectSemanticGraphBoundary(wrongEvent,Map.of())).hasMessage("SEMANTIC_GRAPH_VISIT_EVENT");
            var wrongVersion=change(e,rows->((ObjectNode)rows.get("feed_visit_evidence").getFirst()).putArray("source_versions").add("wish:99999999"));
            assertThatThrownBy(()->d.inspectSemanticGraphBoundary(wrongVersion,Map.of())).hasMessage("SEMANTIC_GRAPH_SOURCE_VERSION_TARGET");
            var wrongHigh=change(e,rows->((ObjectNode)rows.get("feed_visit_evidence").getFirst()).putArray("source_versions").add("history:99999999"));
            assertThatThrownBy(()->d.inspectSemanticGraphBoundary(wrongHigh,Map.of())).hasMessage("SEMANTIC_GRAPH_HISTORY_HIGH_WATER");
            var wrongWish=change(e,rows->{var row=rows.get("historical_balance_checkpoint").stream().filter(r->!r.get("active_wishes").isEmpty()).findFirst().orElseThrow();
                ((ObjectNode)row.get("active_wishes").get(0)).put("wishId",UUID.randomUUID().toString());});
            assertThatThrownBy(()->d.inspectSemanticGraphBoundary(wrongWish,Map.of())).hasMessage("SEMANTIC_GRAPH_REFERENCE_MISSING");
            var keys=all(e);assertThat(d.inspectSemanticGraphBoundary(e,keys).coveredReferencesClosed()).isTrue();
        }
    }
}
