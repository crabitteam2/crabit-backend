package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationEventTimelineTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String ACTOR="student-3-01", ACCOUNT="account-3-01", REF="raw/result.json";
    static final String START="2026-05-31T15:00:00Z";
    JsonNode students, schema;
    @BeforeEach void load() throws Exception {
        students=JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/bundle-contract-valid/students.json")));
        schema=JSON.readTree(Files.readAllBytes(Path.of("api/demo-simulation-v1.schema.json"))).get("$defs").get("event");
    }
    ObjectNode event(String kind,long sequence,String time,String command) {
        ObjectNode e=JSON.createObjectNode().put("eventId","event-"+sequence).put("sequence",sequence).put("occurredAt",time)
            .put("actorStudentId",ACTOR).put("kind",kind);
        e.set("command",JSON.readTree(command));e.set("causes",JSON.createArrayNode());
        e.set("artifactRefs",JSON.createArrayNode().add(REF));
        e.set("outcome",JSON.createObjectNode().put("status","APPLIED").put("resultRef",REF));return e;
    }
    ObjectNode join() {return event("JOIN",1,START,"{\"studentId\":\""+ACTOR+"\",\"accountId\":\""+ACCOUNT+"\",\"academyId\":\"academy-1\",\"grade\":3}");}
    ObjectNode grant() {return event("GRANT",2,"2026-06-01T01:00:00Z","{\"accountId\":\""+ACCOUNT+"\",\"amountKrw\":20000,\"cashEntryId\":\"cash-1\",\"budgetMonth\":\"2026-06\",\"scheduledAt\":\"2026-06-01T00:00:00Z\"}");}
    ObjectNode click() {return event("CLICK",2,"2026-06-01T01:00:00Z","{\"academyId\":\"academy-1\",\"resultContextId\":\"context-1\",\"cardId\":\"card-1\",\"position\":0,\"impressionId\":\"impression-1\",\"clickKind\":\"AUTHOR_PROFILE\"}");}
    ObjectNode close(String kind,long seq,String time,String from,String to) {return event(kind,seq,time,"{\"accountId\":\""+ACCOUNT+"\",\"startInclusive\":\""+from+"\",\"endExclusive\":\""+to+"\",\"snapshotRef\":\""+REF+"\",\"requestRef\":\""+REF+"\",\"responseRef\":\""+REF+"\",\"storedStateRef\":\""+REF+"\",\"generationId\":\"generation-"+seq+"\"}");}
    SimulationEventTimeline.Result verify(JsonNode... events) {
        for(JsonNode e:events)SimulationBundleReader.validate(schema,e,"event");
        return new SimulationEventTimeline().verify(List.of(events),students,Set.of(REF));
    }
    ObjectNode command(ObjectNode e) {return (ObjectNode)e.get("command");}
    @Test void absentClosureResponseRequiresMatchingDurableNoHttpEvidence() throws Exception {
        var closure=close("CLOSE_MONTH",2,"2026-06-30T15:00:00Z","2026-06-01","2026-07-01");
        command(closure).put("responseRef","raw/absent.json").put("storedStateRef","raw/stored.json").put("requestRef","raw/request.json");
        closure.set("artifactRefs",JSON.createArrayNode().add(REF).add("raw/absent.json").add("raw/stored.json").add("raw/request.json"));
        String generation=UUID.randomUUID().toString(),digest="sha256:"+"a".repeat(64);
        var result=JSON.createObjectNode().put("state","NOT_ELIGIBLE").put("pythonInvoked",false).put("generationId",generation).put("inputDigest",digest);
        var row=JSON.createObjectNode().put("state","NOT_ELIGIBLE").put("id",generation).put("input_digest",digest)
            .put("period_start","2026-06-01").put("period_end_exclusive","2026-07-01").putNull("view_json").putNull("internal_metrics_json");
        var request=JSON.createObjectNode().put("generation_id",generation).put("input_digest",digest);
        var artifacts=new HashMap<String,byte[]>();
        artifacts.put(REF,JSON.writeValueAsBytes(result));artifacts.put("raw/stored.json",JSON.writeValueAsBytes(row));
        artifacts.put("raw/request.json",JSON.writeValueAsBytes(request));
        assertThat(new SimulationEventTimeline().verify(List.of(join(),closure),students,artifacts).applied()).isEqualTo(2);
        assertThatThrownBy(()->new SimulationEventTimeline().verify(List.of(join(),closure),students,artifacts.keySet())).hasMessageContaining("ARTIFACT_REF");
        for(var bad:List.of(result.deepCopy().put("pythonInvoked",true),result.deepCopy().put("state","SUCCEEDED"),result.deepCopy().put("generationId",UUID.randomUUID().toString()))) {
            artifacts.put(REF,JSON.writeValueAsBytes(bad));
            assertThatThrownBy(()->new SimulationEventTimeline().verify(List.of(join(),closure),students,artifacts)).hasMessageContaining("RECAP_ABSENCE_EVIDENCE");
        }
        artifacts.put(REF,JSON.writeValueAsBytes(result));
        var foreign=join();foreign.set("artifactRefs",JSON.createArrayNode().add(REF).add("raw/absent.json"));
        assertThatThrownBy(()->new SimulationEventTimeline().verify(List.of(foreign,closure),students,artifacts)).hasMessageContaining("ARTIFACT_REF");
        artifacts.put("raw/recap-http/event-2.json",new byte[0]);
        assertThatThrownBy(()->new SimulationEventTimeline().verify(List.of(join(),closure),students,artifacts)).hasMessageContaining("RECAP_ABSENCE_EVIDENCE");
    }
    @Test void acceptsJoinCashAndEarlierCausesWithoutClaimingStateValidation() {
        ObjectNode g=grant();g.set("causes",JSON.createArrayNode().add("event-1"));
        assertThat(verify(join(),g)).isEqualTo(new SimulationEventTimeline.Result(2,1,2,0,0));
    }
    @Test void validUnmatchedClickDoesNotRequireAnExposure() {assertThat(verify(join(),click()).applied()).isEqualTo(2);}
    @Test void independentProfileVisitNeedsNoClick() {
        ObjectNode v=event("PROFILE_VISIT",2,"2026-06-01T01:00:00Z","{\"academyId\":\"academy-1\",\"targetStudentId\":\"student-3-02\",\"source\":\"DIRECT\",\"sourceEventId\":null}");
        assertThat(verify(join(),v).events()).isEqualTo(2);
    }
    @Test void retainsFailedAndRejectedAttemptsAsSuch() {
        ObjectNode g=grant();((ObjectNode)g.get("outcome")).put("status","REJECTED");
        ObjectNode c=click().put("sequence",3).put("eventId","event-3");((ObjectNode)c.get("outcome")).put("status","FAILED");
        assertThat(verify(join(),g,c)).isEqualTo(new SimulationEventTimeline.Result(3,1,1,1,1));
    }
    @Test void rejectsActivityWithoutAnAppliedJoin() {
        assertThatThrownBy(()->verify(grant())).hasMessageContaining("JOIN_REQUIRED");
        ObjectNode j=join();((ObjectNode)j.get("outcome")).put("status","FAILED");
        assertThatThrownBy(()->verify(j,grant())).hasMessageContaining("JOIN_REQUIRED");
    }
    @Test void duplicateJoinCanBeARejectedAttemptButNotAnAppliedCreation() {
        ObjectNode j=join().put("sequence",2).put("eventId","event-2");
        assertThatThrownBy(()->verify(join(),j)).hasMessageContaining("JOIN_DUPLICATE");
        ((ObjectNode)j.get("outcome")).put("status","REJECTED");assertThat(verify(join(),j).rejected()).isEqualTo(1);
    }
    @TestFactory List<DynamicTest> rejectsGraphAndClockCorruption() {
        Map<String,Consumer<ObjectNode>> cases=new LinkedHashMap<>();
        cases.put("EVENT_DUPLICATE",e->e.put("eventId","event-1"));
        cases.put("EVENT_SEQUENCE",e->e.put("sequence",1));
        cases.put("EVENT_TIME",e->e.put("occurredAt","2026-09-10T15:00:00Z"));
        cases.put("CAUSE_NOT_EARLIER_OR_DUPLICATE",e->e.set("causes",JSON.createArrayNode().add("event-9")));
        cases.put("ARTIFACT_REF",e->e.set("artifactRefs",JSON.createArrayNode().add("raw/missing.json")));
        cases.put("OUTCOME_REF",e->((ObjectNode)e.get("outcome")).put("resultRef","raw/missing.json"));
        cases.put("ACTOR_ENROLLMENT",e->e.put("actorStudentId","unknown"));
        cases.put("ACCOUNT_OWNER",e->command(e).put("accountId","account-4-01"));
        cases.put("GRANT_SCHEDULE",e->command(e).put("scheduledAt","2026-07-01T00:00:00Z"));
        cases.put("GRANT_BUDGET_MONTH",e->command(e).put("budgetMonth","2026-07"));
        List<DynamicTest> result=new ArrayList<>();cases.forEach((rule,edit)->result.add(DynamicTest.dynamicTest(rule,()->{
            ObjectNode e=grant();edit.accept(e);assertThatThrownBy(()->verify(join(),e)).hasMessageContaining(rule);
        })));return result;
    }
    @Test void rejectsDuplicateCauseAndSelfCause() {
        for(var causes:List.of(JSON.createArrayNode().add("event-1").add("event-1"),JSON.createArrayNode().add("event-2"))) {
            ObjectNode e=grant();e.set("causes",causes);assertThatThrownBy(()->verify(join(),e)).hasMessageContaining("CAUSE_NOT_EARLIER");
        }
    }
    @Test void closesWeekBeforeMonthBeforeBoundaryActivity() {
        String time="2026-07-31T15:00:00Z";
        assertThat(verify(join(),close("CLOSE_MONTH",2,time,"2026-07-01","2026-08-01")).events()).isEqualTo(2);
        // June 1 cannot close May (outside the dataset). August 31 closes a week, not a month.
        ObjectNode week=close("CLOSE_WEEK",2,"2026-08-30T15:00:00Z","2026-08-24","2026-08-31");
        assertThat(verify(join(),week).events()).isEqualTo(2);
        ObjectNode activity=grant().put("occurredAt","2026-08-30T15:00:00Z");
        ObjectNode laterWeek=week.deepCopy().put("sequence",3).put("eventId","event-3");
        assertThatThrownBy(()->verify(join(),activity,laterWeek)).hasMessageContaining("PERIOD_BOUNDARY_ORDER");
    }
    @Test void doesNotCloseIncompleteFinalWeekOrSeptemberMonth() {
        for(ObjectNode e:List.of(close("CLOSE_WEEK",2,"2026-09-10T14:59:59Z","2026-09-07","2026-09-14"),
                close("CLOSE_MONTH",2,"2026-09-10T14:59:59Z","2026-09-01","2026-10-01")))
            assertThatThrownBy(()->verify(join(),e)).hasMessageContaining("PERIOD_RANGE");
        assertThat(verify(join(),close("CLOSE_WEEK",2,"2026-09-06T15:00:00Z","2026-08-31","2026-09-07")).events()).isEqualTo(2);
    }
    @Test void rejectsWrongWeekLengthAndDuplicateSuccessfulClose() {
        ObjectNode w=close("CLOSE_WEEK",2,"2026-08-30T15:00:00Z","2026-08-25","2026-08-31");
        assertThatThrownBy(()->verify(join(),w)).hasMessageContaining("WEEK_RANGE");
        command(w).put("startInclusive","2026-08-24");
        ObjectNode duplicate=w.deepCopy().put("sequence",3).put("eventId","event-3");
        assertThatThrownBy(()->verify(join(),w,duplicate)).hasMessageContaining("PERIOD_DUPLICATE");
    }
    @Test void rejectsCrossStudentJoinAndReversedSocialActor() {
        ObjectNode j=join();command(j).put("studentId","student-4-01");
        assertThatThrownBy(()->verify(j)).hasMessageContaining("JOIN_IDENTITY");
        ObjectNode follow=event("FOLLOW",2,"2026-06-01T01:00:00Z","{\"academyId\":\"academy-1\",\"viewerStudentId\":\"student-4-01\",\"ownerStudentId\":\"student-3-01\"}");
        assertThatThrownBy(()->verify(join(),follow)).hasMessageContaining("SOCIAL_DIRECTION");
    }
    @Test void strictSchemaRejectsUnknownCommandsUnknownFieldsFractionalMoneyAndUnconfirmedCompletion() {
        ObjectNode g=grant();command(g).put("amountKrw",1.5);assertThatThrownBy(()->verify(join(),g)).hasMessageContaining("SCHEMA_INVALID");
        ObjectNode zero=grant();command(zero).put("amountKrw",0);assertThatThrownBy(()->verify(join(),zero)).hasMessageContaining("SCHEMA_INVALID");
        ObjectNode c=click();command(c).put("precedingExposureRequired",true);assertThatThrownBy(()->verify(join(),c)).hasMessageContaining("SCHEMA_INVALID");
        ObjectNode unknown=grant().put("kind","ADJUST_ALLOCATION");assertThatThrownBy(()->verify(join(),unknown)).hasMessageContaining("SCHEMA_INVALID");
        ObjectNode complete=event("COMPLETE",2,"2026-06-01T01:00:00Z","{\"accountId\":\""+ACCOUNT+"\",\"wishId\":\"wish-1\",\"expectedVersion\":0,\"idempotencyKey\":\"complete-1\",\"confirmed\":false}");
        assertThatThrownBy(()->verify(join(),complete)).hasMessageContaining("SCHEMA_INVALID");
    }
    @TestFactory List<DynamicTest> sharedEventSchemaVectors() throws Exception {
        JsonNode vectors=JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/event-schema-vectors.json")));
        List<DynamicTest> tests=new ArrayList<>();
        for(JsonNode positive:vectors.get("positive"))tests.add(DynamicTest.dynamicTest("positive-"+positive.get("kind").asString(),()->SimulationBundleReader.validate(schema,positive,"event")));
        for(JsonNode vector:vectors.get("negative"))tests.add(DynamicTest.dynamicTest(vector.get("name").asString(),()->{
            JsonNode event=vectors.get("positive").get(vector.get("base").intValue()).deepCopy();
            String pointer=vector.get("path").asString();int slash=pointer.lastIndexOf('/');
            ((ObjectNode)event.at(pointer.substring(0,slash))).set(pointer.substring(slash+1),vector.get("value"));
            assertThatThrownBy(()->SimulationBundleReader.validate(schema,event,"event")).isInstanceOf(SimulationBundleReader.Rejection.class);
        }));return tests;
    }
}
