package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SimulationAllocationStateTest {
    private static final String DATASET="sha256:"+"c".repeat(64);
    private final UUID account=UUID.randomUUID(),wish=UUID.randomUUID(),root=UUID.randomUUID(),effect=UUID.randomUUID();
    private final Map<String,UUID> ids=Map.of("ACCOUNT:a",account,"WISH:w",wish,"LEDGER_ROOT:deposit",root);
    private SimulationAllocationState.State state(long amount,long delta,long cashDelta,UUID effectAccount) {
        var time=SimulationCashOracle.START;
        return new SimulationAllocationState.State(1,"simulation-allocation-state",DATASET,
            List.of(new SimulationAllocationState.Wish(wish,account,10000,amount,"IN_PROGRESS",time,null,null)),
            List.of(new SimulationAllocationState.Root(root,account,"WISH_DEPOSIT",cashDelta,time,UUID.randomUUID(),null)),
            List.of(new SimulationAllocationState.Effect(effect,root,effectAccount,wish,delta)));
    }
    @Test void reconcilesObservedAllocationWithoutUsingProductionBalanceCalculator() {
        assertThat(SimulationAllocationState.verify(state(4000,4000,0,account),DATASET,ids,SimulationCashOracle.START).allocatedAmount()).isEqualTo(4000);
    }
    @Test void rejectsCacheDriftCashCreationAndCrossAccountEffects() {
        assertThatThrownBy(()->SimulationAllocationState.verify(state(4001,4000,0,account),DATASET,ids,SimulationCashOracle.START)).hasMessage("ALLOCATION_STORED_AMOUNT_MISMATCH");
        assertThatThrownBy(()->SimulationAllocationState.verify(state(4000,4000,1,account),DATASET,ids,SimulationCashOracle.START)).hasMessage("ALLOCATION_ALLOCATION_CHANGED_CASH");
        assertThatThrownBy(()->SimulationAllocationState.verify(state(4000,4000,0,UUID.randomUUID()),DATASET,ids,SimulationCashOracle.START)).hasMessage("ALLOCATION_EFFECT_FOREIGN_KEY");
    }
    @Test void rejectsCoherentlyForgedLedgerAndCacheAgainstOriginalCommandAmount() {
        var actual=state(5000,5000,0,account);
        SimulationAllocationState.verify(actual,DATASET,ids,SimulationCashOracle.START);
        var command=JsonMapper.builder().build().valueToTree(Map.of("eventId","deposit","kind","DEPOSIT",
            "occurredAt",SimulationCashOracle.START.toString(),"outcome",Map.of("status","APPLIED"),
            "command",Map.of("accountId","a","wishId","w","amount",4000)));
        assertThatThrownBy(()->SimulationAllocationState.verifyCommands(actual,ids,List.of(command))).hasMessage("ALLOCATION_COMMAND_AMOUNT");
        var rejected=command.deepCopy();((tools.jackson.databind.node.ObjectNode)rejected.get("outcome")).put("status","REJECTED");
        assertThatThrownBy(()->SimulationAllocationState.verifyCommands(actual,ids,List.of(rejected))).hasMessage("ALLOCATION_REJECTED_COMMAND_ROOT");
        assertThatThrownBy(()->SimulationAllocationState.verifyCommands(actual,ids,List.of())).hasMessage("ALLOCATION_ORPHAN_COMMAND_ROOT");
    }
    @Test void rejectsMissingWishDuplicateEffectAndFutureTimestamp() {
        var good=state(4000,4000,0,account);
        var missing=new SimulationAllocationState.State(1,good.schemaKind(),DATASET,List.of(),good.roots(),good.effects());
        assertThatThrownBy(()->SimulationAllocationState.verify(missing,DATASET,ids,SimulationCashOracle.START)).hasMessage("ALLOCATION_WISH_SET");
        var duplicate=new SimulationAllocationState.State(1,good.schemaKind(),DATASET,good.wishes(),good.roots(),List.of(good.effects().get(0),good.effects().get(0)));
        assertThatThrownBy(()->SimulationAllocationState.verify(duplicate,DATASET,ids,SimulationCashOracle.START)).hasMessage("ALLOCATION_EFFECT_DUPLICATE");
        assertThatThrownBy(()->SimulationAllocationState.verify(good,DATASET,ids,SimulationCashOracle.START.minusSeconds(1))).hasMessage("ALLOCATION_TIME");
    }
}
