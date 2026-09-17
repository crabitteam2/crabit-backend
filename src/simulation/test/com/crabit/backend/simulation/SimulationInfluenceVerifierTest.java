package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationInfluenceVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    final Map<String,JsonNode> commands=new LinkedHashMap<>();
    final Map<String,SimulationCommandDispatcher.Result> results=new LinkedHashMap<>();
    ObjectNode add(String id,String kind,int seq,Map<String,?> command) {
        var e=JSON.createObjectNode().put("eventId",id).put("kind",kind).put("sequence",seq)
            .put("actorStudentId","viewer").put("occurredAt",SimulationCashOracle.START.plusSeconds(seq).toString());
        e.set("command",JSON.valueToTree(command));e.putArray("causes");e.putObject("outcome").put("status","APPLIED");commands.put(id,e);
        var body=JSON.createObjectNode().put("eventType",kind.equals("IMPRESSION")?"FEED_EXPOSURE":kind.equals("CLICK")?"FEED_CLICK":kind)
            .put("occurredAt",e.get("occurredAt").asString());
        results.put(id,new SimulationCommandDispatcher.Result(id,"APPLIED",null,JSON.writeValueAsBytes(Map.of("body",body))));return e;
    }
    Map<String,Object> feed() { return Map.of("academyId","academy","resultContextId","page","cardId","share","position",0,"impressionId","seen"); }
    ObjectNode annotation(String signal,String type) {
        var e=add("annotation","INFLUENCED_DECISION",5,Map.of("signalType",type,"signalEventId",signal,"decisionEventId","decision"));
        e.putArray("causes").add(signal).add("decision");return e;
    }
    void base() { add("seen","IMPRESSION",1,feed());add("clicked","CLICK",2,feed());add("decision","CREATE",4,Map.of()); }
    @Test void acceptsActualExposureAndPreservesRejectedDecisionAsAttempt() {
        base();var e=annotation("clicked","CLICK");var before=e.deepCopy();
        ((ObjectNode)commands.get("decision").get("outcome")).put("status","REJECTED");
        results.put("decision",new SimulationCommandDispatcher.Result("decision","REJECTED","INVALID_STATE",new byte[]{123,125}));
        var receipt=SimulationInfluenceVerifier.receipt(e,commands,results);
        assertThat(receipt.get("decisionStatus").asString()).isEqualTo("REJECTED");assertThat(receipt.get("exposureEventId").asString()).isEqualTo("seen");
        assertThat(e).isEqualTo(before);
        results.put("annotation",new SimulationCommandDispatcher.Result("annotation","APPLIED",null,JSON.writeValueAsBytes(receipt)));
        assertThat(SimulationInfluenceVerifier.verify(new ArrayList<>(commands.values()),new ArrayList<>(results.values()))).isEqualTo(1);
        ((ObjectNode)receipt).put("decisionStatus","APPLIED");results.put("annotation",new SimulationCommandDispatcher.Result("annotation","APPLIED",null,JSON.writeValueAsBytes(receipt)));
        assertThatThrownBy(()->SimulationInfluenceVerifier.verify(new ArrayList<>(commands.values()),new ArrayList<>(results.values()))).hasMessage("INFLUENCE_RECEIPT_MISMATCH");
    }
    @Test void rejectsUnmatchedClickAndExposureRecordedAfterClick() {
        base();var e=annotation("clicked","CLICK");commands.remove("seen");
        assertThatThrownBy(()->SimulationInfluenceVerifier.receipt(e,commands,results)).hasMessage("INFLUENCE_NO_PRIOR_EXPOSURE");
        add("seen","IMPRESSION",3,feed());
        assertThatThrownBy(()->SimulationInfluenceVerifier.receipt(e,commands,results)).hasMessage("INFLUENCE_NO_PRIOR_EXPOSURE");
    }
    @TestFactory List<DynamicTest> rejectsWrongScopeOrderKindAndActualReceipt() {
        Map<String,java.util.function.Consumer<SimulationInfluenceVerifierTest>> cases=new LinkedHashMap<>();
        cases.put("ACTOR",t->((ObjectNode)t.commands.get("seen")).put("actorStudentId","other"));
        cases.put("REFERENCE_ORDER",t->((ObjectNode)t.commands.get("seen")).put("sequence",8));
        cases.put("DECISION_KIND",t->((ObjectNode)t.commands.get("decision")).put("kind","GRANT"));
        cases.put("ACTUAL_RESULT",t->t.results.remove("seen"));
        cases.put("SIGNAL_RECEIPT",t->t.results.put("seen",new SimulationCommandDispatcher.Result("seen","APPLIED",null,"{}".getBytes())));
        return cases.entrySet().stream().map(c->DynamicTest.dynamicTest(c.getKey(),()->{
            var t=new SimulationInfluenceVerifierTest();t.base();var e=t.annotation("seen","IMPRESSION");c.getValue().accept(t);
            assertThatThrownBy(()->SimulationInfluenceVerifier.receipt(e,t.commands,t.results)).hasMessage("INFLUENCE_"+c.getKey());
        })).toList();
    }
    @Test void profileSignalRequiresAnExposureChainForTheSameAuthor() {
        base();var share=add("share","SHARE",0,Map.of());share.put("actorStudentId","author");
        var visit=add("visit","PROFILE_VISIT",3,Map.of("academyId","academy","targetStudentId","author","source","FEED","sourceEventId","clicked"));
        visit.putArray("causes").add("clicked");var e=annotation("visit","PROFILE_VISIT");
        assertThat(SimulationInfluenceVerifier.receipt(e,commands,results).get("exposureEventId").asString()).isEqualTo("seen");
        ((ObjectNode)visit.get("command")).put("targetStudentId","someone-else");
        assertThatThrownBy(()->SimulationInfluenceVerifier.receipt(e,commands,results)).hasMessage("INFLUENCE_PROFILE_TARGET");
    }
}
