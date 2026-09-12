package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationDormancyVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    final Map<String,JsonNode> commands=new LinkedHashMap<>();
    final Map<String,SimulationCommandDispatcher.Result> results=new LinkedHashMap<>();
    ObjectNode event(String id,String kind,long seq,long seconds) {
        var e=JSON.createObjectNode().put("eventId",id).put("kind",kind).put("sequence",seq)
            .put("actorStudentId","student").put("occurredAt",SimulationCashOracle.START.plusSeconds(seconds).toString());
        e.putObject("command");e.putArray("causes");e.putObject("outcome").put("status","APPLIED");
        commands.put(id,e);results.put(id,new SimulationCommandDispatcher.Result(id,"APPLIED",null,"{}".getBytes()));return e;
    }
    ObjectNode returning(long seconds) {
        event("anchor","JOIN",1,0);var e=event("return","RETURN_FROM_DORMANCY",4,seconds);
        e.putObject("command").put("priorDormancyEventId","anchor");e.putArray("causes").add("anchor");return e;
    }
    @Test void passiveCashAndOtherStudentsDoNotCountAsAppVisitsAndReceiptIsReconciled() {
        var e=returning(14*86400);event("grant","GRANT",2,86400);event("other","FEED_QUERY",3,86401).put("actorStudentId","another");
        var receipt=SimulationDormancyVerifier.receipt(e,commands,results);
        assertThat(receipt.get("inactiveDuration").asString()).isEqualTo("PT336H");
        assertThat(receipt.get("domainMutationPerformed").booleanValue()).isFalse();
        var ordered=commands.values().stream().sorted(Comparator.comparingLong(n->n.get("sequence").longValue())).toList();
        results.put("return",new SimulationCommandDispatcher.Result("return","APPLIED",null,JSON.writeValueAsBytes(receipt)));
        assertThat(SimulationDormancyVerifier.verify(ordered,new ArrayList<>(results.values()))).isEqualTo(1);
        ((ObjectNode)receipt).put("inactiveDuration","PT0S");
        results.put("return",new SimulationCommandDispatcher.Result("return","APPLIED",null,JSON.writeValueAsBytes(receipt)));
        assertThatThrownBy(()->SimulationDormancyVerifier.verify(ordered,new ArrayList<>(results.values()))).hasMessage("DORMANCY_RECEIPT_MISMATCH");
    }
    @Test void exactSevenAndTwentyOneDayBoundariesAreInclusive() {
        for(int days:List.of(7,21)) {
            var t=new SimulationDormancyVerifierTest();var e=t.returning(days*86400);
            assertThat(SimulationDormancyVerifier.receipt(e,t.commands,t.results).get("inactiveDuration").asString()).isEqualTo(Duration.ofDays(days).toString());
        }
        for(String at:List.of(SimulationCashOracle.START.plus(Duration.ofDays(7)).minusNanos(1000).toString(),
                SimulationCashOracle.START.plus(Duration.ofDays(21)).plusNanos(1000).toString())) {
            var t=new SimulationDormancyVerifierTest();var e=t.returning(7*86400).put("occurredAt",at);
            assertThatThrownBy(()->SimulationDormancyVerifier.receipt(e,t.commands,t.results)).hasMessage("DORMANCY_DURATION");
        }
    }
    @TestFactory List<DynamicTest> rejectsForgedAnchorsAndHiddenAttempts() {
        Map<String,java.util.function.Consumer<SimulationDormancyVerifierTest>> cases=new LinkedHashMap<>();
        cases.put("ANCHOR_MISSING",t->t.commands.remove("anchor"));
        cases.put("ACTOR",t->((ObjectNode)t.commands.get("anchor")).put("actorStudentId","other"));
        cases.put("PASSIVE_ANCHOR",t->((ObjectNode)t.commands.get("anchor")).put("kind","GRANT"));
        cases.put("ANCHOR_ORDER",t->((ObjectNode)t.commands.get("anchor")).put("sequence",5));
        cases.put("CAUSE",t->((ObjectNode)t.commands.get("return")).putArray("causes"));
        cases.put("ACTUAL_ANCHOR_RESULT",t->t.results.remove("anchor"));
        cases.put("INTERVENING_APP_ACTIVITY",t->{var attempted=t.event("attempt","DEPOSIT",2,86400);((ObjectNode)attempted.get("outcome")).put("status","REJECTED");});
        return cases.entrySet().stream().map(c->DynamicTest.dynamicTest(c.getKey(),()->{
            var t=new SimulationDormancyVerifierTest();var e=t.returning(7*86400);c.getValue().accept(t);
            assertThatThrownBy(()->SimulationDormancyVerifier.receipt(e,t.commands,t.results)).hasMessage("DORMANCY_"+c.getKey());
        })).toList();
    }
}
