package com.crabit.backend.simulation;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic app inactivity annotation; never changes membership, money or behavior rows. */
public final class SimulationDormancyVerifier {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    // Cash activity occurs outside the app; scheduled closure and causal annotations are not visits.
    private static final Set<String> PASSIVE=Set.of("GRANT","PURCHASE","CLOSE_WEEK","CLOSE_MONTH","INFLUENCED_DECISION");
    private SimulationDormancyVerifier() {}

    static JsonNode anchor(JsonNode event,Map<String,JsonNode> prior) {
        String ref=s(event.get("command"),"priorDormancyEventId");
        JsonNode anchor=prior.get(ref);
        check(anchor!=null,"ANCHOR_MISSING");
        check(s(anchor,"actorStudentId").equals(s(event,"actorStudentId")),"ACTOR");
        check(!PASSIVE.contains(s(anchor,"kind")),"PASSIVE_ANCHOR");
        check(anchor.get("sequence").longValue()<event.get("sequence").longValue(),"ANCHOR_ORDER");
        boolean caused=false;for(JsonNode cause:event.get("causes"))if(cause.asString().equals(ref))caused=true;
        check(caused,"CAUSE");
        Duration gap=Duration.between(Instant.parse(s(anchor,"occurredAt")),Instant.parse(s(event,"occurredAt")));
        check(gap.compareTo(Duration.ofDays(7))>=0 && gap.compareTo(Duration.ofDays(21))<=0,"DURATION");
        for(JsonNode candidate:prior.values()) {
            if(s(candidate,"actorStudentId").equals(s(event,"actorStudentId"))
                && candidate.get("sequence").longValue()>anchor.get("sequence").longValue()
                && candidate.get("sequence").longValue()<event.get("sequence").longValue()
                && !PASSIVE.contains(s(candidate,"kind")))check(false,"INTERVENING_APP_ACTIVITY");
        }
        return anchor;
    }

    public static JsonNode receipt(JsonNode event,Map<String,JsonNode> prior,
                                   Map<String,SimulationCommandDispatcher.Result> results) {
        JsonNode anchor=anchor(event,prior);var result=results.get(s(anchor,"eventId"));
        check(result!=null && result.eventId().equals(s(anchor,"eventId"))
            && result.status().equals(s(anchor.get("outcome"),"status")),"ACTUAL_ANCHOR_RESULT");
        var out=JSON.createObjectNode().put("schemaVersion",1).put("schemaKind","simulation-dormancy-return-annotation");
        out.put("priorDormancyEventId",s(anchor,"eventId"));out.put("priorActivityKind",s(anchor,"kind"));
        out.put("priorActivityStatus",result.status());out.put("inactiveSince",s(anchor,"occurredAt"));
        out.put("returnedAt",s(event,"occurredAt"));
        out.put("inactiveDuration",Duration.between(Instant.parse(s(anchor,"occurredAt")),Instant.parse(s(event,"occurredAt"))).toString());
        out.put("syntheticAppInactivityClaim",true);out.put("domainMutationPerformed",false);
        return out;
    }

    public static int verify(List<JsonNode> commands,List<SimulationCommandDispatcher.Result> actual) {
        Map<String,SimulationCommandDispatcher.Result> results=new HashMap<>();
        for(var result:actual)check(results.put(result.eventId(),result)==null,"DUPLICATE_RESULT");
        Map<String,JsonNode> prior=new LinkedHashMap<>();int count=0;
        for(JsonNode command:commands) {
            if(s(command,"kind").equals("RETURN_FROM_DORMANCY")) {
                var result=results.get(s(command,"eventId"));
                check(result!=null && result.status().equals("APPLIED") && result.errorCode()==null,"RETURN_RESULT");
                check(SimulationBundleReader.parse(result.rawResult()).equals(receipt(command,prior,results)),"RECEIPT_MISMATCH");count++;
            }
            check(prior.put(s(command,"eventId"),command)==null,"DUPLICATE_COMMAND");
        }
        return count;
    }
    private static String s(JsonNode node,String key) { return node.get(key).asString(); }
    private static void check(boolean ok,String rule) { if(!ok)throw new IllegalStateException("DORMANCY_"+rule); }
}
