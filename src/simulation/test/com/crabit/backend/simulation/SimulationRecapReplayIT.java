package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class SimulationRecapReplayIT {
    @TempDir Path temp;
    SimulationRecapDispatcherIT fixture() {var f=new SimulationRecapDispatcherIT();f.temp=temp;return f;}
    List<byte[]> events(SimulationRecapDispatcherIT f,boolean month) throws Exception {
        List<JsonNode> events=new ArrayList<>();
        events.add(f.event("JOIN",f.START,Map.of("studentId",f.ACTOR,"accountId",f.ACCOUNT,"academyId","academy-1","grade",3),"APPLIED"));
        events.add(f.event("GRANT",f.START.plusSeconds(1),Map.of("accountId",f.ACCOUNT,"amountKrw",20000,"cashEntryId","cash","budgetMonth","2026-06","scheduledAt",f.START.toString()),"APPLIED"));
        events.add(f.close("CLOSE_WEEK","week",f.WEEK,f.ACCOUNT,"APPLIED"));
        if(month)events.add(f.close("CLOSE_MONTH","month",f.MONTH,f.ACCOUNT,"APPLIED"));
        return events.stream().map(f.JSON::writeValueAsBytes).toList();
    }
    JsonNode read(Path p) throws Exception {return SimulationBundleReader.parse(Files.readAllBytes(p));}
    void verifyIndex(Path output) throws Exception {
        JsonNode index=read(output.resolve("raw/index.json"));
        SimulationBundleReader.validate(fixture().schema().get("$defs").get("rawIndex"),index,"rawIndex");
        for(JsonNode record:index.get("records")) {
            byte[] bytes=Files.readAllBytes(output.resolve(record.get("path").asString()));
            assertThat(record.get("sha256").asString()).isEqualTo(SimulationBundleReader.digest(bytes));
            assertThat(record.get("byteLength").asLong()).isEqualTo(bytes.length);
        }
    }
    @Test void realPythonWeekAndIneligibleMonthHaveDistinctEvidenceAndReproduceAcrossTwoDatabases() throws Exception {
        var f=fixture();var events=events(f,true);Map<String,Object> previous=null;byte[] priorRaw=null;
        try(var python=f.python()) {
            for(int run=0;run<2;run++) {
                Path output=temp.resolve("run-"+run);
                var result=SimulationReplayRun.replay(f.schema(),f.DATASET,f.DATASET,f.people(),events,output,
                    new SimulationReplayRun.RecapOptions(python.endpoint(),f.TOKEN));
                assertThat(result.get("status")).isEqualTo("REPLAYED_PARTIAL_VALIDATION");
                assertThat(result.get("recapExchangesVerified")).isEqualTo(2);
                assertThat(result.get("recapPeriodsVerified")).isEqualTo(2);
                assertThat(output.resolve("raw/recap-period/event-3-period-source.json")).isRegularFile();
                assertThat(output.resolve("raw/recap-period/event-4-period-verification.json")).isRegularFile();
                assertThat(result.get("pythonInvoked")).isEqualTo(true);
                assertThat(result.get("absentRecapResponses")).isEqualTo(List.of("raw/month-response.json"));
                assertThat(output.resolve("raw/month-response.json")).doesNotExist();
                assertThat(Files.readAllBytes(output.resolve("raw/requests/event-3.json"))).isEqualTo(events.get(2));
                byte[] request=Files.readAllBytes(output.resolve("raw/week-request.json"));
                assertThat(request).isEqualTo(Files.readAllBytes(output.resolve("recap-execution/event-3/request.json")));
                assertThat(read(output.resolve("raw/week-snapshot.json"))).isEqualTo(SimulationBundleReader.parse(request).get("input"));
                assertThat(read(output.resolve("raw/month-stored.json")).get("state").asString()).isEqualTo("NOT_ELIGIBLE");
                assertThat(read(output.resolve("normalized-recaps.json")).get(1).get("response").isNull()).isTrue();
                JsonNode index=read(output.resolve("raw/index.json"));
                var records=new HashMap<String,JsonNode>();for(JsonNode r:index.get("records"))records.put(r.get("path").asString(),r);
                assertThat(records.get("raw/week-response.json").get("service").asString()).isEqualTo("RECAP");
                assertThat(records.get("raw/month-request.json").get("kind").asString()).isEqualTo("STORED_DOCUMENT");
                assertThat(records.get("raw/month-request.json").get("service").asString()).isEqualTo("BACKEND");
                verifyIndex(output);
                if(previous!=null) {
                    assertThat(result.get("normalizedRecapDigest")).isEqualTo(previous.get("normalizedRecapDigest"));
                    assertThat(result.get("backendLogicalDigest")).isEqualTo(previous.get("backendLogicalDigest"));
                    assertThat(request).isNotEqualTo(priorRaw);
                }
                previous=result;priorRaw=request;
            }
        }
    }
    @Test void mixedPeerValuesAndSameInstantTransferReproduceWithActualPythonAcrossTwoDatabases() throws Exception {
        var f=fixture();var commands=new ArrayList<JsonNode>();
        for(int i=0;i<3;i++) {
            var join=f.event("JOIN",f.START,Map.of("studentId","student-3-0"+i,"accountId","account-3-0"+i,"academyId","academy-1","grade",3),"APPLIED");
            join.put("actorStudentId","student-3-0"+i);commands.add(join);
        }
        for(int i=0;i<3;i++) {
            var grant=f.event("GRANT",f.START.plusSeconds(1),Map.of("accountId","account-3-0"+i,"amountKrw",20000,"cashEntryId","cash-"+i,"budgetMonth","2026-06","scheduledAt",f.START.toString()),"APPLIED");
            grant.put("actorStudentId","student-3-0"+i);commands.add(grant);
        }
        for(int i=0;i<4;i++) {
            int owner=i<2?0:i-1;
            var create=new HashMap<String,Object>();create.putAll(Map.of("accountId","account-3-0"+owner,"wishId","goal-"+i,"idempotencyKey","create-"+i,"purpose","goal "+i,"targetAmount",10000));
            create.put("startDate",null);create.put("targetDate",null);create.put("photoId",null);
            var event=f.event("CREATE",f.START.plusSeconds(2+i),create,"APPLIED");event.put("actorStudentId","student-3-0"+owner);commands.add(event);
        }
        // One active peer and one zero-activity peer ensure distinct anonymous values.
        for(int i=0;i<2;i++) {
            var event=f.event("DEPOSIT",f.START.plusSeconds(6+i),Map.of("accountId","account-3-0"+i,"wishId",i==0?"goal-0":"goal-2","idempotencyKey","deposit-"+i,"expectedVersion",0,"amount",i==0?4000:2500),"APPLIED");
            event.put("actorStudentId","student-3-0"+i);commands.add(event);
        }
        commands.add(f.event("TRANSFER",f.START.plusSeconds(8),Map.of("accountId",f.ACCOUNT,"sourceWishId","goal-0","destinationWishId","goal-1","amount",1000,
            "sourceExpectedVersion",1,"destinationExpectedVersion",0,"idempotencyKey","transfer","rootEventId","transfer-root","sourceEffectId","transfer-from","destinationEffectId","transfer-to"),"APPLIED"));
        commands.add(f.close("CLOSE_WEEK","week",f.WEEK,f.ACCOUNT,"APPLIED"));
        var bytes=commands.stream().map(f.JSON::writeValueAsBytes).toList();
        Path evidence=Path.of("build/simulation-recap-mixed-repro");Files.createDirectories(evidence);
        Map<String,byte[]> previous=new HashMap<>();byte[] previousRaw=null;
        try(var python=f.python()) {
            for(int run=0;run<2;run++) {
                Path output=temp.resolve("mixed-"+run);
                var result=SimulationReplayRun.replay(f.schema(),f.DATASET,f.DATASET,f.people(),bytes,output,
                    new SimulationReplayRun.RecapOptions(python.endpoint(),f.TOKEN));
                assertThat(result.get("status")).isEqualTo("REPLAYED_PARTIAL_VALIDATION");
                JsonNode request=read(output.resolve("raw/week-request.json"));
                assertThat(request.get("input").get("peer_metrics").get("habit_active_weeks").size()).isEqualTo(2);
                assertThat(request.get("input").get("effective_transactions").size()).isEqualTo(3);
                byte[] raw=Files.readAllBytes(output.resolve("raw/week-request.json"));
                Files.write(evidence.resolve("run-"+run+"-request.json"),raw);
                for(String name:List.of("normalized-recaps.json","normalized-backend.json","normalized-relational.json","normalized-responses.json")) {
                    byte[] actual=Files.readAllBytes(output.resolve(name));
                    Files.write(evidence.resolve("run-"+run+"-"+name),actual);
                    if(run==1)assertThat(actual).as(name).isEqualTo(previous.get(name));
                    previous.put(name,actual);
                }
                if(run==1)assertThat(raw).isNotEqualTo(previousRaw);previousRaw=raw;
                verifyIndex(output);
            }
        }
    }
    @Test void failedPythonRetainsActualHttpAndFailedStoredGenerationWithoutRetry() throws Exception {
        var f=fixture();var events=events(f,true);Path output=temp.resolve("failed");
        try(var python=f.python()) {
            assertThatThrownBy(()->SimulationReplayRun.replay(f.schema(),f.DATASET,f.DATASET,f.people(),events,output,
                new SimulationReplayRun.RecapOptions(python.endpoint(),"wrong"))).hasMessage("HTTP_401");
        }
        JsonNode report=read(output.resolve("replay-observation.json"));
        assertThat(report.get("status").asString()).isEqualTo("FAILED");
        assertThat(report.get("completedEvents").asInt()).isEqualTo(2);
        assertThat(report.get("pythonInvoked").asBoolean()).isTrue();
        assertThat(report.get("recapExchangesVerified").asInt()).isZero();
        assertThat(read(output.resolve("raw/recap-http/event-3.json")).get("status").asInt()).isEqualTo(401);
        assertThat(read(output.resolve("raw/week-stored.json")).get("state").asString()).isEqualTo("FAILED");
        assertThat(output.resolve("raw/requests/event-4.json")).doesNotExist();
        assertThat(output.resolve("normalized-recaps.json")).doesNotExist();
        verifyIndex(output);
    }
    @Test void resultVerificationPathCollisionFailsBeforeOutputOrDatabaseCreation() throws Exception {
        var f=fixture();
        for(String path:List.of("raw/recap-result/event-3.json","raw/recap-period/event-3-period-source.json",
                "raw/recap-period/event-3-period-input.json","raw/recap-period/event-3-period-verification.json")) {
        var events=new ArrayList<>(events(f,false));
        var closure=(tools.jackson.databind.node.ObjectNode)SimulationBundleReader.parse(events.get(2));
        ((tools.jackson.databind.node.ObjectNode)closure.get("command")).put("snapshotRef",path.replace("event-3", "event-"+closure.get("sequence").asLong()));
        events.set(2,f.JSON.writeValueAsBytes(closure));Path output=temp.resolve("result-collision");
        assertThatThrownBy(()->SimulationReplayRun.replay(f.schema(),f.DATASET,f.DATASET,f.people(),events,output,
            new SimulationReplayRun.RecapOptions(java.net.URI.create("http://127.0.0.1:1/internal/v1/recap-generations"),"local")))
            .hasMessage("REPLAY_RAW_PATH_COLLISION");
        assertThat(output).doesNotExist();
        }
    }
    @Test void recapReferenceCollisionFailsBeforeOutputOrDatabaseCreation() throws Exception {
        var f=fixture();var events=new ArrayList<>(events(f,false));
        var closure=(tools.jackson.databind.node.ObjectNode)SimulationBundleReader.parse(events.get(2));
        ((tools.jackson.databind.node.ObjectNode)closure.get("command")).put("snapshotRef","raw/week-request.json");
        events.set(2,f.JSON.writeValueAsBytes(closure));Path output=temp.resolve("collision");
        assertThatThrownBy(()->SimulationReplayRun.replay(f.schema(),f.DATASET,f.DATASET,f.people(),events,output,
            new SimulationReplayRun.RecapOptions(java.net.URI.create("http://127.0.0.1:1/internal/v1/recap-generations"),"local")))
            .hasMessage("REPLAY_RAW_PATH_COLLISION");
        assertThat(output).doesNotExist();
    }
}
