package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationFeedReplayIT {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String DATASET="sha256:"+"c".repeat(64),TOKEN="local-feed-replay-test";
    @TempDir Path temp;
    JsonNode schema() throws Exception {return JSON.readTree(Files.readAllBytes(Path.of("api/demo-simulation-v1.schema.json")));}
    JsonNode people() throws Exception {return JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/bundle-contract-valid/students.json")));}
    JsonNode read(Path p) throws Exception {return JSON.readTree(Files.readAllBytes(p));}
    List<byte[]> events(boolean cards) {
        var f=new SimulationFeedContinuationIT();List<JsonNode> events=new ArrayList<>();
        for(int i=0;i<2;i++)events.add(f.event(i+1,"JOIN","student-3-0"+i,0,Map.of("studentId","student-3-0"+i,"accountId","account-3-0"+i,"academyId","academy-1","grade",3)));
        if(cards)for(int i=0;i<2;i++) {
            var c=new HashMap<String,Object>();c.put("accountId","account-3-01");c.put("wishId","wish-"+i);c.put("idempotencyKey","create-"+i);
            c.put("purpose","책 모으기");c.put("targetAmount",5000);c.put("startDate",null);c.put("targetDate",null);c.put("photoId",null);
            events.add(f.event(3+2*i,"CREATE","student-3-01",1+2*i,c));
            events.add(f.event(4+2*i,"SHARE","student-3-01",2+2*i,Map.of("accountId","account-3-01","wishId","wish-"+i,"expectedVersion",0,"visibility","ACADEMY")));
        }
        int n=events.size()+1;var first=f.feed("first","e6",null);
        if(!cards)first.put("orderedCardIds",List.of());
        events.add(f.event(n,"FEED_QUERY","student-3-00",5,first));
        if(cards) {
            var next=f.event(n+1,"FEED_QUERY","student-3-00",6,f.feed("second","e4","event:e"+n+":nextCursor"));
            next.putArray("causes").add("e"+n);events.add(next);
            var again=f.event(n+2,"FEED_QUERY","student-3-00",7,f.feed("again","e4","event:e"+n+":nextCursor"));
            again.putArray("causes").add("e"+n);events.add(again);
        }
        return events.stream().map(JSON::writeValueAsBytes).toList();
    }
    record Python(Process process,URI endpoint) implements AutoCloseable {
        public void close() throws Exception {process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}process.inputReader().close();}
    }
    Python python() throws Exception {
        Path root=Path.of(System.getenv().getOrDefault("CRABIT_SIMULATION_DATA_ROOT","../crabit-data")).toRealPath();
        var b=new ProcessBuilder(System.getenv().getOrDefault("CRABIT_SIMULATION_PYTHON","python3"),"-u","-m","feed_service","--host","127.0.0.1","--port","0").directory(root.toFile());
        b.environment().putAll(Map.of("FEED_RANKING_CREDENTIAL",TOKEN,"PYTHONDONTWRITEBYTECODE","1"));
        b.redirectError(temp.resolve("python-stderr.txt").toFile());var p=b.start();
        try {
            String ready=CompletableFuture.supplyAsync(()->{try{return p.inputReader().readLine();}catch(Exception e){throw new CompletionException(e);}}).get(10,TimeUnit.SECONDS);
            return new Python(p,URI.create("http://127.0.0.1:"+JSON.readTree(ready).get("port").asInt()+"/internal/v1/feed-rankings"));
        }catch(Throwable e){p.destroyForcibly();throw e;}
    }
    void verifyIndex(Path output) throws Exception {
        var index=read(output.resolve("raw/index.json"));
        SimulationBundleReader.validate(schema().get("$defs").get("rawIndex"),index,"rawIndex");
        for(var r:index.get("records")) {
            byte[] bytes=Files.readAllBytes(output.resolve(r.get("path").asString()));
            assertThat(r.get("sha256").asString()).isEqualTo(SimulationBundleReader.digest(bytes));
            assertThat(r.get("byteLength").asLong()).isEqualTo(bytes.length);
            assertThat(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(TOKEN);
        }
    }
    void copy(Path source,String name) throws Exception {
        Path target=Path.of("build/simulation-feed-replay",name);Files.createDirectories(target);
        try(var files=Files.walk(source)){for(var file:files.filter(Files::isRegularFile).toList()){
            Path to=target.resolve(source.relativize(file));Files.createDirectories(to.getParent());Files.copy(file,to,StandardCopyOption.REPLACE_EXISTING);
        }}
    }
    @Test void realRankingAndContinuationKeepExactPythonBytesAndNormalizeAcrossIndependentDatabases() throws Exception {
        Path output=temp.resolve("recommended");var inputs=events(true);
        try(var python=python()) {
            SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,output,null,
                new SimulationReplayRun.FeedOptions(python.endpoint(),TOKEN));
            Path second=temp.resolve("recommended-second");
            SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,second,null,
                new SimulationReplayRun.FeedOptions(python.endpoint(),TOKEN));
            for(String file:List.of("normalized-feed.json","normalized-backend.json","normalized-relational.json","normalized-responses.json"))
                assertThat(Files.readAllBytes(second.resolve(file))).as(file).isEqualTo(Files.readAllBytes(output.resolve(file)));
            assertThat(Files.readAllBytes(second.resolve("raw/feed/event-7-request.json"))).isNotEqualTo(Files.readAllBytes(output.resolve("raw/feed/event-7-request.json")));
            verifyIndex(second);copy(second,"recommended-second");
        }
        var report=read(output.resolve("replay-observation.json"));
        assertThat(report.get("completedEvents").asInt()).isEqualTo(9);
        assertThat(report.get("status").asString()).isEqualTo("REPLAYED_PARTIAL_VALIDATION");
        assertThat(report.get("feedHttpAttempts").asInt()).isEqualTo(1);
        assertThat(report.get("feedHttpResponses").asInt()).isEqualTo(1);
        assertThat(report.get("feedPagesCaptured").asInt()).isEqualTo(3);
        assertThat(report.get("pythonInvoked").asBoolean()).isTrue();
        assertThat(report.get("feedExchangeNormalizationPerformed").asBoolean()).isTrue();
        assertThat(report.get("readyForApplication").asBoolean()).isFalse();
        assertThat(output.resolve("normalized-backend.json")).exists();
        for(String part:List.of("request","response","http","page","page-source","page-verification","composition-verification","ranking-verification"))
            assertThat(Files.readAllBytes(output.resolve("raw/feed/event-7-"+part+".json")))
                .isEqualTo(Files.readAllBytes(output.resolve("feed-execution/event-7/"+part+".json")));
        assertThat(Files.readAllBytes(output.resolve("raw/first-request.json"))).isEqualTo(inputs.get(6));
        assertThat(read(output.resolve("raw/feed/event-7-http.json")).get("status").asInt()).isEqualTo(200);
        var ranked=read(output.resolve("raw/feed/event-7-response.json"));
        var first=read(output.resolve("raw/first-response.json"));
        var second=read(output.resolve("raw/second-response.json"));
        assertThat(first.get("sortSource").asString()).isEqualTo("RECOMMENDATION");
        assertThat(first.get("items").get(0).get("sharedCardId")).isEqualTo(ranked.get("ordered_card_ids").get(0));
        assertThat(second.get("items").get(0).get("sharedCardId")).isEqualTo(ranked.get("ordered_card_ids").get(1));
        for(int seq:List.of(8,9)) {
            assertThat(output.resolve("raw/feed/event-"+seq+"-request.json")).doesNotExist();
            assertThat(output.resolve("raw/feed/event-"+seq+"-response.json")).doesNotExist();
            assertThat(read(output.resolve("raw/feed/event-"+seq+"-page-verification.json")).get("persistedPageVerified").asBoolean()).isTrue();
            assertThat(read(output.resolve("raw/feed/event-"+seq+"-page.json")).get("pythonInvoked").asBoolean()).isFalse();
        }
        verifyIndex(output);copy(output,"recommended");
    }
    @Test void mismatchedExpectedOrderStillIndexesTheActualPythonAndPageEvidence() throws Exception {
        Path output=temp.resolve("mismatch");var inputs=new ArrayList<>(events(true));
        var event=(ObjectNode)JSON.readTree(inputs.get(6));
        ((ObjectNode)event.get("command")).putArray("orderedCardIds").add("e4");
        inputs.set(6,JSON.writeValueAsBytes(event));
        try(var python=python()) {
            assertThatThrownBy(()->SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,output,null,
                new SimulationReplayRun.FeedOptions(python.endpoint(),TOKEN))).hasMessage("FEED_ORDER_MISMATCH:e7");
        }
        var report=read(output.resolve("replay-observation.json"));
        assertThat(report.get("completedEvents").asInt()).isEqualTo(6);
        assertThat(report.get("feedHttpResponses").asInt()).isEqualTo(1);
        assertThat(report.get("feedPagesCaptured").asInt()).isEqualTo(1);
        assertThat(read(output.resolve("raw/feed/event-7-http.json")).get("status").asInt()).isEqualTo(200);
        assertThat(read(output.resolve("raw/results/7.json")).get("sortSource").asString()).isEqualTo("RECOMMENDATION");
        assertThat(output.resolve("raw/second-request.json")).doesNotExist();
        verifyIndex(output);copy(output,"mismatch");
    }
    @Test void recordedCardAllocationPreservesUuidTieOrderAcrossFreshDatabases() throws Exception {
        var inputs=new ArrayList<>(events(true));
        String tied=JSON.readTree(inputs.get(5)).get("occurredAt").asString();
        for(int index:List.of(3,4)) {
            var event=(ObjectNode)JSON.readTree(inputs.get(index));event.put("occurredAt",tied);
            inputs.set(index,JSON.writeValueAsBytes(event));
        }
        String lower="11111111-1111-4111-8111-111111111111",higher="eeeeeeee-eeee-4eee-beee-eeeeeeeeeeee";
        var mapping=JSON.createObjectNode().put("schemaVersion",1).put("schemaKind","demo-simulation-id-map").put("datasetId",DATASET);
        var entries=mapping.putArray("entries");
        for(var pair:Map.of("e4",lower,"e6",higher).entrySet())
            entries.addObject().put("entityKind","SHARED_CARD").put("logicalId",pair.getKey())
                .put("replayUuid",pair.getValue()).put("ownerAccountId","account-3-01");
        Path first=temp.resolve("tied-first"),second=temp.resolve("tied-second");
        try(var python=python()) {
            for(Path output:List.of(first,second)) {
                var report=SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,output,null,
                    new SimulationReplayRun.FeedOptions(python.endpoint(),TOKEN,java.time.Duration.ofSeconds(30)),mapping);
                assertThat(report.get("completedEvents")).isEqualTo(9);
                assertThat(report.get("sharedCardIdentityAllocation")).isEqualTo("RECORDED_SOURCE_IDS");
                assertThat(report.get("recordedSharedCardIdentities")).isEqualTo(2);
                var request=read(output.resolve("raw/feed/event-7-request.json"));
                assertThat(request.get("candidates").get(0).get("card_id").asString()).isEqualTo(higher);
                assertThat(request.get("candidates").get(1).get("card_id").asString()).isEqualTo(lower);
                assertThat(read(output.resolve("raw/first-response.json")).get("items").get(0).get("sharedCardId").asString()).isEqualTo(higher);
                assertThat(read(output.resolve("raw/second-response.json")).get("items").get(0).get("sharedCardId").asString()).isEqualTo(lower);
                verifyIndex(output);
            }
        }
        for(String file:List.of("normalized-feed.json","normalized-backend.json","normalized-relational.json","normalized-responses.json"))
            assertThat(Files.readAllBytes(first.resolve(file))).as(file).isEqualTo(Files.readAllBytes(second.resolve(file)));
        assertThat(read(first.resolve("raw/feed/event-7-request.json")).get("candidates").get(0).get("author_id"))
            .isNotEqualTo(read(second.resolve("raw/feed/event-7-request.json")).get("candidates").get(0).get("author_id"));
        assertThat(read(first.resolve("replay-observation.json")).get("validationPreservationBefore").get("database"))
            .isNotEqualTo(read(second.resolve("replay-observation.json")).get("validationPreservationBefore").get("database"));
        var bad=mapping.deepCopy();((ObjectNode)bad.get("entries").get(0)).put("ownerAccountId","account-3-00");
        assertThatThrownBy(()->SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,temp.resolve("bad-owner"),null,null,bad))
            .hasMessage("REPLAY_CARD_ID_OWNER");
        assertThat(temp.resolve("bad-owner")).doesNotExist();
    }
    @Test void controlledReplayBudgetPreservesAnActualSlowPythonResponseWithoutChangingServingDefaults() throws Exception {
        Path output=temp.resolve("slow-functional-replay");
        var proxy=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        try(var python=python();var client=java.net.http.HttpClient.newHttpClient()) {
            proxy.createContext("/internal/v1/feed-rankings",exchange -> {
                try {
                    byte[] body=exchange.getRequestBody().readAllBytes();
                    var request=java.net.http.HttpRequest.newBuilder(python.endpoint())
                        .header("Authorization",exchange.getRequestHeaders().getFirst("Authorization"))
                        .header("Content-Type","application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)).build();
                    var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                    Thread.sleep(700);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(response.statusCode(),response.body().length);
                    exchange.getResponseBody().write(response.body());
                } catch(InterruptedException e) {Thread.currentThread().interrupt();}
                finally {exchange.close();}
            });
            proxy.start();
            URI endpoint=URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()+"/internal/v1/feed-rankings");
            var result=SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),events(true),output,null,
                new SimulationReplayRun.FeedOptions(endpoint,TOKEN,java.time.Duration.ofSeconds(30)));
            assertThat(result.get("completedEvents")).isEqualTo(9);
            assertThat(result.get("feedDeadlineBudgetMillis")).isEqualTo(30000L);
            assertThat(result.get("servingLatencyPolicyValidated")).isEqualTo(false);
            assertThat(read(output.resolve("raw/first-response.json")).get("sortSource").asString()).isEqualTo("RECOMMENDATION");
            assertThat(read(output.resolve("raw/feed/event-7-http.json")).get("status").asInt()).isEqualTo(200);
            assertThat(new SimulationReplayRun.FeedOptions(endpoint,TOKEN).deadlineBudget).isEqualTo(java.time.Duration.ofMillis(500));
            assertThatThrownBy(()->new SimulationReplayRun.FeedOptions(endpoint,TOKEN,java.time.Duration.ZERO)).hasMessage("FEED_REPLAY_BUDGET");
            verifyIndex(output);
        } finally {proxy.stop(0);}
    }
    @Test void actualUnauthorizedFallbackIsRecordedWithoutClaimingRecommendation() throws Exception {
        Path output=temp.resolve("unauthorized");
        try(var python=python()) {
            var result=SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),events(true),output,null,
                new SimulationReplayRun.FeedOptions(python.endpoint(),"incorrect"));
            assertThat(result.get("status")).isEqualTo("REPLAYED_PARTIAL_VALIDATION");
            assertThat(result.get("feedHttpAttempts")).isEqualTo(1);
            assertThat(result.get("feedPagesCaptured")).isEqualTo(3);
            assertThat(result.get("readyForApplication")).isEqualTo(false);
        }
        assertThat(read(output.resolve("raw/feed/event-7-http.json")).get("status").asInt()).isEqualTo(401);
        assertThat(read(output.resolve("raw/first-response.json")).get("sortSource").asString()).isEqualTo("LATEST");
        verifyIndex(output);copy(output,"unauthorized");
    }
    @Test void emptyCandidatesProduceOnlyPageEvidenceAndNoFabricatedHttp() throws Exception {
        Path output=temp.resolve("empty");
        var result=SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),events(false),output,null,
            new SimulationReplayRun.FeedOptions(URI.create("http://127.0.0.1:1/internal/v1/feed-rankings"),TOKEN));
        assertThat(result.get("feedHttpAttempts")).isEqualTo(0);
        assertThat(result.get("feedHttpResponses")).isEqualTo(0);
        assertThat(result.get("pythonInvoked")).isEqualTo(false);
        assertThat(result.get("feedPagesCaptured")).isEqualTo(1);
        assertThat(output.resolve("raw/feed/event-3-request.json")).doesNotExist();
        assertThat(read(output.resolve("raw/feed/event-3-page-verification.json")).get("itemCount").asInt()).isZero();
        verifyIndex(output);
    }
    @Test void reservedFeedPathsCollideBeforeDatabaseOrOutputCreation() throws Exception {
        for(String part:List.of("request","response","http","page","page-source","page-verification","composition-verification","ranking-verification")) {
            var inputs=new ArrayList<>(events(false));var feed=(ObjectNode)JSON.readTree(inputs.get(2));
            ((ObjectNode)feed.get("command")).put("requestRef","raw/feed/event-3-"+part+".json");
            inputs.set(2,JSON.writeValueAsBytes(feed));Path output=temp.resolve(part);
            assertThatThrownBy(()->SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,output,null,
                new SimulationReplayRun.FeedOptions(URI.create("http://127.0.0.1:1/internal/v1/feed-rankings"),TOKEN)))
                .hasMessage("REPLAY_RAW_PATH_COLLISION");
            assertThat(output).doesNotExist();
        }
    }
    @Test void configurationRejectsExternalEndpointsAndMalformedCredentials() {
        assertThatThrownBy(()->new SimulationReplayRun.FeedOptions(URI.create("https://example.com/internal/v1/feed-rankings"),TOKEN))
            .hasMessage("FEED_LOCAL_ENDPOINT_REQUIRED");
        for(String token:List.of(""," ","a\nb","a\rb"))
            assertThatThrownBy(()->new SimulationReplayRun.FeedOptions(URI.create("http://127.0.0.1:1/internal/v1/feed-rankings"),token))
                .hasMessage("FEED_CREDENTIAL_REQUIRED");
    }
}
