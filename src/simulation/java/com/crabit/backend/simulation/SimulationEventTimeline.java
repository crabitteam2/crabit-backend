package com.crabit.backend.simulation;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Admission of causal references, not evidence that a domain command ran or was authorized. */
public final class SimulationEventTimeline {
    private static final Instant START=SimulationCashOracle.START, END=SimulationCashOracle.END;
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    public record Result(int events, int joinedStudents, int applied, int rejected, int failed) {}

    /** A closure response is only a reserved path when durable evidence proves no HTTP occurred. */
    public Result verify(List<JsonNode> events, JsonNode students, Map<String,byte[]> artifacts) {
        var absentResponses=new HashMap<String,String>();
        for(JsonNode event:events) {
            if(!Set.of("CLOSE_WEEK","CLOSE_MONTH").contains(str(event,"kind")))continue;
            JsonNode command=event.get("command");String response=str(command,"responseRef");
            if(artifacts.containsKey(response))continue;
            byte[] storedBytes=artifacts.get(str(command,"storedStateRef"));
            byte[] resultBytes=artifacts.get(str(event.get("outcome"),"resultRef"));
            byte[] requestBytes=artifacts.get(str(command,"requestRef"));
            check(storedBytes!=null && resultBytes!=null && requestBytes!=null,"RECAP_ABSENCE_EVIDENCE",str(event,"eventId"));
            JsonNode row=SimulationBundleReader.parse(storedBytes), result=SimulationBundleReader.parse(resultBytes), request=SimulationBundleReader.parse(requestBytes);
            check(str(event.get("outcome"),"status").equals("APPLIED")
                && row.path("state").asString().equals("NOT_ELIGIBLE")
                && result.path("state").asString().equals("NOT_ELIGIBLE")
                && result.path("pythonInvoked").isBoolean() && !result.get("pythonInvoked").asBoolean()
                && row.path("id").equals(result.path("generationId")) && row.path("id").equals(request.path("generation_id"))
                && row.path("input_digest").equals(result.path("inputDigest")) && row.path("input_digest").equals(request.path("input_digest"))
                && row.path("period_start").equals(command.path("startInclusive"))
                && row.path("period_end_exclusive").equals(command.path("endExclusive"))
                && !row.hasNonNull("view_json") && !row.hasNonNull("internal_metrics_json")
                && !artifacts.containsKey("raw/recap-http/event-"+event.get("sequence").asLong()+".json"),
                "RECAP_ABSENCE_EVIDENCE",str(event,"eventId"));
            absentResponses.put(str(event,"eventId"),response);
        }
        return verify(events,students,artifacts.keySet(),absentResponses);
    }

