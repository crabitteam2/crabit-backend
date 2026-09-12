package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationBundleReaderTest {
    static final Path SCHEMA=Path.of("api/demo-simulation-v1.schema.json");
    static final Path FIXTURE=Path.of("src/test/resources/simulation/bundle-contract-valid");
    static final JsonMapper JSON=JsonMapper.builder().build();
    @TempDir Path temp;
    Path copy() throws Exception {
        Path dir=Files.createTempDirectory(temp,"bundle-");
        try(var files=Files.walk(FIXTURE)) { for(Path p:files.toList()) {
            Path dest=dir.resolve(FIXTURE.relativize(p));
            if(Files.isDirectory(p))Files.createDirectories(dest);else Files.copy(p,dest);
        }}
        return dir;
    }
    ObjectNode manifest(Path dir)throws Exception {return (ObjectNode)JSON.readTree(Files.readAllBytes(dir.resolve("manifest.json")));}
    void save(Path dir,ObjectNode m)throws Exception {
        m.put("datasetId",SimulationBundleReader.datasetIdentity(m));
        Files.write(dir.resolve("manifest.json"),JSON.writeValueAsBytes(m));
    }
    void replace(Path dir,String name,byte[] bytes)throws Exception {
        Files.write(dir.resolve(name),bytes); ObjectNode m=manifest(dir);
        for(JsonNode file:m.get("files"))if(file.get("path").asString().equals(name)) {
            ((ObjectNode)file).put("byteLength",bytes.length).put("sha256",SimulationBundleReader.digest(bytes));
        }
        // Deliberately refresh transport and config identity so a bad semantic input is not masked by a hash error.
        if(name.equals("config.json")) {
            try {m.put("configDigest",SimulationBundleReader.digest(SimulationBundleReader.canonical(JSON.readTree(bytes)).getBytes(StandardCharsets.UTF_8)));}
            catch(RuntimeException ignored) { }
        }
        save(dir,m);
    }
    SimulationBundleReader.AdmittedBundle read(Path dir)throws Exception {
        return new SimulationBundleReader().read(dir,SCHEMA,SimulationBundleReader.digest(Files.readAllBytes(dir.resolve("manifest.json"))));
    }
    @Test void admitsIndependentlyEncodedFixtureAndReturnsIsolatedBytes() throws Exception {
        Path dir=copy(); var result=read(dir);
        assertThat(result.artifacts()).hasSize(10);
        assertThat(result.datasetId()).isEqualTo("sha256:788a0b350face4bd933002e304d7a8aa0447b31cd0661f5b21a989d099898089");
        byte[] original=result.artifacts().get("config.json"); result.artifacts().get("config.json")[0]=0;
        Files.writeString(dir.resolve("config.json"),"changed");
        assertThat(result.artifacts().get("config.json")).isEqualTo(original);
    }
    @TestFactory List<DynamicTest> sharedRejectionVectors() throws Exception {
        List<DynamicTest> tests=new ArrayList<>();
        for(JsonNode vector:JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/rejection-vectors.json")))) {
            tests.add(DynamicTest.dynamicTest(vector.get("name").asString(),()->{
                Path dir=copy(); String name=vector.get("file").asString();
                JsonNode document=JSON.readTree(Files.readAllBytes(dir.resolve(name)));String pointer=vector.get("path").asString();
                int split=pointer.lastIndexOf('/');ObjectNode parent=(ObjectNode)document.at(pointer.substring(0,split));
                parent.set(pointer.substring(split+1),vector.get("value"));
                if(name.equals("manifest.json")) save(dir,(ObjectNode)document);
                else replace(dir,name,JSON.writeValueAsBytes(document));
                assertThatThrownBy(()->read(dir)).isInstanceOf(SimulationBundleReader.Rejection.class).hasMessageStartingWith(vector.get("code").asString()+":");
            }));
        }
        return tests;
    }
    @Test void rejectsMissingAndUnlistedArtifacts()throws Exception {
        Path dir=copy();Files.writeString(dir.resolve("extra.json"),"{}");
        assertThatThrownBy(()->read(dir)).hasMessageContaining("unlisted bundle entry");
        Files.delete(dir.resolve("extra.json"));Files.delete(dir.resolve("events.ndjson"));
        assertThatThrownBy(()->read(dir)).hasMessageContaining("regular artifact file");
    }
    @Test void rejectsSymlinkEvenWhenBytesMatchAndSymlinkAncestor()throws Exception {
        Path dir=copy();Path outside=temp.resolve("outside.json");Files.writeString(outside,"[]\n");
        Files.delete(dir.resolve("state/export.json"));Files.createSymbolicLink(dir.resolve("state/export.json"),outside);
        assertThatThrownBy(()->read(dir)).hasMessageContaining("without symlink ancestors");
        Files.delete(dir.resolve("state/export.json"));Files.delete(dir.resolve("state"));
        Files.createSymbolicLink(dir.resolve("state"),temp);
        assertThatThrownBy(()->read(dir)).hasMessageContaining("without symlink ancestors");
    }
    @Test void rejectsCorruptionAndWrongExpectedIdentity()throws Exception {
        Path dir=copy();Files.writeString(dir.resolve("events.ndjson"),"{}\n");
        assertThatThrownBy(()->read(dir)).hasMessageStartingWith("CHECKSUM_MISMATCH:");
        assertThatThrownBy(()->new SimulationBundleReader().read(dir,SCHEMA,"sha256:"+"0".repeat(64))).hasMessageContaining("manifest bytes");
    }
    @Test void rejectsDuplicateKeysTrailingJsonAndMissingRequiredProperties()throws Exception {
        for(String document:List.of("{\"schemaVersion\":1,\"schemaVersion\":1}","{} {}","{}")) {
            Path dir=copy();Files.writeString(dir.resolve("manifest.json"),document);
            assertThatThrownBy(()->read(dir)).hasMessageStartingWith("SCHEMA_INVALID:");
        }
    }
    @Test void rejectsStaleDatasetIdentityDespiteValidTransportDigest()throws Exception {
        Path dir=copy();ObjectNode m=manifest(dir);((ObjectNode)m.get("codeShas")).put("crabit-data","1".repeat(40));
        Files.write(dir.resolve("manifest.json"),JSON.writeValueAsBytes(m));
        assertThatThrownBy(()->read(dir)).hasMessageContaining("dataset identity");
    }
    @Test void rejectsStaleConfigDigestEvenIfFileHashIsFresh()throws Exception {
        Path dir=copy();ObjectNode config=(ObjectNode)JSON.readTree(Files.readAllBytes(dir.resolve("config.json")));
        String old=manifest(dir).get("configDigest").asString();config.put("seed",12);replace(dir,"config.json",JSON.writeValueAsBytes(config));
        ObjectNode m=manifest(dir);m.put("configDigest",old);save(dir,m);
        assertThatThrownBy(()->read(dir)).hasMessageContaining("canonical config digest");
    }
    @Test void rejectsMalformedArtifactJsonEvenWithUpdatedChecksum()throws Exception {
        Path dir=copy();replace(dir,"config.json","{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->read(dir)).hasMessageContaining("strict JSON parsing");
    }
    @Test void rejectsNonUtf8AndInvalidUnicodeInsteadOfSilentlyRepairingBytes() throws Exception {
        List<byte[]> invalid = List.of("{}".getBytes(StandardCharsets.UTF_16),
            new byte[]{'{','"','a','"',':','"',(byte)0xc3,(byte)0x28,'"','}'},
            ("{\"a\":\"" + "\\u" + "d800\"}").getBytes(StandardCharsets.UTF_8));
        for (byte[] bytes : invalid) {
            Path dir=copy();replace(dir,"config.json",bytes);
            assertThatThrownBy(()->read(dir)).hasMessageStartingWith("SCHEMA_INVALID:");
        }
    }
    @Test void rejectsUnsortedFilesCaseCollisionsAndUnlistedDirectories()throws Exception {
        Path dir=copy();ObjectNode m=manifest(dir);var files=(tools.jackson.databind.node.ArrayNode)m.get("files");
        JsonNode first=files.get(0);files.set(0,files.get(1));files.set(1,first);save(dir,m);
        assertThatThrownBy(()->read(dir)).hasMessageContaining("sorted by path");
        Path other=copy();Files.createDirectory(other.resolve("empty"));
        assertThatThrownBy(()->read(other)).hasMessageContaining("unlisted bundle directory");
    }
    @Test void rejectsCaseCollidingRawPathsEvenWithMatchingHashes()throws Exception {
        Path dir=copy();Files.createDirectories(dir.resolve("raw"));ObjectNode m=manifest(dir);
        var files=(tools.jackson.databind.node.ArrayNode)m.get("files");
        for(String name:List.of("raw/A.bin","raw/a.bin")) {
            byte[] bytes={0,1,2};Files.write(dir.resolve(name),bytes);
            files.add(JSON.createObjectNode().put("path",name).put("role","RAW").put("byteLength",3)
                .put("sha256",SimulationBundleReader.digest(bytes)).put("recordCount",1));
        }
        List<JsonNode> sorted=new ArrayList<>();files.forEach(sorted::add);sorted.sort(Comparator.comparing(n->n.get("path").asString()));
        files.removeAll();sorted.forEach(files::add);save(dir,m);
        assertThatThrownBy(()->read(dir)).hasMessageContaining("unique artifact path including case");
    }
    @Test void rejectsWrongLogicalDigestAndDoesNotFoldItIntoDatasetIdentity()throws Exception {
        Path dir=copy();ObjectNode m=manifest(dir);String original=m.get("datasetId").asString();
        m.put("logicalDigest","sha256:"+"0".repeat(64));save(dir,m);
        assertThat(manifest(dir).get("datasetId").asString()).isEqualTo(original);
        assertThatThrownBy(()->read(dir)).hasMessageContaining("normalized projection digest");
    }
    @Test void ndjsonCountsAttemptsAndRejectsBlankLines()throws Exception {
        Path dir=copy();
        SimulationEventTimelineTest factory=new SimulationEventTimelineTest();
        ObjectNode first=factory.join();
        first.set("artifactRefs",JSON.createArrayNode().add("validation.json"));
        ((ObjectNode)first.get("outcome")).put("resultRef","validation.json");
        ObjectNode second=first.deepCopy().put("eventId","event-2").put("sequence",2);
        ((ObjectNode)second.get("outcome")).put("status","REJECTED");
        String lines=JSON.writeValueAsString(first)+"\n"+JSON.writeValueAsString(second)+"\n";
        replace(dir,"events.ndjson",lines.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->read(dir)).hasMessageContaining("record count");
        ObjectNode m=manifest(dir);for(JsonNode file:m.get("files"))if(file.get("role").asString().equals("EVENTS"))((ObjectNode)file).put("recordCount",2);save(dir,m);
        assertThat(read(dir).artifacts().get("events.ndjson")).isEqualTo(lines.getBytes(StandardCharsets.UTF_8));
        replace(dir,"events.ndjson",lines.replaceFirst("\n","\n\n").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->read(dir)).hasMessageContaining("blank NDJSON record");
        second.set("causes",JSON.createArrayNode().add("future-event"));
        replace(dir,"events.ndjson",(JSON.writeValueAsString(first)+"\n"+JSON.writeValueAsString(second)+"\n").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->read(dir)).hasMessageContaining("CAUSE_NOT_EARLIER");
    }
    @Test void canonicalNumbersCannotLosePrecisionOrCoerceFractions() {
        for(String invalid:List.of("1.0","9007199254740992","-9007199254740992"))
            assertThatThrownBy(()->SimulationBundleReader.canonical(JSON.readTree(invalid))).hasMessageContaining("canonical safe integer");
    }
    @Test void mandatoryWeeklyCloseMetadataPassesFormerFileAndManifestCaps() throws Exception {
        Path dir=copy();ObjectNode m=manifest(dir);var entries=new ArrayList<JsonNode>();m.get("files").forEach(entries::add);
        int mandatoryRawFiles=80*14*11;
        for(int i=0;i<mandatoryRawFiles;i++)entries.add(JSON.createObjectNode()
            .put("path",String.format(java.util.Locale.ROOT,"raw/closure-%05d.json",i)).put("role","RAW").put("byteLength",0)
            .put("sha256",SimulationBundleReader.digest(new byte[0])).put("recordCount",1));
        entries.sort(Comparator.comparing(e->e.get("path").asString()));m.set("files",JSON.valueToTree(entries));save(dir,m);
        assertThat(entries.size()).isGreaterThan(4096);
        assertThat(Files.size(dir.resolve("manifest.json"))).isGreaterThan(1024*1024).isLessThan(SimulationBundleReader.MAX_MANIFEST_BYTES);
        // Metadata is admitted; the first deliberately absent immutable RAW file still fails closed.
        assertThatThrownBy(()->read(dir)).hasMessageContaining("regular artifact file");
    }
    @Test void sharedLimitsRemainFiniteAndWorstCaseManifestMetadataFits() throws Exception {
        JsonNode schema=JSON.readTree(Files.readAllBytes(SCHEMA)),files=schema.get("properties").get("files");
        assertThat(files.get("maxItems").asInt()).isEqualTo(SimulationBundleReader.MAX_FILES);
        assertThat(files.get("items").get("properties").get("byteLength").get("maximum").asInt()).isEqualTo(SimulationBundleReader.MAX_ARTIFACT_BYTES);
        assertThat(schema.get("$defs").get("rawIndex").get("properties").get("records").get("items").get("properties").get("byteLength").get("maximum").asInt())
            .isEqualTo(SimulationBundleReader.MAX_ARTIFACT_BYTES);
        var largest=JSON.createObjectNode().put("path","a".repeat(240)).put("role","NORMALIZED")
            .put("byteLength",SimulationBundleReader.MAX_ARTIFACT_BYTES).put("sha256","sha256:"+"f".repeat(64)).put("recordCount",1000000);
        SimulationBundleReader.validate(files.get("items"),largest,"file");
        long worstManifest=65536L+(JSON.writeValueAsBytes(largest).length+1L)*SimulationBundleReader.MAX_FILES;
        assertThat(worstManifest).isLessThanOrEqualTo(SimulationBundleReader.MAX_MANIFEST_BYTES);
        // Headroom model, not a claim of the DATA policy's actual event counts or measured memory.
        long closeAndVisitFiles=2000L*13+100L*102*(16+2)+10;
        assertThat(closeAndVisitFiles).isLessThan(SimulationBundleReader.MAX_FILES);
        var tooMany=JSON.createArrayNode();for(int i=0;i<=SimulationBundleReader.MAX_FILES;i++)tooMany.add(largest);
        assertThatThrownBy(()->SimulationBundleReader.validate(files,tooMany,"files")).hasMessageStartingWith("SCHEMA_INVALID:");
        largest.put("byteLength",SimulationBundleReader.MAX_ARTIFACT_BYTES+1L);
        assertThatThrownBy(()->SimulationBundleReader.validate(files.get("items"),largest,"file")).hasMessageStartingWith("SCHEMA_INVALID:");
    }
    @Test void aggregateLimitUsesLongAndRejectsBeforeLoadingGigabytes() throws Exception {
        Path dir=copy();var m=manifest(dir);var files=m.putArray("files");
        int count=(int)(SimulationBundleReader.MAX_TOTAL_BYTES/SimulationBundleReader.MAX_ARTIFACT_BYTES);
        assertThat(SimulationBundleReader.MAX_TOTAL_BYTES).isGreaterThan(Integer.MAX_VALUE);
        for(int i=0;i<count;i++)files.addObject().put("path",String.format(java.util.Locale.ROOT,"raw/%03d.json",i))
            .put("role","RAW").put("byteLength",SimulationBundleReader.MAX_ARTIFACT_BYTES).put("sha256","sha256:"+"f".repeat(64)).put("recordCount",1);
        save(dir,m);
        assertThatThrownBy(()->read(dir)).hasMessageContaining("regular artifact file");
        files.addObject().put("path","raw/overflow.json").put("role","RAW").put("byteLength",1).put("sha256","sha256:"+"f".repeat(64)).put("recordCount",1);
        save(dir,m);
        assertThatThrownBy(()->read(dir)).hasMessageContaining("total bundle byte limit");
    }

}
