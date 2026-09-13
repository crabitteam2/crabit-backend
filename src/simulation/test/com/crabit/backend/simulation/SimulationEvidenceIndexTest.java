package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SimulationEvidenceIndexTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final Path ROOT=Path.of("src/test/resources/simulation/bundle-contract-valid");
    @org.junit.jupiter.api.io.TempDir Path temp;
    ObjectNode manifest,idMap,rawIndex,validation; JsonNode students,schema;
    List<JsonNode> events; Map<String,byte[]> artifacts;
    ObjectNode load(String name)throws Exception {return (ObjectNode)JSON.readTree(Files.readAllBytes(ROOT.resolve(name)));}
    @BeforeEach void setup()throws Exception {
        manifest=load("manifest.json");idMap=load("id-map.json");rawIndex=load("raw/index.json");validation=load("validation.json");
        students=JSON.readTree(Files.readAllBytes(ROOT.resolve("students.json")));
        schema=JSON.readTree(Files.readAllBytes(Path.of("api/demo-simulation-v1.schema.json"))).get("$defs");
        artifacts=new TreeMap<>();for(JsonNode file:manifest.get("files"))artifacts.put(file.get("path").asString(),Files.readAllBytes(ROOT.resolve(file.get("path").asString())));
        ObjectNode event=new SimulationEventTimelineTest().join();events=new ArrayList<>(List.of(event));
        // Deliberately non-JSON bytes: indexing must preserve immutable transport rather than reserialize it.
        byte[] raw=new byte[]{0,(byte)0xff,13,10,32};String path="raw/response.bin";
        artifacts.put(path,raw);
        ((ArrayNode)manifest.get("files")).add(JSON.createObjectNode().put("path",path).put("role","RAW"));
        ((ArrayNode)rawIndex.get("records")).add(JSON.createObjectNode().put("path",path).put("byteLength",raw.length)
            .put("sha256",SimulationBundleReader.digest(raw)).put("contentType","application/octet-stream")
            .put("service","FEED").put("modelVersion","TEST_ONLY").put("eventId","event-1").put("kind","RESPONSE"));
        event.set("artifactRefs",JSON.createArrayNode().add(path));
    }
    SimulationEvidenceIndex.Result verify() {
        for(var entry:Map.of("idMap",idMap,"rawIndex",rawIndex,"validation",validation).entrySet())
            SimulationBundleReader.validate(schema.get(entry.getKey()),entry.getValue(),entry.getKey());
        return new SimulationEvidenceIndex().verify(manifest,students,events,idMap,rawIndex,validation,artifacts);
    }
    ObjectNode raw(){return (ObjectNode)rawIndex.get("records").get(0);}
    ObjectNode account(){return (ObjectNode)idMap.get("entries").get(1);}
    ObjectNode rule(){return (ObjectNode)validation.get("rules").get(0);}
    @Test void verifiesBijectionsAndExactOpaqueBytesWithoutClaimingDomainExecution() {
        byte[] before=artifacts.get("raw/response.bin").clone();
        assertThat(verify()).isEqualTo(new SimulationEvidenceIndex.Result(201,1,1));
        assertThat(artifacts.get("raw/response.bin")).isEqualTo(before);
        assertThat(rule().get("status").asString()).isEqualTo("NOT_RUN");
    }
    @TestFactory List<DynamicTest> rejectsRawEvidenceCorruption() {
        Map<String,Consumer<ObjectNode>> cases=new LinkedHashMap<>();
        cases.put("RAW_INDEX_BYTES-length",n->n.put("byteLength",4));
        cases.put("RAW_INDEX_BYTES-digest",n->n.put("sha256","sha256:"+"0".repeat(64)));
        cases.put("RAW_INDEX_MODEL_BINDING",n->n.put("modelVersion","different-model"));
        cases.put("SCHEMA_INVALID-unknown-service",n->n.put("service","unknown-service"));
        cases.put("RAW_INDEX_ROLE",n->n.put("path","config.json"));
        cases.put("RAW_INDEX_EVENT_REFERENCE",n->n.put("eventId","missing-event"));
        cases.put("SCHEMA_INVALID-unknown-field",n->n.put("bearerToken","not-a-real-token"));
        cases.put("SCHEMA_INVALID-traversal",n->n.put("path","raw/../config.json"));
        cases.put("SCHEMA_INVALID-fraction",n->n.put("byteLength",0.5));
        List<DynamicTest> tests=new ArrayList<>();cases.forEach((name,edit)->tests.add(DynamicTest.dynamicTest(name,()->{
            setup();edit.accept(raw());assertThatThrownBy(this::verify).hasMessageContaining(name.split("-")[0]);
        })));return tests;
    }
    private void unversionedFeed(String part,byte[] bytes) {
        String path="raw/feed/event-1-"+part+".json";
        ((ObjectNode)events.get(0)).put("kind","FEED_QUERY");
        ((ObjectNode)events.get(0)).set("artifactRefs",JSON.createArrayNode().add(path));
        raw().put("path",path).putNull("modelVersion").put("kind",part.equals("response")?"RESPONSE":part.equals("request")?"REQUEST":"RUNTIME_OBSERVATION")
            .put("byteLength",bytes.length).put("sha256",SimulationBundleReader.digest(bytes));
        for(JsonNode f:manifest.get("files"))if(f.get("role").asString().equals("RAW"))((ObjectNode)f).put("path",path);
        artifacts.put(path,bytes);
    }
    @Test void unversionedFeedResponseIsBoundToActualWireModel() throws Exception {
        unversionedFeed("response",JSON.writeValueAsBytes(Map.of("model_version","TEST_ONLY")));
        assertThat(verify().rawRecords()).isEqualTo(1);
        unversionedFeed("response",JSON.writeValueAsBytes(Map.of("model_version","other")));
        assertThatThrownBy(this::verify).hasMessageContaining("RAW_INDEX_MODEL_BINDING");
    }
    @Test void unversionedFeedAttemptNeedsActualFallbackWhenNoResponseExists() throws Exception {
        unversionedFeed("request",new byte[]{'{','}'});
        assertThatThrownBy(this::verify).hasMessageContaining("RAW_INDEX_MODEL_BINDING");
        artifacts.put("raw/feed/event-1-page.json",JSON.writeValueAsBytes(Map.of("sortSource","LATEST")));
        assertThat(verify().rawRecords()).isEqualTo(1);
        artifacts.put("raw/feed/event-1-page.json",JSON.writeValueAsBytes(Map.of("pythonInvoked",true,"responseCaptured",false,"page",Map.of("sortSource","LATEST"))));
        assertThat(verify().rawRecords()).isEqualTo(1);
        artifacts.put("raw/feed/event-1-page.json",JSON.writeValueAsBytes(Map.of("sortSource","RECOMMENDED","modelVersion","TEST_ONLY")));
        assertThatThrownBy(this::verify).hasMessageContaining("RAW_INDEX_MODEL_BINDING");
    }
    @Test void unversionedFeedObservationsAreNotModelExecutions() {
        unversionedFeed("page-source",new byte[]{'{','}'});
        assertThat(verify().rawRecords()).isEqualTo(1);
        ((ObjectNode)events.get(0)).put("kind","JOIN");
        assertThatThrownBy(this::verify).hasMessageContaining("RAW_INDEX_MODEL_BINDING");
    }
    @Test void rejectsMissingDuplicateAndUnreferencedRawRecords()throws Exception {
        ((ArrayNode)rawIndex.get("records")).removeAll();assertThatThrownBy(this::verify).hasMessageContaining("RAW_INDEX_COMPLETE");
        setup();((ArrayNode)rawIndex.get("records")).add(raw().deepCopy());assertThatThrownBy(this::verify).hasMessageContaining("RAW_INDEX_CANONICAL_ORDER");
        setup();((ObjectNode)events.get(0)).set("artifactRefs",JSON.createArrayNode());assertThatThrownBy(this::verify).hasMessageContaining("RAW_INDEX_EVENT_REFERENCE");
    }
    @Test void rejectsPayloadDigestChangesEvenWhenRecordMetadataWasNotChanged() {
        artifacts.get("raw/response.bin")[0]=1;assertThatThrownBy(this::verify).hasMessageContaining("RAW_INDEX_BYTES");
    }
    @TestFactory List<DynamicTest> rejectsIdentityCorruption() {
        Map<String,Runnable> edits=new LinkedHashMap<>();
        edits.put("ID_MAP_UUID_DUPLICATE",()->account().put("replayUuid",idMap.get("entries").get(2).get("replayUuid").asString()));
        edits.put("ID_MAP_ACCOUNT_SELF",()->account().put("ownerAccountId","account-3-01"));
        edits.put("ID_MAP_ACCOUNT_REFERENCE",()->account().put("ownerAccountId","account-missing"));
        edits.put("ID_MAP_ACCOUNT_COVERAGE",()->((ArrayNode)idMap.get("entries")).remove(1));
        edits.put("ID_MAP_CANONICAL_ORDER",()->((ArrayNode)idMap.get("entries")).add(account().deepCopy()));
        edits.put("SCHEMA_INVALID",()->account().put("targetUuid","00000000-0000-0000-0000-000000000001"));
        edits.put("EVIDENCE_DATASET_BINDING",()->idMap.put("datasetId","sha256:"+"0".repeat(64)));
        List<DynamicTest> tests=new ArrayList<>();edits.forEach((name,edit)->tests.add(DynamicTest.dynamicTest(name,()->{
            setup();edit.run();assertThatThrownBy(this::verify).hasMessageContaining(name);
        })));return tests;
    }
    @Test void rejectsUnownedDerivedIdentityAndAllowsExplicitOwnedWish() {
        ObjectNode wish=JSON.createObjectNode().put("entityKind","WISH").put("logicalId","wish-1")
            .put("replayUuid","11111111-2222-3333-4444-555555555555").put("ownerAccountId","account-3-01");
        ((ArrayNode)idMap.get("entries")).add(wish);assertThat(verify().identityMappings()).isEqualTo(202);
        wish.putNull("ownerAccountId");assertThatThrownBy(this::verify).hasMessageContaining("ID_MAP_ACCOUNT_REFERENCE");
    }
    @Test void cannotReportNonzeroExecutedCountForNotRunOrHideFailure()throws Exception {
        rule().put("checkedCount",1);assertThatThrownBy(this::verify).hasMessageContaining("VALIDATION_NOT_RUN_COUNT");
        setup();rule().put("status","FAIL");assertThatThrownBy(this::verify).hasMessageContaining("VALIDATION_ERROR_STATUS");
        setup();rule().put("status","PASS");((ArrayNode)rule().get("errors")).add(JSON.createObjectNode().put("code","bad").put("rule","domain-replay")
            .putNull("logicalId").putNull("occurredAt").put("message","failed").set("artifactRefs",JSON.createArrayNode()));
        assertThatThrownBy(this::verify).hasMessageContaining("VALIDATION_ERROR_STATUS");
    }
    @Test void rejectsReportDigestDriftDuplicateRulesAndMissingArtifacts()throws Exception {
        validation.put("schemaDigest","sha256:"+"f".repeat(64));assertThatThrownBy(this::verify).hasMessageContaining("VALIDATION_DIGEST_BINDING");
        setup();((ArrayNode)validation.get("rules")).add(rule().deepCopy());assertThatThrownBy(this::verify).hasMessageContaining("VALIDATION_RULE_DUPLICATE");
        setup();((ArrayNode)rule().get("artifactRefs")).add("raw/missing.bin");assertThatThrownBy(this::verify).hasMessageContaining("VALIDATION_ARTIFACT_REFERENCE");
    }
    @Test void fullReaderRetainsRawBytesAndRequiresEveryRawFileToHaveTypedEvidence()throws Exception {
        SimulationBundleReaderTest harness=new SimulationBundleReaderTest();harness.temp=temp;Path dir=harness.copy();
        String path="raw/response.bin";byte[] bytes=artifacts.get(path);
        Files.write(dir.resolve(path),bytes);
        ObjectNode m=harness.manifest(dir);ArrayNode files=(ArrayNode)m.get("files");
        files.add(JSON.createObjectNode().put("path",path).put("role","RAW").put("byteLength",bytes.length)
            .put("sha256",SimulationBundleReader.digest(bytes)).put("recordCount",1));
        List<JsonNode> ordered=new ArrayList<>();files.forEach(ordered::add);
        ordered.sort(Comparator.comparing(n->n.get("path").asString()));files.removeAll();ordered.forEach(files::add);
        for(JsonNode file:files)if(file.get("role").asString().equals("EVENTS"))((ObjectNode)file).put("recordCount",1);
        harness.save(dir,m);
        ObjectNode event=(ObjectNode)events.get(0);((ObjectNode)event.get("outcome")).put("resultRef",path);
        harness.replace(dir,"events.ndjson",(JSON.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8));
        harness.replace(dir,"raw/index.json",JSON.writeValueAsBytes(rawIndex));
        var admitted=harness.read(dir);
        assertThat(admitted.artifacts().get(path)).isEqualTo(bytes);
        admitted.artifacts().get(path)[0]^=1;
        assertThat(admitted.artifacts().get(path)).isEqualTo(bytes);
        Files.writeString(dir.resolve(path),"changed after admission");
        assertThat(admitted.artifacts().keySet()).contains(path);
        assertThatThrownBy(()->admitted.artifacts().get(path)).hasMessageContaining("CHECKSUM_MISMATCH");
        Files.write(dir.resolve(path),bytes);
        ((ArrayNode)rawIndex.get("records")).removeAll();
        harness.replace(dir,"raw/index.json",JSON.writeValueAsBytes(rawIndex));
        assertThatThrownBy(()->harness.read(dir)).hasMessageContaining("RAW_INDEX_COMPLETE");
    }
    @TestFactory List<DynamicTest> sharedEvidenceSchemaVectors()throws Exception {
        Map<String,JsonNode> documents=Map.of("id-map.json",load("id-map.json"),"raw/index.json",load("raw/index.json"),"validation.json",load("validation.json"));
        Map<String,String> definitions=Map.of("id-map.json","idMap","raw/index.json","rawIndex","validation.json","validation","raw-positive.json","rawIndex");
        List<DynamicTest> tests=new ArrayList<>();
        JsonNode positive=JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/evidence-raw-positive.json")));
        tests.add(DynamicTest.dynamicTest("typed-positive-raw-index",()->SimulationBundleReader.validate(schema.get("rawIndex"),positive,"rawIndex")));
        for(JsonNode vector:JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/evidence-schema-vectors.json")))) {
            tests.add(DynamicTest.dynamicTest(vector.get("name").asString(),()->{
                String file=vector.get("file").asString();JsonNode document=(file.equals("raw-positive.json")?positive:documents.get(file)).deepCopy();
                String pointer=vector.get("path").asString();int slash=pointer.lastIndexOf('/');
                ((ObjectNode)document.at(pointer.substring(0,slash))).set(pointer.substring(slash+1),vector.get("value"));
                assertThatThrownBy(()->SimulationBundleReader.validate(schema.get(definitions.get(file)),document,file)).isInstanceOf(SimulationBundleReader.Rejection.class);
            }));
        }
        return tests;
    }
    @Test void readerRejectsMalformedMetadataAfterTransportChecksumsAreRefreshed()throws Exception {
        SimulationBundleReaderTest harness=new SimulationBundleReaderTest();
        harness.temp=temp;Path dir=harness.copy();
        ObjectNode map=load("id-map.json");((ObjectNode)map.get("entries").get(1)).put("ownerAccountId","missing-account");
        harness.replace(dir,"id-map.json",JSON.writeValueAsBytes(map));
        assertThatThrownBy(()->harness.read(dir)).hasMessageContaining("ID_MAP_ACCOUNT_REFERENCE");
    }
}