    /** Inputs must have passed the closed event/student schemas first. */
    public Result verify(List<JsonNode> events, JsonNode students, Set<String> artifactPaths) {
        return verify(events,students,artifactPaths,Map.of());
    }
    private Result verify(List<JsonNode> events, JsonNode students, Set<String> artifactPaths, Map<String,String> absentResponses) {
        Map<String,JsonNode> people=new HashMap<>(), prior=new HashMap<>();
        Map<String,String> accountOwners=new HashMap<>();
        Set<String> joined=new HashSet<>(), cashIds=new HashSet<>(), closedPeriods=new HashSet<>();
        for(JsonNode s:students) {
            people.put(str(s,"logicalStudentId"),s);
            accountOwners.put(str(s,"logicalAccountId"),str(s,"logicalStudentId"));
        }
        long sequence=0; Instant previous=START; int applied=0,rejected=0,failed=0,previousPhase=-1;
        for(JsonNode e:events) {
            String id=str(e,"eventId"), actor=str(e,"actorStudentId"), kind=str(e,"kind");
            JsonNode cmd=e.get("command"), person=people.get(actor);
            Instant time=Instant.parse(str(e,"occurredAt"));
            check(!prior.containsKey(id),"EVENT_DUPLICATE",id);
            check(e.get("sequence").longValue()>sequence,"EVENT_SEQUENCE",id);
            check(!time.isBefore(previous) && !time.isBefore(START) && time.isBefore(END),"EVENT_TIME",id);
            int phase=kind.equals("CLOSE_WEEK")?0:kind.equals("CLOSE_MONTH")?1:2;
            check(!time.equals(previous) || phase>=previousPhase,"PERIOD_BOUNDARY_ORDER",id);
            if(!time.equals(previous))previousPhase=-1;
            previousPhase=phase; previous=time; sequence=e.get("sequence").longValue();
            Set<String> causes=new HashSet<>();
            for(JsonNode c:e.get("causes"))check(causes.add(c.asString()) && prior.containsKey(c.asString()),"CAUSE_NOT_EARLIER_OR_DUPLICATE",id);
            Set<String> refs=new HashSet<>();
            for(JsonNode r:e.get("artifactRefs"))check(refs.add(r.asString()) && (artifactPaths.contains(r.asString())
                || r.asString().equals(absentResponses.get(id))),"ARTIFACT_REF",id);
            check(refs.contains(str(e.get("outcome"),"resultRef")),"OUTCOME_REF",id);
            for(String key:cmd.propertyNames())if(key.endsWith("Ref"))check(refs.contains(str(cmd,key)),"COMMAND_ARTIFACT_REF",id);
            check(person!=null && !time.isBefore(Instant.parse(str(person,"joinedAt"))),"ACTOR_ENROLLMENT",id);
            boolean ok=str(e.get("outcome"),"status").equals("APPLIED");
            if(kind.equals("JOIN")) {
                check(actor.equals(str(cmd,"studentId")) && str(person,"logicalAccountId").equals(str(cmd,"accountId"))
                    && str(person,"logicalAcademyId").equals(str(cmd,"academyId")) && person.get("grade").equals(cmd.get("grade")),"JOIN_IDENTITY",id);
                if(ok)check(time.equals(Instant.parse(str(person,"joinedAt"))) && joined.add(actor),"JOIN_DUPLICATE_OR_TIME",id);
            } else check(joined.contains(actor),"JOIN_REQUIRED",id);
            // Attempts against another account may be retained as rejected domain evidence.
            if(ok && cmd.has("accountId"))check(actor.equals(accountOwners.get(str(cmd,"accountId"))),"ACCOUNT_OWNER",id);
            if(cmd.has("academyId") && ok)check(str(person,"logicalAcademyId").equals(str(cmd,"academyId")),"ACTOR_ACADEMY",id);
            if(kind.equals("GRANT") || kind.equals("PURCHASE")) {
                check(cashIds.add(str(cmd,"cashEntryId")),"CASH_ENTRY_DUPLICATE",id);
                if(kind.equals("GRANT")) {
                    Instant scheduled=Instant.parse(str(cmd,"scheduledAt"));
                    check(!scheduled.isAfter(time) && !scheduled.isBefore(Instant.parse(str(person,"joinedAt"))),"GRANT_SCHEDULE",id);
                    check(str(cmd,"budgetMonth").equals(YearMonth.from(scheduled.atZone(SEOUL)).toString()),"GRANT_BUDGET_MONTH",id);
                }
            }
            if(kind.equals("TRANSFER"))check(!str(cmd,"sourceWishId").equals(str(cmd,"destinationWishId"))
                && !str(cmd,"sourceEffectId").equals(str(cmd,"destinationEffectId"))
                && !str(cmd,"rootEventId").equals(str(cmd,"sourceEffectId"))
                && !str(cmd,"rootEventId").equals(str(cmd,"destinationEffectId")),"TRANSFER_DISTINCT_IDENTITIES",id);
            if(Set.of("FOLLOW","UNFOLLOW","BLOCK","UNBLOCK").contains(kind)) {
                String owner=str(cmd,"ownerStudentId");
                check(actor.equals(str(cmd,"viewerStudentId")) && people.containsKey(owner),"SOCIAL_DIRECTION",id);
                if(ok)check(!actor.equals(owner) && joined.contains(owner),"SOCIAL_TARGET_ENROLLMENT",id);
            }
            if(kind.equals("INFLUENCED_DECISION")) {
                String signal=str(cmd,"signalEventId"), decision=str(cmd,"decisionEventId");
                JsonNode source=prior.get(signal), target=prior.get(decision);
                check(causes.contains(signal) && causes.contains(decision) && source!=null && target!=null,"INFLUENCE_CAUSE",id);
                check(str(source,"kind").equals(str(cmd,"signalType")) && actor.equals(str(source,"actorStudentId"))
                    && actor.equals(str(target,"actorStudentId")) && str(source.get("outcome"),"status").equals("APPLIED")
                    && source.get("sequence").longValue()<target.get("sequence").longValue(),"INFLUENCE_SIGNAL",id);
            }
            if(kind.equals("RETURN_FROM_DORMANCY"))SimulationDormancyVerifier.anchor(e,prior);
            if(kind.equals("PROFILE_VISIT") && !cmd.get("sourceEventId").isNull())
                check(causes.contains(str(cmd,"sourceEventId")),"VISIT_SOURCE_CAUSE",id);
            // CLICK deliberately has no exposure prerequisite. Authorization/context remain domain checks.
            if(kind.equals("CLOSE_WEEK") || kind.equals("CLOSE_MONTH")) {
                LocalDate from=LocalDate.parse(str(cmd,"startInclusive")), to=LocalDate.parse(str(cmd,"endExclusive"));
                check(from.isBefore(to) && !from.atStartOfDay(SEOUL).toInstant().isBefore(START)
                    && time.equals(to.atStartOfDay(SEOUL).toInstant()),"PERIOD_RANGE",id);
                if(kind.equals("CLOSE_WEEK"))check(from.getDayOfWeek()==DayOfWeek.MONDAY && to.equals(from.plusDays(7)),"WEEK_RANGE",id);
                else check(from.getDayOfMonth()==1 && to.equals(from.plusMonths(1)),"MONTH_RANGE",id);
                if(ok)check(closedPeriods.add(kind+":"+str(cmd,"accountId")+":"+from),"PERIOD_DUPLICATE",id);
            }
            switch(str(e.get("outcome"),"status")) { case "APPLIED" -> applied++; case "REJECTED" -> rejected++; case "FAILED" -> failed++; default -> throw new IllegalArgumentException("unvalidated outcome"); }
            SimulationFeedContinuation.source(e,prior);
            prior.put(id,e);
        }
        return new Result(events.size(),joined.size(),applied,rejected,failed);
    }
    private static String str(JsonNode n,String key) { return n.get(key).asString(); }
    private static void check(boolean ok,String rule,String id) {
        if(!ok)throw new SimulationBundleReader.Rejection("INVARIANT_VIOLATION",rule+" event="+id);
    }
}
