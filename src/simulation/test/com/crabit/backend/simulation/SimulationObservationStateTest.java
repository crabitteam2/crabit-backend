package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationObservationStateTest {
    private final UUID account=UUID.randomUUID(),first=UUID.randomUUID(),failure=UUID.randomUUID(),last=UUID.randomUUID(),root=UUID.randomUUID();
    private final String dataset="sha256:"+"c".repeat(64);
    private final java.time.Instant start=SimulationCashOracle.START;
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private SimulationObservationState.State state() {
        return new SimulationObservationState.State(1,"simulation-observation-state",dataset,
            List.of(new SimulationObservationState.Account(account,3)),List.of(
                new SimulationObservationState.Observation(first,account,"SUCCEEDED","PRE_DEPOSIT",1000L,null,1L,true,null,0L,root,start.plusSeconds(1),"SIMULATION",dataset,"cash:"+account+":1"),
                new SimulationObservationState.Observation(failure,account,"FAILED","USER_REQUESTED",null,"BALANCE_SYNC_FAILED",2L,null,null,null,null,start.plusSeconds(2),"PROVIDER",null,null),
                new SimulationObservationState.Observation(last,account,"SUCCEEDED","USER_REQUESTED",1000L,null,3L,null,first,1000L,null,start.plusSeconds(3),"SIMULATION",dataset,"cash:"+account+":1")),
            List.of(new SimulationObservationState.Cash(account,1,"GRANT",1000,start)));
    }
    private SimulationAllocationState.State allocation() {
        return new SimulationAllocationState.State(1,"simulation-allocation-state",dataset,List.of(),
            List.of(new SimulationAllocationState.Root(root,account,"CARD_BALANCE_CHANGE",1000,start.plusSeconds(1),null,null)),List.of());
    }
    private SimulationObservationState.Verification verify(SimulationObservationState.State s) {
        return SimulationObservationState.verify(s,allocation(),dataset,Map.of("ACCOUNT:a",account,"BALANCE_OBSERVATION:lookup",last),start.plusSeconds(4));
    }
    private SimulationObservationState.State mutate(int row,String field,Object value) {
        ObjectNode tree=json.valueToTree(state());ObjectNode o=(ObjectNode)tree.get("observations").get(row);
        o.set(field,json.valueToTree(value));return json.treeToValue(tree,SimulationObservationState.State.class);
    }
    @Test void failuresDoNotReplaceSuccessfulChainAndZeroDeltaDoesNotInventRoot() {
        assertThat(verify(state())).isEqualTo(new SimulationObservationState.Verification(1,3,2,1,0,1));
        assertThatThrownBy(()->verify(mutate(2,"previousId",failure))).hasMessage("OBSERVATION_SUCCESS_CHAIN");
        assertThatThrownBy(()->verify(mutate(2,"changeEventId",root))).hasMessage("OBSERVATION_ZERO_CHANGE");
        assertThatThrownBy(()->verify(mutate(1,"balance",0))).hasMessage("OBSERVATION_FAILED_SHAPE");
    }
    @Test void forgedSimulationSourceAndCoherentlyChangedBalanceCannotPassCashProof() {
        assertThatThrownBy(()->verify(mutate(0,"sourceKind","PROVIDER"))).hasMessage("OBSERVATION_SOURCE");
        assertThatThrownBy(()->verify(mutate(0,"datasetId","sha256:"+"d".repeat(64)))).hasMessage("OBSERVATION_SOURCE");
        assertThatThrownBy(()->verify(mutate(0,"sourceRef","cash:"+UUID.randomUUID()+":1"))).hasMessage("OBSERVATION_SOURCE_REF");
        assertThatThrownBy(()->verify(mutate(0,"balance",999))).hasMessage("OBSERVATION_SOURCE_BALANCE");
        assertThatThrownBy(()->verify(mutate(0,"sourceRef","cash:"+account+":2"))).hasMessage("OBSERVATION_SOURCE_BALANCE");
    }
    @Test void duplicateMissingFutureAndCrossAccountObservationsAreRejected() {
        assertThatThrownBy(()->verify(mutate(2,"id",first))).hasMessage("OBSERVATION_OBSERVATION_IDENTITY");
        assertThatThrownBy(()->verify(mutate(2,"lookupVersion",4))).hasMessage("OBSERVATION_LOOKUP_SEQUENCE");
        assertThatThrownBy(()->verify(mutate(2,"observedAt",start.plusSeconds(5)))).hasMessage("OBSERVATION_TIME");
        assertThatThrownBy(()->verify(mutate(2,"accountId",UUID.randomUUID()))).hasMessage("OBSERVATION_OBSERVATION_IDENTITY");
        var s=state();
        assertThatThrownBy(()->verify(new SimulationObservationState.State(1,s.schemaKind(),dataset,s.accounts(),s.observations().subList(0,2),s.cash())))
            .hasMessage("OBSERVATION_ACCOUNT_LOOKUP_VERSION");
    }
    @Test void rootsAndDepositProofMustResolveToActualObservationAccountTimeAndMethod() {
        var s=state();var a=allocation();
        var deposit=new SimulationAllocationState.Root(UUID.randomUUID(),account,"WISH_DEPOSIT",0,start.plusSeconds(1),first,null);
        var roots=new ArrayList<>(a.roots());roots.add(deposit);
        var joined=new SimulationAllocationState.State(1,a.schemaKind(),dataset,List.of(),roots,List.of());
        assertThat(SimulationObservationState.verify(s,joined,dataset,Map.of("ACCOUNT:a",account),start.plusSeconds(4)).depositLinks()).isEqualTo(1);
        assertThatThrownBy(()->SimulationObservationState.verify(mutate(0,"lookupMethod","USER_REQUESTED"),joined,dataset,Map.of("ACCOUNT:a",account),start.plusSeconds(4)))
            .hasMessage("OBSERVATION_DEPOSIT_LINK");
        assertThatThrownBy(()->SimulationObservationState.verify(s,new SimulationAllocationState.State(1,a.schemaKind(),dataset,List.of(),List.of(),List.of()),dataset,Map.of("ACCOUNT:a",account),start.plusSeconds(4)))
            .hasMessage("OBSERVATION_CHANGE_LINK");
    }
    @Test void cashFromTheFutureOrOlderSequenceCannotExplainObservation() {
        var s=state();
        var future=new SimulationObservationState.State(1,s.schemaKind(),dataset,s.accounts(),s.observations(),
            List.of(new SimulationObservationState.Cash(account,1,"GRANT",1000,start.plusSeconds(2))));
        assertThatThrownBy(()->verify(future)).hasMessage("OBSERVATION_SOURCE_TIME");
        var cash=new ArrayList<>(s.cash());cash.add(new SimulationObservationState.Cash(account,2,"GRANT",1,start));
        assertThatThrownBy(()->verify(new SimulationObservationState.State(1,s.schemaKind(),dataset,s.accounts(),s.observations(),cash)))
            .hasMessage("OBSERVATION_STALE_SOURCE");
    }
}
