package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationProviderReferenceBoundaryIT {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final SimulationRecapDispatcherIT f=new SimulationRecapDispatcherIT();
    private final SimulationStoredReferenceBoundaryIT helpers=new SimulationStoredReferenceBoundaryIT();
    private SimulationCommandDispatcher dispatcher() throws Exception {
        return new SimulationCommandDispatcher(f.schema(),f.DATASET,f.DATASET,f.people());
    }
    private void scenario(SimulationCommandDispatcher d) {
        d.execute(f.event("JOIN",f.START,Map.of("studentId",f.ACTOR,"accountId",f.ACCOUNT,"academyId","academy-1","grade",3),"APPLIED"));
        lookup(d,0,"zero");
        d.execute(f.event("GRANT",f.START.plusSeconds(1),Map.of("accountId",f.ACCOUNT,"amountKrw",20000,"cashEntryId","grant","budgetMonth","2026-06","scheduledAt",f.START.toString()),"APPLIED"));
        lookup(d,1,"grant");
        d.execute(f.event("PURCHASE",f.START.plusSeconds(2),Map.of("accountId",f.ACCOUNT,"cashEntryId","purchase","amountKrw",1000),"APPLIED"));
        lookup(d,3,"purchase");
    }
    private void lookup(SimulationCommandDispatcher d,int at,String name) {
        d.execute(f.event("BALANCE_LOOKUP",f.START.plusSeconds(at),Map.of("accountId",f.ACCOUNT,"observationRef","raw/"+name+".json"),"APPLIED"));
    }
    private SimulationRelationalState.Export observation(SimulationRelationalState.Export e,String suffix,java.util.function.Consumer<ObjectNode> edit) {
        var tables=new TreeMap<>(e.state().tables());var rows=new ArrayList<JsonNode>();boolean found=false;
        for(var row:tables.get("balance_observation")) {
            var copy=(ObjectNode)row.deepCopy();
            if(copy.get("simulation_source_ref").asString().endsWith(":"+suffix)) {edit.accept(copy);found=true;}
            rows.add(copy);
        }
        assertThat(found).isTrue();tables.put("balance_observation",rows);var s=e.state();
        return new SimulationRelationalState.Export(e.catalog(),new SimulationRelationalState.State(1,s.schemaKind(),s.datasetId(),s.catalogDigest(),tables));
    }
    @Test void actualZeroGrantAndPurchaseObservationsBindPrefixInBothDirectionsWithoutWrites() throws Exception {
        try(var d=dispatcher()) {
            scenario(d);var e=d.relationalState();var before=d.preservationFingerprint();String source=JSON.writeValueAsString(e);
            var keys=helpers.all(e);var full=d.inspectSemanticGraphBoundary(e,keys);
            assertThat(full.coveredReferencesClosed()).isTrue();assertThat(full.readyForApplication()).isFalse();
            var observations=d.inspectSemanticGraphBoundary(e,Map.of("balance_observation",keys.get("balance_observation")));
            assertThat(observations.crossings().stream().filter(c->c.reference().equals("simulation_source_ref.cash_sequence"))).hasSize(2);
            assertThat(observations.crossings().stream().filter(c->c.reference().equals("simulation_source_ref.account"))).hasSize(3);
            var cash=d.inspectSemanticGraphBoundary(e,Map.of("demo_simulation_cash_event",keys.get("demo_simulation_cash_event")));
            assertThat(cash.crossings()).anySatisfy(c->{assertThat(c.reference()).isEqualTo("simulation_source_ref.cash_sequence");assertThat(c.direction()).isEqualTo("RETAINED_TO_SELECTED");});
            var purchaseKey=keys.get("demo_simulation_cash_event").stream().filter(k->k.get("event_id").asString().equals("purchase")).toList();
            assertThat(purchaseKey).hasSize(1);
            var prefix=d.inspectSemanticGraphBoundary(e,Map.of("demo_simulation_cash_event",purchaseKey));
            assertThat(prefix.crossings()).anySatisfy(c->{assertThat(c.reference()).isEqualTo("previous_sequence");assertThat(c.direction()).isEqualTo("SELECTED_TO_RETAINED");});
            assertThat(prefix.crossings()).anySatisfy(c->{assertThat(c.reference()).isEqualTo("cash_sequence");assertThat(c.direction()).isEqualTo("RETAINED_TO_SELECTED");});
            assertThat(JSON.writeValueAsString(e)).isEqualTo(source);assertThat(d.preservationFingerprint()).isEqualTo(before);
            var output=Path.of("build/simulation-provider-reference-boundary");Files.createDirectories(output);
            for(var item:Map.of("full",full,"observations",observations,"cash",cash,"prefix",prefix).entrySet())
                Files.writeString(output.resolve(item.getKey()+".json"),JSON.writeValueAsString(item.getValue()));
        }
    }
    @Test void malformedCrossAccountMissingOverflowFutureAndStaleReferencesFailClosed() throws Exception {
        try(var d=dispatcher()) {
            scenario(d);var e=d.relationalState();String prefix="cash:"+d.id("ACCOUNT",f.ACCOUNT)+":";
            for(String ref:List.of("cash:"+UUID.randomUUID()+":2",prefix+"02",prefix+"-1",prefix+"2:extra","external:2")) {
                var bad=observation(e,"2",r->r.put("simulation_source_ref",ref));
                assertThatThrownBy(()->d.inspectSemanticGraphBoundary(bad,Map.of())).hasMessage("SEMANTIC_GRAPH_PROVIDER_REFERENCE");
            }
            for(String seq:List.of("3","9223372036854775808")) {
                var bad=observation(e,"2",r->r.put("simulation_source_ref",prefix+seq));
                assertThatThrownBy(()->d.inspectSemanticGraphBoundary(bad,Map.of())).hasMessage("SEMANTIC_GRAPH_PROVIDER_SEQUENCE");
            }
            // Return to the prior balance so a stale source remains numerically plausible.
            d.execute(f.event("GRANT",f.START.plusSeconds(4),Map.of("accountId",f.ACCOUNT,"amountKrw",1000,"cashEntryId","second-grant","budgetMonth","2026-06","scheduledAt",f.START.toString()),"APPLIED"));
            lookup(d,5,"second-grant");lookup(d,7,"later");var later=d.relationalState();
            var tables=new TreeMap<>(later.state().tables());var changed=new ArrayList<JsonNode>();
            for(var cash:tables.get("demo_simulation_cash_event")) {
                var copy=(ObjectNode)cash.deepCopy();if(copy.get("sequence").asLong()==3)copy.put("occurred_at",f.START.plusSeconds(6).toString());changed.add(copy);
            }
            tables.put("demo_simulation_cash_event",changed);var state=later.state();
            var future=new SimulationRelationalState.Export(later.catalog(),new SimulationRelationalState.State(1,state.schemaKind(),state.datasetId(),state.catalogDigest(),tables));
            assertThatThrownBy(()->d.inspectSemanticGraphBoundary(future,Map.of())).hasMessage("SEMANTIC_GRAPH_PROVIDER_TIME");
            var stale=observation(later,"3",r->r.put("simulation_source_ref",prefix+"1"));
            assertThatThrownBy(()->d.inspectSemanticGraphBoundary(stale,Map.of())).hasMessage("SEMANTIC_GRAPH_PROVIDER_STALE_SEQUENCE");
            var balance=observation(e,"2",r->r.put("actual_card_balance",19001));
            assertThatThrownBy(()->d.inspectSemanticGraphBoundary(balance,Map.of())).hasMessage("SEMANTIC_GRAPH_PROVIDER_BALANCE");
            assertThat(d.inspectSemanticGraphBoundary(later,helpers.all(later)).coveredReferencesClosed()).isTrue();
        }
    }
    @Test void sameInstantCashAfterZeroObservationIsValidAndFailedLookupClaimsNoCash() throws Exception {
        try(var d=dispatcher()) {
            d.execute(f.event("JOIN",f.START,Map.of("studentId",f.ACTOR,"accountId",f.ACCOUNT,"academyId","academy-1","grade",3),"APPLIED"));
            lookup(d,0,"zero");
            d.execute(f.event("GRANT",f.START,Map.of("accountId",f.ACCOUNT,"amountKrw",10000,"cashEntryId","same-time","budgetMonth","2026-06","scheduledAt",f.START.toString()),"APPLIED"));
            lookup(d,0,"after");var e=d.relationalState();
            assertThat(d.inspectSemanticGraphBoundary(e,helpers.all(e)).coveredReferencesClosed()).isTrue();
            // Existing failure representation must never be relabeled as a successful simulation observation.
            var failed=observation(e,"0",r->{r.put("status","FAILED");r.put("source_kind","PROVIDER");r.putNull("simulation_dataset_id");r.putNull("simulation_source_ref");});
            assertThat(SimulationSemanticGraphBoundary.inspect(failed,Map.of()).crossings()).isEmpty();
            var forged=observation(e,"0",r->r.put("status","FAILED"));
            assertThatThrownBy(()->SimulationSemanticGraphBoundary.inspect(forged,Map.of())).hasMessage("SEMANTIC_GRAPH_FAILED_PROVIDER_SOURCE");
        }
    }
}
