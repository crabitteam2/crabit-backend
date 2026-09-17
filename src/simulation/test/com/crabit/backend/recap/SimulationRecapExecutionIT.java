package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.*;
import com.crabit.backend.simulation.*;
import com.crabit.backend.demo.DemoSimulationCashService;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.wish.*;
import com.crabit.backend.relationship.RelationshipContextAuthorizationService;
import java.net.URI;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SimulationRecapExecutionIT {
    @TempDir Path temp;
    static final String DATASET="sha256:"+"e".repeat(64), TOKEN="local-simulation-recap-test";
    static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    record Owner(UUID academy,UUID student,UUID account) {}
    Owner seed(SimulationDomainRuntime runtime) {
        var o=new Owner(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        runtime.executeAt(SimulationCashOracle.START,s->{
            var j=s.jdbc();var at=Timestamp.from(s.clock().instant());
            j.update("INSERT INTO academy(id,name) VALUES (?,'local recap')",o.academy());
            j.update("INSERT INTO student(id,nickname,age,age_provenance) VALUES (?,'synthetic',9,'PROVIDED')",o.student());
            j.update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,?)",UUID.randomUUID(),o.student(),o.academy(),at);
            j.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",o.account(),o.student(),o.academy(),at);
            j.update("INSERT INTO demo_simulation_dataset(dataset_id,manifest_digest,state,starts_at,ends_at) VALUES (?,?,'BUILDING',?,?)",DATASET,DATASET,at,Timestamp.from(SimulationCashOracle.END));
            j.update("INSERT INTO demo_simulation_account(account_id,dataset_id,logical_student_id,logical_account_id,grade,is_owner) VALUES (?,?,'s','a',3,false)",o.account(),DATASET);
            s.service(DemoSimulationCashService.class).apply(DATASET,"grant",o.account(),DemoSimulationCashService.Kind.GRANT,20000,s.clock().instant());return null;
        });return o;
    }
    record Python(Process process,URI endpoint) implements AutoCloseable {
        @Override public void close() throws Exception {process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}process.inputReader().close();}
    }
    Python python() throws Exception {
        Path root=Path.of(System.getenv().getOrDefault("CRABIT_SIMULATION_DATA_ROOT","../crabit-data")).toRealPath();
        assertThat(root.resolve("recap_service/__main__.py")).isRegularFile();
        var builder=new ProcessBuilder(System.getenv().getOrDefault("CRABIT_SIMULATION_PYTHON","python3"),"-u","-m","recap_service").directory(root.toFile());
        builder.environment().putAll(Map.of("CRABIT_RECAP_HOST","127.0.0.1","CRABIT_RECAP_PORT","0","CRABIT_RECAP_TOKEN",TOKEN,"PYTHONDONTWRITEBYTECODE","1"));
        builder.redirectError(temp.resolve("python-stderr.txt").toFile());
        var process=builder.start();
        try {
            var ready=CompletableFuture.supplyAsync(()->{try{return process.inputReader().readLine();}catch(Exception e){throw new CompletionException(e);}}).get(10,TimeUnit.SECONDS);
            assertThat(ready).isNotNull();
            return new Python(process,URI.create(JSON.readTree(ready).get("url").asString()+"/internal/v1/recap-generations"));
        }catch(Throwable failure){process.destroyForcibly();throw failure;}
    }
    @Test void actualPythonWeeklyMonthlyPersistenceAndOwnerRetrievalRemainFrozen() throws Exception {
        try(var python=python();var runtime=new SimulationDomainRuntime()) {
            var o=seed(runtime);
            var wish=runtime.executeAt(SimulationCashOracle.START.plusSeconds(1),s->s.service(WishLifecycleService.class).create(o.student(),o.academy(),o.account(),"create","Goal",30000,(LocalDate)null));
            for(int day:new int[]{2,3,4})runtime.executeAt(Instant.parse("2026-06-0"+day+"T00:00:00Z"),s->{
                long v=s.jdbc().queryForObject("SELECT version FROM wish WHERE id=?",Long.class,wish.wish().id());
                return s.service(WishFundMovementService.class).deposit(o.student(),o.academy(),o.account(),wish.wish().id(),"d"+day,1000,v);
            });
            for(var kind:RecapKind.values()) {
                String command=kind==RecapKind.WEEKLY?"CLOSE_WEEK":"CLOSE_MONTH";
                LocalDate start=LocalDate.parse("2026-06-01"),end=kind==RecapKind.WEEKLY?start.plusWeeks(1):start.plusMonths(1);
                Instant at=end.atStartOfDay(RecapPeriods.SEOUL).toInstant();
                runtime.executeAt(at,s->{
                    var prepared=SimulationRecapPreparation.prepare(s,DATASET,o.student(),o.account(),UUID.randomUUID(),command,start,end);
                    Path output=temp.resolve(kind.name());
                    try {
                        var done=SimulationRecapExecution.complete(s,DATASET,prepared,python.endpoint(),TOKEN,output);
                        assertThat(done.state()).isEqualTo("SUCCEEDED");assertThat(done.pythonInvoked()).isTrue();
                        assertThat(Files.readString(output.resolve("request.json"))).isEqualTo(prepared.requestJson());
                        var raw=JSON.readTree(Files.readString(output.resolve("response.json")));
                        assertThat(raw.get("generation_id").asString()).isEqualTo(done.generationId().toString());
                        assertThat(raw.get("view")).isEqualTo(JSON.readTree(done.viewJson()));
                        assertThat(raw.get("internal_metrics")).isEqualTo(JSON.readTree(done.internalMetricsJson()));
                        var before=s.jdbc().queryForMap("SELECT * FROM recap_generation WHERE id=?",done.generationId());
                        var query=new RecapQueryService(s.service(CardBalanceAccountRepository.class),s.service(RecapGenerationRepository.class),
                            s.service(SharedCardQueryRepository.class),s.service(ObjectMapper.class),s.clock(),s.service(RelationshipContextAuthorizationService.class),null);
                        // This case contains no shared story/photo; photo enrichment is not tested here.
                        var publicView=kind==RecapKind.WEEKLY?query.weekly(o.student(),o.academy(),o.account(),start.toString()):query.monthly(o.student(),o.academy(),o.account(),"2026-06");
                        assertThat(publicView.status()).isEqualTo("SUCCEEDED");
                        var publicJson=JSON.valueToTree(publicView);
                        if(kind==RecapKind.WEEKLY)assertThat(publicJson.get("result").get("page1LastWeekPerformance").get("achievement").get("netSavings").asLong()).isEqualTo(3000);
                        else assertThat(publicJson.get("result").get("objectivePerformance").get("totalSavings").asLong()).isEqualTo(3000);
                        assertThatThrownBy(()->{ if(kind==RecapKind.WEEKLY)query.weekly(UUID.randomUUID(),o.academy(),o.account(),start.toString()); else query.monthly(UUID.randomUUID(),o.academy(),o.account(),"2026-06"); }).isInstanceOf(WishLifecycleException.class);
                        Files.writeString(output.resolve("owner-response.json"),JSON.writeValueAsString(publicView));
                        var repeated=SimulationRecapExecution.complete(s,DATASET,prepared,python.endpoint(),TOKEN,output);
                        assertThat(repeated.pythonInvoked()).isFalse();assertThat(repeated.viewJson()).isEqualTo(done.viewJson());
                        assertThat(s.jdbc().queryForMap("SELECT * FROM recap_generation WHERE id=?",done.generationId())).isEqualTo(before);
                        // Copy exact evidence out of the temporary JUnit directory for review.
                        Path durable=Path.of("build/simulation-recap-execution/"+kind.name());Files.createDirectories(durable);
                        try(var files=Files.list(output)){for(var f:files.toList())Files.copy(f,durable.resolve(f.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
                    } catch(java.io.IOException e) {throw new java.io.UncheckedIOException(e);}return null;
                });
            }
        }
    }
    @Test void unauthorizedPythonResponseIsRetainedAndCannotBecomeASuccessfulRecap() throws Exception {
        try(var python=python();var runtime=new SimulationDomainRuntime()) {
            var o=seed(runtime);
            runtime.executeAt(Instant.parse("2026-06-07T15:00:00Z"),s->{
                var p=SimulationRecapPreparation.prepare(s,DATASET,o.student(),o.account(),UUID.randomUUID(),"CLOSE_WEEK",LocalDate.parse("2026-06-01"),LocalDate.parse("2026-06-08"));
                Path output=temp.resolve("unauthorized");
                assertThatThrownBy(()->SimulationRecapExecution.complete(s,DATASET,p,python.endpoint(),"wrong-token",output)).hasMessage("HTTP_401");
                var row=s.service(RecapGenerationRepository.class).findById(p.generationId()).orElseThrow();
                assertThat(row.state()).isEqualTo(RecapGenerationState.FAILED);assertThat(row.viewJson()).isNull();assertThat(row.currentVersion()).isFalse();
                try{assertThat(JSON.readTree(Files.readString(output.resolve("http.json"))).get("status").asInt()).isEqualTo(401);assertThat(Files.readString(output.resolve("response.json"))).doesNotContain(TOKEN);}
                catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
                assertThatThrownBy(()->SimulationRecapExecution.complete(s,DATASET,p,python.endpoint(),TOKEN,temp.resolve("retry"))).hasMessage("RECAP_EXECUTION_NOT_PENDING");
                return null;
            });
        }
    }
    @Test void ineligibleMonthDoesNotContactPythonAndRejectsDatasetOrFrozenInputSubstitution() throws Exception {
        try(var runtime=new SimulationDomainRuntime()) {
            var o=seed(runtime);
            runtime.executeAt(Instant.parse("2026-06-30T15:00:00Z"),s->{
                var p=SimulationRecapPreparation.prepare(s,DATASET,o.student(),o.account(),UUID.randomUUID(),"CLOSE_MONTH",LocalDate.parse("2026-06-01"),LocalDate.parse("2026-07-01"));
                var endpoint=URI.create("http://127.0.0.1:1/internal/v1/recap-generations");
                var before=s.jdbc().queryForMap("SELECT * FROM recap_generation WHERE id=?",p.generationId());
                try {
                    var done=SimulationRecapExecution.complete(s,DATASET,p,endpoint,TOKEN,temp.resolve("no-call"));
                    assertThat(done.state()).isEqualTo("NOT_ELIGIBLE");assertThat(done.pythonInvoked()).isFalse();assertThat(done.viewJson()).isNull();
                    assertThat(temp.resolve("no-call")).doesNotExist();
                }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
                assertThatThrownBy(()->SimulationRecapExecution.complete(s,"sha256:"+"f".repeat(64),p,endpoint,TOKEN,temp.resolve("wrong-dataset"))).hasMessage("RECAP_SIMULATION_DATASET_OWNER");
                var forged=new SimulationRecapPreparation.Prepared(p.generationId(),p.inputDigest(),"{}",p.state(),p.generationVersion(),false);
                assertThatThrownBy(()->SimulationRecapExecution.complete(s,DATASET,forged,endpoint,TOKEN,temp.resolve("forged"))).hasMessage("RECAP_FROZEN_INPUT_CONFLICT");
                assertThat(s.jdbc().queryForMap("SELECT * FROM recap_generation WHERE id=?",p.generationId())).isEqualTo(before);
                return null;
            });
        }
    }
    Owner author(SimulationDomainRuntime runtime,Owner viewer,int index) {
        var a=new Owner(viewer.academy(),UUID.randomUUID(),UUID.randomUUID());
        runtime.executeAt(SimulationCashOracle.START,s->{
            var j=s.jdbc();var at=Timestamp.from(s.clock().instant());
            j.update("INSERT INTO student(id,nickname,age,age_provenance) VALUES (?,?,9,'PROVIDED')",a.student(),"author-"+index);
            j.update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,?)",UUID.randomUUID(),a.student(),a.academy(),at);
            j.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",a.account(),a.student(),a.academy(),at);
            j.update("INSERT INTO demo_simulation_account(account_id,dataset_id,logical_student_id,logical_account_id,grade,is_owner) VALUES (?,?,?,?,3,false)",a.account(),DATASET,"author-"+index,"author-account-"+index);
            s.service(DemoSimulationCashService.class).apply(DATASET,"author-grant-"+index,a.account(),DemoSimulationCashService.Kind.GRANT,10000,s.clock().instant());return null;
        });return a;
    }
    UUID completedStory(SimulationDomainRuntime runtime,Owner a,Instant at,String key) {
        return runtime.executeAt(at,s->{
            var service=s.service(WishLifecycleService.class);
            var wish=service.create(a.student(),a.academy(),a.account(),key,"Story "+key,1000,(LocalDate)null).wish().id();
            s.service(WishFundMovementService.class).deposit(a.student(),a.academy(),a.account(),wish,key+"-deposit",1000,0);
            service.complete(a.student(),a.academy(),a.account(),wish,key+"-complete",1);
            service.patch(a.student(),a.academy(),a.account(),wish,2,new WishPatch(null,null,false,null,WishVisibility.ACADEMY));return wish;
        });
    }
    @Test void actualPythonStoryOrderAndQueryPrivacyChangesKeepFrozenRecapAndAuthorizeBeforePhotos() throws Exception {
        try(var python=python();var runtime=new SimulationDomainRuntime()) {
            var viewer=seed(runtime);var author1=author(runtime,viewer,1);var author2=author(runtime,viewer,2);
            UUID first=completedStory(runtime,author1,SimulationCashOracle.START.plusSeconds(10),"first");
            UUID second=completedStory(runtime,author2,SimulationCashOracle.START.plusSeconds(20),"second");
            Instant closure=Instant.parse("2026-06-07T15:00:00Z");Path output=temp.resolve("story-query");
            UUID generation=runtime.executeAt(closure,s->{
                var p=SimulationRecapPreparation.prepare(s,DATASET,viewer.student(),viewer.account(),UUID.randomUUID(),"CLOSE_WEEK",LocalDate.parse("2026-06-01"),LocalDate.parse("2026-06-08"));
                try {
                    var result=SimulationRecapExecution.complete(s,DATASET,p,python.endpoint(),TOKEN,output);
                    var stories=JSON.readTree(result.viewJson()).get("page3_academy_success_stories").get("stories");
                    assertThat(stories.valueStream().map(x->x.get("wish_id").asString()).toList()).containsExactly(first.toString(),second.toString());
                    assertThat(stories.valueStream().map(x->x.get("type_title").asString()).toList()).containsOnly("탐색형 토끼");
                    assertThat(JSON.readTree(Files.readString(output.resolve("result-verification.json"))).get("storiesVerified").asInt()).isEqualTo(2);
                    return result.generationId();
                }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            });
            byte[] original=Files.readAllBytes(output.resolve("response.json"));
            // This test observes authorization-before-photo calls; it does not contact a photo provider.
            var photos=org.mockito.Mockito.mock(com.crabit.backend.wishphoto.WishPhotoService.class);
            UUID later=completedStory(runtime,author2,closure.plusSeconds(1),"later-story");
            runtime.executeAt(closure.plusSeconds(2),s->{
                var query=new RecapQueryService(s.service(CardBalanceAccountRepository.class),s.service(RecapGenerationRepository.class),
                    s.service(SharedCardQueryRepository.class),s.service(ObjectMapper.class),s.clock(),s.service(RelationshipContextAuthorizationService.class),photos);
                var before=s.jdbc().queryForMap("SELECT * FROM recap_generation WHERE id=?",generation);
                java.util.function.Supplier<tools.jackson.databind.JsonNode> read=()->JSON.valueToTree(query.weekly(viewer.student(),viewer.academy(),viewer.account(),"2026-06-01"));
                var initial=read.get();assertThat(initial.get("result").get("page3AcademySuccessStories").get("stories").valueStream().map(x->x.get("wishId").asString()).toList()).containsExactly(first.toString(),second.toString());
                var order=org.mockito.Mockito.inOrder(photos);order.verify(photos).attachedView(first);order.verify(photos).attachedView(second);
                org.mockito.Mockito.clearInvocations(photos);
                var relations=s.service(com.crabit.backend.relationship.RelationshipCommandService.class);
                relations.blockStudent(author1.student(),viewer.student());
                var blocked=read.get();assertThat(blocked.get("result").get("page3AcademySuccessStories").get("stories").valueStream().map(x->x.get("wishId").asString()).toList()).containsExactly(second.toString());
                org.mockito.Mockito.verify(photos,org.mockito.Mockito.never()).attachedView(first);org.mockito.Mockito.verify(photos).attachedView(second);
                org.mockito.Mockito.clearInvocations(photos);
                s.service(WishLifecycleService.class).patch(author2.student(),author2.academy(),author2.account(),second,3,new WishPatch(null,null,false,null,WishVisibility.FOLLOWERS));
                var hidden=read.get();assertThat(hidden.get("result").get("page3AcademySuccessStories").get("stories").size()).isZero();org.mockito.Mockito.verifyNoInteractions(photos);
                relations.follow(viewer.student(),viewer.academy(),author2.student());
                var followed=read.get();assertThat(followed.get("result").get("page3AcademySuccessStories").get("stories").valueStream().map(x->x.get("wishId").asString()).toList()).containsExactly(second.toString());
                org.mockito.Mockito.verify(photos).attachedView(second);org.mockito.Mockito.verify(photos,org.mockito.Mockito.never()).attachedView(first);
                assertThatThrownBy(()->query.weekly(author2.student(),viewer.academy(),viewer.account(),"2026-06-01")).isInstanceOf(WishLifecycleException.class);
                org.mockito.Mockito.verify(photos,org.mockito.Mockito.never()).attachedView(later);
                assertThat(s.jdbc().queryForMap("SELECT * FROM recap_generation WHERE id=?",generation)).isEqualTo(before);
                try {
                    Files.writeString(output.resolve("query-privacy.json"),JSON.writeValueAsString(Map.of("initial",initial,"blocked",blocked,"hidden",hidden,"followed",followed,"photoProviderInvoked",false)));
                }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}return null;
            });
            assertThat(Files.readAllBytes(output.resolve("response.json"))).isEqualTo(original);
            Path durable=Path.of("build/simulation-recap-result-query");Files.createDirectories(durable);
            try(var files=Files.list(output)){for(var file:files.toList())Files.copy(file,durable.resolve(file.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
        }
    }
    @Test void substitutedPythonStoryIsRecordedButNeverStoredAsSuccessful() throws Exception {
        try(var python=python();var runtime=new SimulationDomainRuntime()) {
            var viewer=seed(runtime);var author=author(runtime,viewer,1);
            completedStory(runtime,author,SimulationCashOracle.START.plusSeconds(10),"fault-story");
            // A local fault proxy changes only the story identity in a real Python response.
            var proxy=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            try(var client=java.net.http.HttpClient.newHttpClient()) {
                proxy.createContext("/internal/v1/recap-generations",exchange->{
                    try {
                        var request=java.net.http.HttpRequest.newBuilder(python.endpoint()).header("Authorization","Bearer "+TOKEN)
                            .header("Content-Type","application/json").header("Idempotency-Key",exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build();
                        var upstream=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                        if(upstream.statusCode()!=200)throw new java.io.IOException("Fault fixture upstream failed");
                        var response=JSON.readTree(upstream.body());
                        ((tools.jackson.databind.node.ObjectNode)response.get("view").get("page3_academy_success_stories").get("stories").get(0)).put("wish_id",UUID.randomUUID().toString());
                        byte[] body=JSON.writeValueAsBytes(response);exchange.getResponseHeaders().add("Content-Type","application/json");
                        exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                    } catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
                    finally {exchange.close();}
                });proxy.start();
                runtime.executeAt(Instant.parse("2026-06-07T15:00:00Z"),s->{
                    var p=SimulationRecapPreparation.prepare(s,DATASET,viewer.student(),viewer.account(),UUID.randomUUID(),"CLOSE_WEEK",LocalDate.parse("2026-06-01"),LocalDate.parse("2026-06-08"));
                    Path output=temp.resolve("substituted-story");
                    var endpoint=URI.create("http://127.0.0.1:"+proxy.getAddress().getPort()+"/internal/v1/recap-generations");
                    assertThatThrownBy(()->SimulationRecapExecution.complete(s,DATASET,p,endpoint,TOKEN,output)).hasMessage("RECAP_RESULT_SELECTION");
                    var stored=s.service(RecapGenerationRepository.class).findById(p.generationId()).orElseThrow();
                    assertThat(stored.state()).isEqualTo(RecapGenerationState.FAILED);assertThat(stored.viewJson()).isNull();assertThat(stored.currentVersion()).isFalse();
                    assertThat(output.resolve("response.json")).isRegularFile();assertThat(output.resolve("result-verification.json")).doesNotExist();
                    try {
                        assertThat(JSON.readTree(Files.readString(output.resolve("http.json"))).get("status").asInt()).isEqualTo(200);
                        assertThat(JSON.readTree(Files.readString(output.resolve("failure.json"))).get("code").asString()).isEqualTo("RECAP_RESULT_SELECTION");
                        Path durable=Path.of("build/simulation-recap-result-failure");Files.createDirectories(durable);
                        try(var files=Files.list(output)){for(var file:files.toList())Files.copy(file,durable.resolve(file.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
                    }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}return null;
                });
            } finally {proxy.stop(0);}
        }
    }
    @ParameterizedTest @ValueSource(strings={"https://example.com/internal/v1/recap-generations","http://localhost:8081/internal/v1/recap-generations","http://127.0.0.1:8081/internal/v1/recap-generations?x=1","http://user@127.0.0.1:8081/internal/v1/recap-generations","http://127.0.0.1:8081/other"})
    void rejectsUnboundedServiceEndpoints(String endpoint){assertThatThrownBy(()->SimulationRecapExecution.validateEndpoint(URI.create(endpoint))).hasMessage("RECAP_LOCAL_ENDPOINT_REQUIRED");}
}
