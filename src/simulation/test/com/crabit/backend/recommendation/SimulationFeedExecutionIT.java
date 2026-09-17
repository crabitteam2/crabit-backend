package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import com.crabit.backend.simulation.*;
import com.crabit.backend.wish.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class SimulationFeedExecutionIT {
    @TempDir Path temp;
    static final String DATASET="sha256:"+"e".repeat(64), TOKEN="local-simulation-feed-test";
    static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    record Owner(UUID academy,UUID student,UUID account) {}
    Owner seed(SimulationDomainRuntime runtime) {
        var viewer=new Owner(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        runtime.executeAt(SimulationCashOracle.START,s->{
            var j=s.jdbc();var at=Timestamp.from(s.clock().instant());
            j.update("INSERT INTO academy(id,name) VALUES (?,'local feed')",viewer.academy());
            j.update("INSERT INTO demo_simulation_dataset(dataset_id,manifest_digest,state,starts_at,ends_at) VALUES (?,?,'BUILDING',?,?)",DATASET,DATASET,at,Timestamp.from(SimulationCashOracle.END));
            member(s,viewer,"viewer");return null;
        });return viewer;
    }
    void member(SimulationDomainRuntime.Services s,Owner o,String label) {
        var j=s.jdbc();var at=Timestamp.from(s.clock().instant());
        j.update("INSERT INTO student(id,nickname,age,age_provenance) VALUES (?,?,9,'PROVIDED')",o.student(),label);
        j.update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,?)",UUID.randomUUID(),o.student(),o.academy(),at);
        j.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",o.account(),o.student(),o.academy(),at);
        j.update("INSERT INTO demo_simulation_account(account_id,dataset_id,logical_student_id,logical_account_id,grade,is_owner) VALUES (?,?,?,?,3,false)",o.account(),DATASET,label,label+"-account");
    }
    UUID author(SimulationDomainRuntime runtime,Owner viewer) {
        var author=new Owner(viewer.academy(),UUID.randomUUID(),UUID.randomUUID());
        return runtime.executeAt(SimulationCashOracle.START.plusSeconds(1),s->{
            member(s,author,"author");var life=s.service(WishLifecycleService.class);
            var wish=life.create(author.student(),author.academy(),author.account(),"create","책 모으기",10000,(LocalDate)null).wish();
            life.patch(author.student(),author.academy(),author.account(),wish.id(),wish.version(),new WishPatch(null,null,false,null,WishVisibility.ACADEMY));
            return s.jdbc().queryForObject("SELECT id FROM shared_card WHERE wish_id=?",UUID.class,wish.id());
        });
    }
    record Python(Process process,URI endpoint) implements AutoCloseable {
        @Override public void close() throws Exception {process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}process.inputReader().close();}
    }
    Python python() throws Exception {
        Path root=Path.of(System.getenv().getOrDefault("CRABIT_SIMULATION_DATA_ROOT","../crabit-data")).toRealPath();
        assertThat(root.resolve("feed_service/__main__.py")).isRegularFile();
        var builder=new ProcessBuilder(System.getenv().getOrDefault("CRABIT_SIMULATION_PYTHON","python3"),"-u","-m","feed_service","--host","127.0.0.1","--port","0").directory(root.toFile());
        builder.environment().putAll(Map.of("FEED_RANKING_CREDENTIAL",TOKEN,"PYTHONDONTWRITEBYTECODE","1"));
        builder.redirectError(temp.resolve("python-stderr.txt").toFile());var process=builder.start();
        try {
            var ready=CompletableFuture.supplyAsync(()->{try{return process.inputReader().readLine();}catch(Exception e){throw new CompletionException(e);}}).get(10,TimeUnit.SECONDS);
            assertThat(ready).isNotNull();int port=JSON.readTree(ready).get("port").asInt();
            return new Python(process,URI.create("http://127.0.0.1:"+port+"/internal/v1/feed-rankings"));
        }catch(Throwable failure){process.destroyForcibly();throw failure;}
    }
    SimulationFeedExecution.Result run(SimulationDomainRuntime.Services s,Owner viewer,URI uri,String token,Path output) {
        try {return SimulationFeedExecution.execute(s,DATASET,viewer.student(),viewer.academy(),UUID.randomUUID(),UUID.randomUUID(),uri,token,output);}
        catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
    }
    @Test void actualDatabaseCandidatesAndPythonRankingPreserveRawBytesWithoutPageMutation() throws Exception {
        try(var python=python();var runtime=new SimulationDomainRuntime()) {
            var viewer=seed(runtime);var card=author(runtime,viewer);
            runtime.executeAt(Instant.parse("2026-07-01T00:00:00Z"),s->{
                var before=s.jdbc().queryForList("SELECT * FROM feed_page_context");
                var done=run(s,viewer,python.endpoint(),TOKEN,temp.resolve("success"));
                assertThat(done.mode()).isEqualTo("RECOMMENDED");assertThat(done.pythonInvoked()).isTrue();
                assertThat(done.ranking().orElseThrow().orderedCardIds()).containsExactly(card);
                assertThat(done.request().candidates()).hasSize(1);
                assertThat(done.request().viewer_previous_month().get("month")).isEqualTo("2026-06");
                assertThat(done.request().viewer_previous_month().get("coverage")).isEqualTo("COMPLETE");
                assertThat(s.jdbc().queryForList("SELECT * FROM feed_page_context")).isEqualTo(before);
                try {
                    var output=temp.resolve("success");byte[] request=Files.readAllBytes(output.resolve("request.json"));
                    var raw=JSON.readTree(Files.readAllBytes(output.resolve("response.json")));
                    assertThat(raw.get("input_digest").asString()).isEqualTo("sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request)));
                    assertThat(raw.get("ordered_card_ids").get(0).asString()).isEqualTo(card.toString());
                    assertThat(JSON.readTree(Files.readString(output.resolve("http.json"))).get("status").asInt()).isEqualTo(200);
                    for(var file:List.of("request.json","response.json","http.json","result.json","input-source.json","input-verification.json"))assertThat(Files.readString(output.resolve(file))).doesNotContain(TOKEN);
                    Path durable=Path.of("build/simulation-feed-execution/success");Files.createDirectories(durable);
                    try(var files=Files.list(output)){for(var f:files.toList())Files.copy(f,durable.resolve(f.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
                }catch(Exception e){throw new RuntimeException(e);}return null;
            });
        }
    }
    @Test void actualPythonUnauthorizedResponseIsPreservedAndUsesLatestWithoutRetry() throws Exception {
        try(var python=python();var runtime=new SimulationDomainRuntime()) {
            var viewer=seed(runtime);author(runtime,viewer);
            runtime.executeAt(SimulationCashOracle.START.plusSeconds(2),s->{
                var output=temp.resolve("unauthorized");var done=run(s,viewer,python.endpoint(),"wrong-token",output);
                assertThat(done.mode()).isEqualTo("LATEST");assertThat(done.pythonInvoked()).isTrue();assertThat(done.ranking()).isEmpty();
                try {assertThat(JSON.readTree(Files.readString(output.resolve("http.json"))).get("status").asInt()).isEqualTo(401);
                    assertThat(output.resolve("response.json")).isRegularFile();
                    Path durable=Path.of("build/simulation-feed-execution/unauthorized");Files.createDirectories(durable);
                    try(var files=Files.list(output)){for(var f:files.toList())Files.copy(f,durable.resolve(f.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
                }catch(Exception e){throw new RuntimeException(e);}return null;
            });
        }
    }
    @Test void emptyCandidatesSkipHttpAndForeignDatasetCannotProduceEvidence() {
        try(var runtime=new SimulationDomainRuntime()) {
            var viewer=seed(runtime);runtime.executeAt(SimulationCashOracle.START.plusSeconds(2),s->{
                var output=temp.resolve("empty");var endpoint=URI.create("http://127.0.0.1:1/internal/v1/feed-rankings");
                var done=run(s,viewer,endpoint,TOKEN,output);
                assertThat(done.mode()).isEqualTo("LATEST");assertThat(done.pythonInvoked()).isFalse();assertThat(output.resolve("response.json")).doesNotExist();
                try {assertThat(JSON.readTree(Files.readAllBytes(output.resolve("input-verification.json"))).get("candidateCount").asInt()).isZero();}
                catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
                assertThatThrownBy(()->run(s,new Owner(viewer.academy(),UUID.randomUUID(),viewer.account()),endpoint,TOKEN,temp.resolve("foreign"))).hasMessage("FEED_SIMULATION_DATASET_OWNER");
                assertThat(temp.resolve("foreign")).doesNotExist();
                assertThatThrownBy(()->run(s,viewer,endpoint,TOKEN,output)).hasCauseInstanceOf(FileAlreadyExistsException.class);
                return null;
            });
        }
    }
    @ParameterizedTest @ValueSource(strings={"https://127.0.0.1:443/internal/v1/feed-rankings","http://example.com:80/internal/v1/feed-rankings","http://localhost:123/internal/v1/feed-rankings","http://127.0.0.1:123/internal/v1/feed-rankings?x=1","http://user@127.0.0.1:123/internal/v1/feed-rankings"})
    void refusesNonLocalOrAmbiguousEndpoint(String uri) {
        assertThatThrownBy(()->SimulationFeedExecution.validateEndpoint(URI.create(uri))).hasMessage("FEED_LOCAL_ENDPOINT_REQUIRED");
    }
}
