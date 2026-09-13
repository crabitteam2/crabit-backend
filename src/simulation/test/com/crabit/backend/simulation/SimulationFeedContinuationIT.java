package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationFeedContinuationIT {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String DATASET="sha256:"+"c".repeat(64);
    @TempDir Path temp;
    ObjectNode event(int n,String kind,String actor,int seconds,Map<String,?> command) {
        var e=JSON.createObjectNode().put("eventId","e"+n).put("sequence",n).put("kind",kind).put("actorStudentId",actor)
            .put("occurredAt",SimulationCashOracle.START.plusSeconds(seconds).toString());
        e.putArray("causes");e.set("command",JSON.valueToTree(command));
        e.putObject("outcome").put("status","APPLIED").put("resultRef","raw/results/"+n+".json");
        var refs=e.putArray("artifactRefs").add("raw/results/"+n+".json");
        command.forEach((k,v)->{if(k.endsWith("Ref"))refs.add(v.toString());});return e;
    }
    Map<String,Object> feed(String page,String card,String cursor) {
        var c=new HashMap<String,Object>();c.put("academyId","academy-1");c.put("limit",1);c.put("cursor",cursor);
        c.put("resultContextId",page);c.put("orderedCardIds",List.of(card));c.put("requestRef","raw/"+page+"-request.json");c.put("responseRef","raw/"+page+"-response.json");return c;
    }
    @Test void identicalCommandBytesReplayRealSignedPagesInTwoDatabasesAndRetainExpiredRejection() throws Exception {
        var schema=JSON.readTree(Files.readAllBytes(Path.of("api/demo-simulation-v1.schema.json")));
        var people=JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/bundle-contract-valid/students.json")));
        List<JsonNode> events=new ArrayList<>();
        for(int i=0;i<2;i++)events.add(event(i+1,"JOIN","student-3-0"+i,0,Map.of("studentId","student-3-0"+i,"accountId","account-3-0"+i,"academyId","academy-1","grade",3)));
        for(int i=0;i<2;i++) {
            var c=new HashMap<String,Object>();c.put("accountId","account-3-01");c.put("wishId","wish-"+i);c.put("idempotencyKey","create-"+i);
            c.put("purpose","synthetic wish "+i);c.put("targetAmount",5000);c.put("startDate",null);c.put("targetDate",null);c.put("photoId",null);
            events.add(event(3+2*i,"CREATE","student-3-01",1+2*i,c));
            events.add(event(4+2*i,"SHARE","student-3-01",2+2*i,Map.of("accountId","account-3-01","wishId","wish-"+i,"expectedVersion",0,"visibility","ACADEMY")));
        }
        events.add(event(7,"FEED_QUERY","student-3-00",5,feed("first","e6",null)));
        var next=event(8,"FEED_QUERY","student-3-00",6,feed("second","e4","event:e7:nextCursor"));next.putArray("causes").add("e7");events.add(next);
        var expired=event(9,"FEED_QUERY","student-3-00",305,feed("expired","e4","event:e7:nextCursor"));
        expired.putArray("causes").add("e7");((ObjectNode)expired.get("outcome")).put("status","REJECTED");events.add(expired);
        List<byte[]> commands=events.stream().map(JSON::writeValueAsBytes).toList();List<String> normalized=new ArrayList<>(),raw=new ArrayList<>(), backendDigests=new ArrayList<>(), originalManifestDigests=new ArrayList<>();
        var invalid=next.deepCopy();invalid.putArray("causes");
        var broken=new ArrayList<>(commands);broken.set(7,JSON.writeValueAsBytes(invalid));
        Path rejected=temp.resolve("preflight-rejected");
        assertThatThrownBy(()->SimulationReplayRun.replay(schema,DATASET,DATASET,people,broken,rejected))
            .hasMessageContaining("FEED_CONTINUATION_CAUSE_REQUIRED");
        assertThat(rejected).doesNotExist();
        for(int run=0;run<2;run++) {
            Path output=temp.resolve("run-"+run);
            String manifest="sha256:"+(run==0?"a":"b").repeat(64);
            var result=SimulationReplayRun.replay(schema,DATASET,manifest,people,commands,output);
            byte[] backend=Files.readAllBytes(output.resolve("normalized-backend.json"));
            assertThat(result.get("backendLogicalProjectionExported")).isEqualTo(true);
            assertThat(result.get("backendLogicalDigest")).isEqualTo(SimulationBundleReader.digest(backend));
            backendDigests.add(result.get("backendLogicalDigest").toString());
            var original=JSON.readTree(Files.readAllBytes(output.resolve("state/relational.json")));
            assertThat(original.get("tables").get("demo_simulation_dataset").get(0).get("manifest_digest").asString()).isEqualTo(manifest);
            originalManifestDigests.add(manifest);
            assertThat(result.get("completedEvents")).isEqualTo(9);assertThat(result.get("behaviorReconciliationPerformed")).isEqualTo(true);
            assertThat(result.get("readyForApplication")).isEqualTo(false);
            assertThat(Files.readAllBytes(output.resolve("raw/second-request.json"))).isEqualTo(commands.get(7));
            var first=JSON.readTree(Files.readAllBytes(output.resolve("raw/first-response.json")));raw.add(first.get("nextCursor").asString());
            var second=JSON.readTree(Files.readAllBytes(output.resolve("raw/second-response.json")));
            assertThat(second.get("items")).hasSize(1);assertThat(second.get("nextCursor").isNull()).isTrue();
            assertThat(JSON.readTree(Files.readAllBytes(output.resolve("raw/expired-response.json"))).get("code").asString()).isEqualTo("FEED_CURSOR_EXPIRED");
            normalized.add(result.get("normalizedResponseDigest").toString());
        }
        assertThat(backendDigests.get(0)).isEqualTo(backendDigests.get(1));
        assertThat(originalManifestDigests.get(0)).isNotEqualTo(originalManifestDigests.get(1));
        assertThat(raw.get(0)).isNotEqualTo(raw.get(1));assertThat(normalized.get(0)).isEqualTo(normalized.get(1));
        Path report=Path.of("build/simulation-verification/feed-continuation.json");Files.createDirectories(report.getParent());
        var observation=new LinkedHashMap<String,Object>(Map.of("schemaVersion",1,"status","PASS",
            "independentDisposableDatabases",2,"eventsPerReplay",commands.size(),"identicalLogicalCommandBytes",true,
            "commandDigests",commands.stream().map(SimulationBundleReader::digest).toList(),
            "rawCursorDigests",raw.stream().map(value->SimulationBundleReader.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList(),
            "normalizedResponseDigests",normalized,"expiredAtExactFiveMinuteBoundary",true,"fullDatasetValidationPerformed",false));
        observation.put("backendLogicalDigests",backendDigests);
        observation.put("originalManifestDigests",originalManifestDigests);
        Files.writeString(report,JSON.writeValueAsString(observation));
    }
}
