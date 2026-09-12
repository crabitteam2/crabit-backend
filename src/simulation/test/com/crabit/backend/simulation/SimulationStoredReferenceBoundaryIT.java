package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationStoredReferenceBoundaryIT {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @TempDir Path temp;
    SimulationRecapDispatcherIT fixture() {var f=new SimulationRecapDispatcherIT();f.temp=temp;return f;}
    void wishes(SimulationRecapDispatcherIT f,SimulationCommandDispatcher d) {
        f.seed(d);
        for(int i=0;i<2;i++) {
            var c=new HashMap<String,Object>(Map.of("accountId",f.ACCOUNT,"wishId",i==0?"wish":"destination","idempotencyKey","create-"+i,"purpose","goal "+i,"targetAmount",10000));
            c.put("startDate",null);c.put("targetDate",null);c.put("photoId",null);
            d.execute(f.event("CREATE",f.START.plusSeconds(2+i),c,"APPLIED"));
        }
        f.deposit(d,f.START.plusSeconds(4),0,4000);
        d.execute(f.event("TRANSFER",f.START.plusSeconds(5),Map.of("accountId",f.ACCOUNT,"sourceWishId","wish","destinationWishId","destination","amount",1000,
            "sourceExpectedVersion",1,"destinationExpectedVersion",0,"idempotencyKey","transfer","rootEventId","transfer-root","sourceEffectId","transfer-from","destinationEffectId","transfer-to"),"APPLIED"));
    }
    Map<String,List<JsonNode>> all(SimulationRelationalState.Export e) {
        var result=new TreeMap<String,List<JsonNode>>();
        for(var k:e.catalog().keys())if(k.primary()) {
            var keys=new ArrayList<JsonNode>();for(JsonNode row:e.state().tables().get(k.table())) {
                var key=JSON.createObjectNode();for(String c:k.columns())key.set(c,row.get(c));keys.add(key);
            }result.put(k.table(),keys);
        }return result;
    }
    SimulationRelationalState.Export change(SimulationRelationalState.Export e,String table,java.util.function.Consumer<ObjectNode> edit) {
        var rows=new TreeMap<>(e.state().tables());var values=new ArrayList<>(rows.get(table));
        var row=(ObjectNode)values.getFirst().deepCopy();edit.accept(row);values.set(0,row);rows.put(table,values);
        var s=e.state();return new SimulationRelationalState.Export(e.catalog(),new SimulationRelationalState.State(1,s.schemaKind(),s.datasetId(),s.catalogDigest(),rows));
    }
    @Test void actualTransferAndPythonRecapReferencesCrossBothDirectionsWithoutChangingSourceOrDatabase() throws Exception {
        var f=fixture();try(var python=f.python();var d=f.dispatcher(python.endpoint())) {
            wishes(f,d);d.execute(f.close("CLOSE_WEEK","week",f.WEEK,f.ACCOUNT,"APPLIED"));
            var e=d.relationalState();var before=d.preservationFingerprint();String source=JSON.writeValueAsString(e);
            var keys=all(e);var full=d.inspectSemanticGraphBoundary(e,keys);
            assertThat(full.coveredReferencesClosed()).isTrue();assertThat(full.readyForApplication()).isFalse();
            var student=d.inspectSemanticGraphBoundary(e,Map.of("student",keys.get("student")));
            assertThat(student.crossings()).anySatisfy(c->{assertThat(c.reference()).isEqualTo("wish_idempotency_records.destinationSnapshot.id");assertThat(c.target()).isEqualTo("wish");});
            assertThat(student.crossings()).anySatisfy(c->{assertThat(c.reference()).isEqualTo("wish_idempotency_records.eventId");assertThat(c.target()).isEqualTo("ledger_event");});
            var recap=d.inspectSemanticGraphBoundary(e,Map.of("recap_generation",keys.get("recap_generation")));
            assertThat(recap.crossings()).anySatisfy(c->{assertThat(c.reference()).isEqualTo("request_json.input.effective_transactions[].root_event_id");assertThat(c.target()).isEqualTo("ledger_event");});
            assertThat(recap.crossings()).anySatisfy(c->{assertThat(c.reference()).isEqualTo("internal_metrics_json.account_id");assertThat(c.target()).isEqualTo("card_balance_account");});
            var ledger=d.inspectSemanticGraphBoundary(e,Map.of("ledger_event",keys.get("ledger_event")));
            assertThat(ledger.crossings()).anySatisfy(c->{assertThat(c.table()).isEqualTo("recap_generation");assertThat(c.direction()).isEqualTo("RETAINED_TO_SELECTED");});
            assertThat(JSON.writeValueAsString(e)).isEqualTo(source);assertThat(d.preservationFingerprint()).isEqualTo(before);
            var output=Path.of("build/simulation-stored-reference-boundary");Files.createDirectories(output);
            for(var entry:Map.of("full",full,"student",student,"recap",recap,"ledger",ledger).entrySet())Files.writeString(output.resolve(entry.getKey()+".json"),JSON.writeValueAsString(entry.getValue()));
        }
    }
    @Test void absentWrongTypedAndMalformedStoredReferencesFailClosed() throws Exception {
        var f=fixture();try(var python=f.python();var d=f.dispatcher(python.endpoint())) {
            wishes(f,d);d.execute(f.close("CLOSE_WEEK","week",f.WEEK,f.ACCOUNT,"APPLIED"));var e=d.relationalState();
            for(String field:List.of("targetId","eventId")) {
                var corrupt=change(e,"student",r->((ObjectNode)r.get("wish_idempotency_records").iterator().next()).put(field,UUID.randomUUID().toString()));
                assertThatThrownBy(()->d.inspectSemanticGraphBoundary(corrupt,Map.of())).hasMessage("SEMANTIC_GRAPH_REFERENCE_MISSING");
            }
            var missingSnapshot=change(e,"student",r->((ObjectNode)r.get("wish_idempotency_records").iterator().next().get("snapshot")).putNull("id"));
            assertThatThrownBy(()->d.inspectSemanticGraphBoundary(missingSnapshot,Map.of())).hasMessage("SEMANTIC_GRAPH_REFERENCE_REQUIRED");
            for(String field:List.of("request_json","view_json","internal_metrics_json")) {
                var unknown=change(e,"recap_generation",r->r.put(field,"{\"wish_id\":\""+UUID.randomUUID()+"\"}"));
                assertThatThrownBy(()->d.inspectSemanticGraphBoundary(unknown,Map.of())).hasMessage("SEMANTIC_GRAPH_REFERENCE_MISSING");
                var badType=change(e,"recap_generation",r->r.put(field,"{\"wish_id\":7}"));
                assertThatThrownBy(()->d.inspectSemanticGraphBoundary(badType,Map.of())).hasMessage("SEMANTIC_GRAPH_REFERENCE_TYPE");
                for(String invalid:List.of("{","{\"wish_id\":null,\"wish_id\":null}","{} {}")) {
                    var malformed=change(e,"recap_generation",r->r.put(field,invalid));
                    assertThatThrownBy(()->d.inspectSemanticGraphBoundary(malformed,Map.of())).hasMessage("SEMANTIC_GRAPH_RECAP_JSON_INVALID");
                }
            }
            assertThat(d.inspectSemanticGraphBoundary(e,all(e)).coveredReferencesClosed()).isTrue();
        }
    }
}
