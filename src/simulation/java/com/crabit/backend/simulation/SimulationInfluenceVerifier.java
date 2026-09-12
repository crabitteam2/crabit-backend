package com.crabit.backend.simulation;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Records a synthetic causal claim only after its referenced real commands completed. */
public final class SimulationInfluenceVerifier {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private SimulationInfluenceVerifier() {}

    public static JsonNode receipt(JsonNode annotation,Map<String,JsonNode> prior,
                                   Map<String,SimulationCommandDispatcher.Result> results) {
        JsonNode c=annotation.get("command");
        JsonNode signal=earlier(annotation,s(c,"signalEventId"),prior,results);
        JsonNode decision=earlier(annotation,s(c,"decisionEventId"),prior,results);
        check(causes(annotation,s(signal,"eventId")) && causes(annotation,s(decision,"eventId")),"CAUSE");
        check(s(signal,"kind").equals(s(c,"signalType")),"SIGNAL_TYPE");
        check(Set.of("CREATE","DEPOSIT").contains(s(decision,"kind")),"DECISION_KIND");
        check(before(signal,decision),"SIGNAL_ORDER");
        JsonNode exposure=exposure(signal,prior,results,new HashSet<>());
        check(before(exposure,decision),"EXPOSURE_ORDER");
        var receipt=JSON.createObjectNode();
        receipt.put("schemaVersion",1);receipt.put("schemaKind","simulation-influence-annotation");
        receipt.put("signalEventId",s(signal,"eventId"));receipt.put("signalType",s(signal,"kind"));
        receipt.put("exposureEventId",s(exposure,"eventId"));receipt.put("decisionEventId",s(decision,"eventId"));
        receipt.put("decisionKind",s(decision,"kind"));receipt.put("decisionStatus",results.get(s(decision,"eventId")).status());
        receipt.put("recordedAt",s(annotation,"occurredAt"));
        receipt.put("syntheticCausalClaim",true);receipt.put("domainMutationPerformed",false);
        return receipt;
    }

    public static int verify(List<JsonNode> commands,List<SimulationCommandDispatcher.Result> actual) {
        Map<String,SimulationCommandDispatcher.Result> results=new HashMap<>();
        for(var result:actual)check(results.put(result.eventId(),result)==null,"DUPLICATE_RESULT");
        Map<String,JsonNode> prior=new LinkedHashMap<>();int count=0;
        for(JsonNode command:commands) {
            if(s(command,"kind").equals("INFLUENCED_DECISION")) {
                var result=results.get(s(command,"eventId"));
                check(result!=null && result.status().equals("APPLIED") && result.errorCode()==null,"ANNOTATION_RESULT");
                check(SimulationBundleReader.parse(result.rawResult()).equals(receipt(command,prior,results)),"RECEIPT_MISMATCH");count++;
            }
            check(prior.put(s(command,"eventId"),command)==null,"DUPLICATE_COMMAND");
        }
        return count;
    }

    private static JsonNode exposure(JsonNode signal,Map<String,JsonNode> prior,
                                     Map<String,SimulationCommandDispatcher.Result> results,Set<String> visited) {
        check(visited.add(s(signal,"eventId")),"SOURCE_CYCLE");
        var actual=results.get(s(signal,"eventId"));
        check(actual!=null && actual.status().equals("APPLIED") && actual.errorCode()==null,"SIGNAL_NOT_ACCEPTED");
        JsonNode body=SimulationBundleReader.parse(actual.rawResult()).get("body");
        String expected=s(signal,"kind").equals("IMPRESSION")?"FEED_EXPOSURE":s(signal,"kind").equals("CLICK")?"FEED_CLICK":"PROFILE_VISIT";
        check(body!=null && body.has("eventType") && s(body,"eventType").equals(expected)
            && body.has("occurredAt") && Instant.parse(s(body,"occurredAt")).equals(Instant.parse(s(signal,"occurredAt"))),"SIGNAL_RECEIPT");
        if(s(signal,"kind").equals("IMPRESSION"))return signal;
        JsonNode c=signal.get("command");
        if(s(signal,"kind").equals("CLICK")) {
            return prior.values().stream().filter(e->s(e,"kind").equals("IMPRESSION") && before(e,signal)
                && s(e,"actorStudentId").equals(s(signal,"actorStudentId"))
                && List.of("academyId","resultContextId","cardId","position","impressionId").stream()
                    .allMatch(k->e.get("command").get(k).equals(c.get(k)))
                && results.containsKey(s(e,"eventId")) && results.get(s(e,"eventId")).status().equals("APPLIED"))
                .min(Comparator.comparingLong(e->e.get("sequence").longValue()))
                .map(e->exposure(e,prior,results,visited)).orElseThrow(()->new IllegalStateException("INFLUENCE_NO_PRIOR_EXPOSURE"));
        }
        check(s(signal,"kind").equals("PROFILE_VISIT") && !c.get("sourceEventId").isNull(),"PROFILE_SOURCE_REQUIRED");
        JsonNode source=earlier(signal,s(c,"sourceEventId"),prior,results);
        check(causes(signal,s(source,"eventId")) && Set.of("IMPRESSION","CLICK","PROFILE_VISIT").contains(s(source,"kind")),"PROFILE_SOURCE");
        // Logical shared-card identities are bound by the dispatcher to their successful sharing event.
        check(c.get("academyId").equals(source.get("command").get("academyId")),"PROFILE_ACADEMY");
        if(s(source,"kind").equals("PROFILE_VISIT"))check(c.get("targetStudentId").equals(source.get("command").get("targetStudentId")),"PROFILE_TARGET");
        if(!s(source,"kind").equals("PROFILE_VISIT")) {
            JsonNode share=prior.get(s(source.get("command"),"cardId"));
            check(share!=null && Set.of("SHARE","VISIBILITY_CHANGE").contains(s(share,"kind"))
                && s(share,"actorStudentId").equals(s(c,"targetStudentId")),"PROFILE_TARGET");
        }
        return exposure(source,prior,results,visited);
    }

    private static JsonNode earlier(JsonNode event,String id,Map<String,JsonNode> prior,
                                    Map<String,SimulationCommandDispatcher.Result> results) {
        JsonNode source=prior.get(id);var actual=results.get(id);
        check(source!=null && before(source,event),"REFERENCE_ORDER");
        check(s(source,"actorStudentId").equals(s(event,"actorStudentId")),"ACTOR");
        check(actual!=null && actual.eventId().equals(id) && actual.status().equals(s(source.get("outcome"),"status")),"ACTUAL_RESULT");
        return source;
    }
    private static boolean before(JsonNode a,JsonNode b) {
        return a.get("sequence").longValue()<b.get("sequence").longValue()
            && !Instant.parse(s(a,"occurredAt")).isAfter(Instant.parse(s(b,"occurredAt")));
    }
    private static boolean causes(JsonNode e,String id) { for(JsonNode c:e.get("causes"))if(c.asString().equals(id))return true;return false; }
    private static String s(JsonNode n,String k) { return n.get(k).asString(); }
    private static void check(boolean ok,String code) { if(!ok)throw new IllegalStateException("INFLUENCE_"+code); }
}
