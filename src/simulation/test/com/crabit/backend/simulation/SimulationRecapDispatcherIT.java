package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationRecapDispatcherIT {
    @TempDir Path temp;
    static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    static final String DATASET="sha256:"+"c".repeat(64), ACTOR="student-3-00", ACCOUNT="account-3-00", TOKEN="local-dispatcher-recap";
    static final Instant START=SimulationCashOracle.START, WEEK=Instant.parse("2026-06-07T15:00:00Z"), MONTH=Instant.parse("2026-06-30T15:00:00Z");
    long sequence;
    JsonNode schema() throws Exception {return JSON.readTree(Files.readAllBytes(Path.of("api/demo-simulation-v1.schema.json")));}
    JsonNode people() throws Exception {return JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/bundle-contract-valid/students.json")));}
    SimulationCommandDispatcher dispatcher(URI endpoint) throws Exception {
        var d=new SimulationCommandDispatcher(schema(),DATASET,DATASET,people());
        try {d.configureRecap(endpoint,TOKEN,temp);return d;}catch(Exception e){d.close();throw e;}
    }
    ObjectNode event(String kind,Instant at,Map<String,?> command,String status) {
        var e=JSON.createObjectNode();e.put("eventId","e"+(++sequence));e.put("sequence",sequence);e.put("actorStudentId",ACTOR);e.put("occurredAt",at.toString());e.put("kind",kind);
        e.putArray("causes");e.set("command",JSON.valueToTree(command));String result="raw/result-"+sequence+".json";
        e.putObject("outcome").put("status",status).put("resultRef",result);var refs=e.putArray("artifactRefs").add(result);
        command.forEach((k,v)->{if(k.endsWith("Ref"))refs.add(v.toString());});return e;
    }
    void seed(SimulationCommandDispatcher d) {
        d.execute(event("JOIN",START,Map.of("studentId",ACTOR,"accountId",ACCOUNT,"academyId","academy-1","grade",3),"APPLIED"));
        d.execute(event("GRANT",START.plusSeconds(1),Map.of("accountId",ACCOUNT,"amountKrw",20000,"cashEntryId","cash","budgetMonth","2026-06","scheduledAt",START.toString()),"APPLIED"));
    }
    ObjectNode close(String kind,String generation,Instant at,String account,String status) {
        return event(kind,at,Map.of("accountId",account,"startInclusive","2026-06-01","endExclusive",kind.equals("CLOSE_WEEK")?"2026-06-08":"2026-07-01","generationId",generation,
            "snapshotRef","raw/"+generation+"-snapshot.json","requestRef","raw/"+generation+"-request.json","responseRef","raw/"+generation+"-response.json","storedStateRef","raw/"+generation+"-stored.json"),status);
    }
    void deposit(SimulationCommandDispatcher d,Instant at,int index,int amount) {
        d.execute(event("DEPOSIT",at,Map.of("accountId",ACCOUNT,"wishId","wish","idempotencyKey","deposit-"+index,"expectedVersion",index,"amount",amount),"APPLIED"));
    }
    @Test void actualPythonClosesWeekAndMonthWithFrozenEvidenceAndTypedGenerationIdentities() throws Exception {
        try(var python=python();var d=dispatcher(python.endpoint())) {
            var peerJoin=event("JOIN",START,Map.of("studentId","student-3-01","accountId","account-3-01","academyId","academy-1","grade",3),"APPLIED");
            peerJoin.put("actorStudentId","student-3-01");d.execute(peerJoin);
            seed(d);
            var peerGrant=event("GRANT",START.plusSeconds(1),Map.of("accountId","account-3-01","amountKrw",10000,"cashEntryId","peer-cash","budgetMonth","2026-06","scheduledAt",START.toString()),"APPLIED");
            peerGrant.put("actorStudentId","student-3-01");d.execute(peerGrant);
            var create=new HashMap<String,Object>();create.putAll(Map.of("accountId",ACCOUNT,"wishId","wish","idempotencyKey","create","purpose","Goal","targetAmount",30000));
            create.put("startDate",null);create.put("targetDate",null);create.put("photoId",null);
            d.execute(event("CREATE",START.plusSeconds(2),create,"APPLIED"));
            var peerCreate=new HashMap<String,Object>(create);peerCreate.put("accountId","account-3-01");peerCreate.put("wishId","peer-wish");peerCreate.put("targetAmount",10000);peerCreate.put("idempotencyKey","peer-create");
            var pc=event("CREATE",START.plusSeconds(2),peerCreate,"APPLIED");pc.put("actorStudentId","student-3-01");d.execute(pc);
            var pd=event("DEPOSIT",START.plusSeconds(2),Map.of("accountId","account-3-01","wishId","peer-wish","amount",2500,"expectedVersion",0,"idempotencyKey","peer-deposit"),"APPLIED");pd.put("actorStudentId","student-3-01");d.execute(pd);
            deposit(d,START.plusSeconds(3),0,1000);deposit(d,START.plusSeconds(4),1,1000);deposit(d,WEEK.minusNanos(1000),2,1000);
            var week=close("CLOSE_WEEK","week",WEEK,ACCOUNT,"APPLIED");var result=d.execute(week);
            Path weekly=temp.resolve("event-"+week.get("sequence").asLong());
            var response=JSON.readTree(Files.readString(weekly.resolve("response.json")));
            assertThat(response.get("view").get("page1_last_week_performance").get("achievement").get("net_savings").asLong()).isEqualTo(3000);
            assertThat(JSON.readTree(result.rawResult()).get("generationId").asString()).isEqualTo(d.id("RECAP_GENERATION","week").toString());
            String prefix="event-"+week.get("sequence").asLong()+"-period-";
            var source=JSON.readTree(Files.readString(temp.resolve(prefix+"source.json")));
            var checked=SimulationRecapPeriodVerifier.verify(JSON.readTree(Files.readString(weekly.resolve("request.json"))),source);
            assertThat(checked.periodNetSavings()).isEqualTo(3000);
            assertThat(checked.periodDeposits()).isEqualTo(3);
            assertThat(checked.wishes()).isEqualTo(1);
            var peerCheck=SimulationRecapPeerVerifier.verify(JSON.readTree(Files.readString(weekly.resolve("request.json"))),source.get("peer_source"));
            assertThat(peerCheck.peers()).isEqualTo(1);assertThat(peerCheck.achievementRates()).isEqualTo(1);
            assertThat(JSON.readTree(Files.readString(weekly.resolve("request.json"))).get("input").get("peer_metrics").get("achievement_rates").get(0).asDouble()).isEqualTo(25.0);
            var tampered=JSON.readTree(Files.readString(weekly.resolve("request.json")));
            ((tools.jackson.databind.node.ArrayNode)tampered.get("input").get("effective_transactions")).remove(0);
            assertThatThrownBy(()->SimulationRecapPeriodVerifier.verify(tampered,source)).hasMessage("RECAP_PERIOD_EFFECTIVE_TRANSACTIONS");
            var before=d.relationalState().state();byte[] request=Files.readAllBytes(weekly.resolve("request.json"));
            assertThat(d.execute(week).rawResult()).isEqualTo(result.rawResult());assertThat(d.relationalState().state()).isEqualTo(before);
            deposit(d,WEEK,3,500);assertThat(Files.readAllBytes(weekly.resolve("request.json"))).isEqualTo(request);
            var month=close("CLOSE_MONTH","month",MONTH,ACCOUNT,"APPLIED");d.execute(month);
            var monthly=JSON.readTree(Files.readString(temp.resolve("event-"+month.get("sequence").asLong()).resolve("response.json")));
            assertThat(monthly.get("view").get("objective_performance").get("total_savings").asLong()).isEqualTo(3500);
            assertThat(SimulationRecapPeriodVerifier.verify(JSON.readTree(request),source).periodNetSavings()).isEqualTo(3000);
            assertThat(JSON.readTree(Files.readString(temp.resolve("event-"+month.get("sequence").asLong()+"-period-verification.json"))).get("period").get("periodNetSavings").asLong()).isEqualTo(3500);
            Path monthFolder=temp.resolve("event-"+month.get("sequence").asLong());
            var normalizedMonth=new com.crabit.backend.recap.SimulationRecapNormalization(d.identities()).exchange(
                Files.readAllBytes(monthFolder.resolve("request.json")),Files.readAllBytes(monthFolder.resolve("response.json")),
                JSON.readTree(Files.readString(monthFolder.resolve("stored-state.json"))));
            assertThat(normalizedMonth.get("response").get("view").get("objective_performance").get("total_savings").asLong()).isEqualTo(3500);
            assertThat(normalizedMonth.get("stored").get("internal_metrics_json").get("account_id").asString()).isEqualTo("ACCOUNT:"+ACCOUNT);
            var rows=d.relationalState();d.verifyRelationalState(rows);d.verifyRelationalDomain(rows);
            var idMap=SimulationReplayIdentityMap.capture(rows,d.identities(),schema());
            assertThat(idMap.get("entries").valueStream().filter(x->x.get("entityKind").asString().equals("RECAP_GENERATION")).map(x->x.get("logicalId").asString())).containsExactlyInAnyOrder("week","month");
            assertThat(JSON.readTree(Files.readString(weekly.resolve("stored-state.json"))).get("state").asString()).isEqualTo("SUCCEEDED");
            Path durable=Path.of("build/simulation-recap-dispatcher");Files.createDirectories(durable);
            for(String file:List.of("request.json","response.json","stored-state.json","http.json"))Files.copy(weekly.resolve(file),durable.resolve("weekly-"+file),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    @Test void ineligibleMonthPersistsWithoutInventedHttpAndRejectsConflictingGenerationOrPeriod() throws Exception {
        try(var d=dispatcher(URI.create("http://127.0.0.1:1/internal/v1/recap-generations"))) {
            seed(d);var month=close("CLOSE_MONTH","month",MONTH,ACCOUNT,"APPLIED");
            var r=JSON.readTree(d.execute(month).rawResult());assertThat(r.get("state").asString()).isEqualTo("NOT_ELIGIBLE");assertThat(r.get("pythonInvoked").asBoolean()).isFalse();
            Path output=temp.resolve("event-"+month.get("sequence").asLong());assertThat(output.resolve("request.json")).isRegularFile();assertThat(output.resolve("response.json")).doesNotExist();
            var before=d.relationalState().state();
            assertThatThrownBy(()->d.execute(close("CLOSE_MONTH","month",MONTH,ACCOUNT,"APPLIED"))).hasMessage("RECAP_GENERATION_ID_CONFLICT");
            assertThatThrownBy(()->d.execute(close("CLOSE_MONTH","other",MONTH,ACCOUNT,"APPLIED"))).hasMessage("RECAP_PERIOD_ALREADY_CLOSED");
            assertThat(d.relationalState().state()).isEqualTo(before);
        }
    }
    @Test void wrongOwnerAndNonBoundaryCloseCannotGenerateAnotherStudentsRecap() throws Exception {
        try(var d=dispatcher(URI.create("http://127.0.0.1:1/internal/v1/recap-generations"))) {
            seed(d);var before=d.relationalState().state();
            assertThatThrownBy(()->d.execute(close("CLOSE_WEEK","early",WEEK.minusNanos(1000),ACCOUNT,"APPLIED"))).hasMessage("RECAP_CLOSE_INSTANT");
            assertThat(d.execute(close("CLOSE_WEEK","foreign",WEEK,"account-3-01","REJECTED")).errorCode()).isEqualTo("FORBIDDEN");
            assertThat(d.relationalState().state()).isEqualTo(before);assertThat(d.identities()).doesNotContainKey("RECAP_GENERATION:foreign");
        }
    }
    @Test void periodClosureCannotFollowBoundaryAppActivity() throws Exception {
        try(var d=dispatcher(URI.create("http://127.0.0.1:1/internal/v1/recap-generations"))) {
            seed(d);
            d.execute(event("PURCHASE",WEEK,Map.of("accountId",ACCOUNT,"cashEntryId","boundary","amountKrw",1000),"APPLIED"));
            var before=d.relationalState().state();
            assertThatThrownBy(()->d.execute(close("CLOSE_WEEK","late",WEEK,ACCOUNT,"APPLIED"))).hasMessage("EVENT_PHASE_ORDER");
            assertThat(d.relationalState().state()).isEqualTo(before);
        }
    }
    @Test void pythonFailureRetainsRawHttpAndStopsFurtherCommandsWithoutRetry() throws Exception {
        try(var python=python();var d=new SimulationCommandDispatcher(schema(),DATASET,DATASET,people())) {
            d.configureRecap(python.endpoint(),"incorrect",temp);seed(d);
            var e=close("CLOSE_WEEK","failed",WEEK,ACCOUNT,"APPLIED");
            assertThatThrownBy(()->d.execute(e)).hasMessage("HTTP_401");
            var output=temp.resolve("event-"+e.get("sequence").asLong());
            assertThat(JSON.readTree(Files.readString(output.resolve("http.json"))).get("status").asInt()).isEqualTo(401);
            assertThat(output.resolve("response.json")).isRegularFile();assertThat(output.resolve("failure.json")).isRegularFile();
            assertThatThrownBy(()->d.execute(e)).hasMessage("DISPATCHER_POISONED");
            assertThat(Files.readString(output.resolve("request.json"))).doesNotContain(TOKEN,"incorrect");
        }
    }
    @Test void twoFreshDatabasesProduceEqualVerifiedRecapProjectionWithoutErasingRawEvidence() throws Exception {
        JsonNode previousProjection=null,previousCommand=null;byte[] previousRaw=null;
        try(var python=python()) {
            for(int run=0;run<2;run++) {
                sequence=0;Path evidence=Files.createDirectory(temp.resolve("normalize-"+run));
                try(var d=new SimulationCommandDispatcher(schema(),DATASET,DATASET,people())) {
                    d.configureRecap(python.endpoint(),TOKEN,evidence);seed(d);
                    var create=new HashMap<String,Object>();create.putAll(Map.of("accountId",ACCOUNT,"wishId","wish","idempotencyKey","create","purpose","Goal","targetAmount",30000));
                    create.put("startDate",null);create.put("targetDate",null);create.put("photoId",null);
                    d.execute(event("CREATE",START.plusSeconds(2),create,"APPLIED"));
                    deposit(d,START.plusSeconds(3),0,1000);
                    var close=close("CLOSE_WEEK","week",WEEK,ACCOUNT,"APPLIED");d.execute(close);
                    Path folder=evidence.resolve("event-"+sequence);
                    byte[] request=Files.readAllBytes(folder.resolve("request.json")),response=Files.readAllBytes(folder.resolve("response.json"));
                    JsonNode stored=JSON.readTree(Files.readString(folder.resolve("stored-state.json")));
                    var normalizer=new com.crabit.backend.recap.SimulationRecapNormalization(d.identities());
                    JsonNode projection=normalizer.exchange(request,response,stored);
                    assertThat(projection.get("request").get("input").get("effective_transactions").get(0).get("root_event_id").asString()).startsWith("LEDGER_ROOT:");
                    assertThat(projection.get("request").get("input_digest").asString()).startsWith("logical:sha256:");
                    assertThat(projection.get("response").get("view").get("page1_last_week_performance").get("achievement").get("net_savings").asLong()).isEqualTo(1000);
                    var state=d.relationalState();d.verifyRelationalState(state);d.verifyRelationalDomain(state);
                    var mapping=SimulationReplayIdentityMap.capture(state,d.identities(),schema());
                    JsonNode normalizedState=SimulationRelationalNormalizer.normalize(state,mapping,d.verifyIdempotencyState(state));
                    assertThat(normalizedState.get("tables").get("recap_generation").get(0)).isEqualTo(projection.get("stored"));
                    JsonNode commands=d.normalizedResponses();JsonNode last=commands.get("events").get(commands.get("events").size()-1);
                    assertThat(last.get("response").get("generationId").asString()).isEqualTo("RECAP_GENERATION:week");
                    assertThat(last.get("response").get("inputDigest")).isEqualTo(projection.get("request").get("input_digest"));
                    if(previousProjection!=null) {
                        assertThat(request).isNotEqualTo(previousRaw);
                        assertThat(projection).isEqualTo(previousProjection);assertThat(last).isEqualTo(previousCommand);
                    }
                    previousProjection=projection;previousRaw=request;previousCommand=last;
                    var corrupt=stored.deepCopy();((ObjectNode)corrupt).put("input_digest","sha256:"+"0".repeat(64));
                    assertThatThrownBy(()->normalizer.exchange(request,response,corrupt)).hasMessageContaining("STORED_BINDING:input_digest");
                    assertThat(Files.readAllBytes(folder.resolve("request.json"))).isEqualTo(request);
                    Path durable=Path.of("build/simulation-recap-normalization");Files.createDirectories(durable);
                    Files.writeString(durable.resolve("run-"+run+"-normalized.json"),JSON.writeValueAsString(projection));
                    Files.write(durable.resolve("run-"+run+"-request.json"),request);
                }
            }
        }
    }
    @Test void notEligibleNormalizationRetainsNullResponseAndRejectsInventedHttp() throws Exception {
        try(var d=dispatcher(URI.create("http://127.0.0.1:1/internal/v1/recap-generations"))) {
            seed(d);d.execute(close("CLOSE_MONTH","month",MONTH,ACCOUNT,"APPLIED"));
            Path folder=temp.resolve("event-"+sequence);byte[] request=Files.readAllBytes(folder.resolve("request.json"));
            JsonNode stored=JSON.readTree(Files.readString(folder.resolve("stored-state.json")));
            var n=new com.crabit.backend.recap.SimulationRecapNormalization(d.identities());
            JsonNode normalized=n.exchange(request,null,stored);
            assertThat(normalized.get("response").isNull()).isTrue();assertThat(normalized.get("pythonInvoked").asBoolean()).isFalse();
            assertThat(normalized.get("stored").get("view_json").isNull()).isTrue();
            assertThatThrownBy(()->n.exchange(request,"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),stored)).hasMessage("RECAP_NORMALIZATION_INELIGIBLE_HTTP_RESPONSE");
            assertThat(d.normalizedResponses().get("events").get(2).get("response").get("generationId").asString()).isEqualTo("RECAP_GENERATION:month");
        }
    }
    @Test void completedSharedStoryIsIndependentlyVerifiedBeforeActualPythonCall() throws Exception { verifyStory(false); }
    @Test void julyStoryReconcilesActualJuneAuthorMetricsBeforePython() throws Exception { verifyStory(true); }
    void verifyStory(boolean july) throws Exception {
        try(var python=python();var d=dispatcher(python.endpoint())) {
            var join=event("JOIN",START,Map.of("studentId","student-3-01","accountId","account-3-01","academyId","academy-1","grade",3),"APPLIED");
            join.put("actorStudentId","student-3-01");d.execute(join);seed(d);
            var grant=event("GRANT",START.plusSeconds(1),Map.of("accountId","account-3-01","amountKrw",10000,"cashEntryId","author-cash","budgetMonth","2026-06","scheduledAt",START.toString()),"APPLIED");
            grant.put("actorStudentId","student-3-01");d.execute(grant);
            var create=new HashMap<String,Object>(Map.of("accountId","account-3-01","wishId","story","idempotencyKey","author-create","purpose","Completed goal","targetAmount",1000));
            create.put("startDate",null);create.put("targetDate",null);create.put("photoId",null);
            var c=event("CREATE",START.plusSeconds(2),create,"APPLIED");c.put("actorStudentId","student-3-01");d.execute(c);
            var deposit=event("DEPOSIT",START.plusSeconds(3),Map.of("accountId","account-3-01","wishId","story","idempotencyKey","author-deposit","expectedVersion",0,"amount",1000),"APPLIED");
            deposit.put("actorStudentId","student-3-01");d.execute(deposit);
            var complete=event("COMPLETE",july?MONTH.plusSeconds(1):START.plusSeconds(4),Map.of("accountId","account-3-01","wishId","story","idempotencyKey","author-complete","expectedVersion",1,"confirmed",true),"APPLIED");
            complete.put("actorStudentId","student-3-01");d.execute(complete);
            var share=event("SHARE",july?MONTH.plusSeconds(2):START.plusSeconds(5),Map.of("accountId","account-3-01","wishId","story","expectedVersion",2,"visibility","ACADEMY"),"APPLIED");
            share.put("actorStudentId","student-3-01");d.execute(share);
            var close=close("CLOSE_WEEK","story-week",july?Instant.parse("2026-07-05T15:00:00Z"):WEEK,ACCOUNT,"APPLIED");
            if(july)((ObjectNode)close.get("command")).put("startInclusive","2026-06-29").put("endExclusive","2026-07-06");
            d.execute(close);
            String prefix="event-"+sequence+"-period-";Path output=temp.resolve("event-"+sequence);
            var request=JSON.readTree(Files.readString(output.resolve("request.json")));
            var source=JSON.readTree(Files.readString(temp.resolve(prefix+"source.json")));
            var verified=SimulationRecapStoryVerifier.verify(request,source.get("peer_source"),source.get("story_source"));
            assertThat(verified.selected()).isEqualTo(1);assertThat(verified.eligibleBeforeLimit()).isEqualTo(1);
            assertThat(request.get("input").get("success_story_candidates").get(0).get("wish_id").asString()).isEqualTo(d.id("WISH","story").toString());
            assertThat(JSON.readTree(Files.readString(temp.resolve(prefix+"verification.json"))).get("stories").get("selected").asInt()).isEqualTo(1);
            assertThat(JSON.readTree(Files.readString(output.resolve("http.json"))).get("status").asInt()).isEqualTo(200);
            assertThat(SimulationRecapAuthorVerifier.verify(request,source).authorsVerified()).isEqualTo(1);
            assertThat(JSON.readTree(Files.readString(temp.resolve(prefix+"verification.json"))).get("authors").get("authorsVerified").asInt()).isEqualTo(1);
            var author=(ObjectNode)request.get("input").get("success_story_candidates").get(0).get("author_previous_month");
            assertThat(author.get("deposit_count").asLong()).isEqualTo(july?1:0);
            assertThat(author.get("total_savings").asLong()).isEqualTo(july?1000:0);
            var forged=request.deepCopy();
            ((ObjectNode)forged.get("input").get("success_story_candidates").get(0).get("author_previous_month")).put("total_savings",9999);
            assertThatThrownBy(()->SimulationRecapAuthorVerifier.verify(forged,source)).hasMessage("RECAP_AUTHOR_METRIC:total_savings");
            byte[] raw=Files.readAllBytes(output.resolve("request.json"));
            ((tools.jackson.databind.node.ArrayNode)request.get("input").get("success_story_candidates")).removeAll();
            assertThatThrownBy(()->SimulationRecapStoryVerifier.verify(request,source.get("peer_source"),source.get("story_source"))).hasMessage("RECAP_STORY_CANDIDATE_SELECTION");
            assertThat(Files.readAllBytes(output.resolve("request.json"))).isEqualTo(raw);
            Path durable=Path.of(july?"build/simulation-recap-author-metrics":"build/simulation-recap-stories");Files.createDirectories(durable);
            for(String file:List.of("request.json","response.json","stored-state.json","http.json"))Files.copy(output.resolve(file),durable.resolve(file),StandardCopyOption.REPLACE_EXISTING);
            Files.copy(temp.resolve(prefix+"source.json"),durable.resolve("period-source.json"),StandardCopyOption.REPLACE_EXISTING);
            Files.copy(temp.resolve(prefix+"verification.json"),durable.resolve("period-verification.json"),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    record Python(Process process,URI endpoint) implements AutoCloseable {
        public void close() throws Exception {process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}process.inputReader().close();}
    }
    Python python() throws Exception {
        var builder=new ProcessBuilder(System.getenv().getOrDefault("CRABIT_SIMULATION_PYTHON","python3"),"-u","-m","recap_service").directory(Path.of(System.getenv().getOrDefault("CRABIT_SIMULATION_DATA_ROOT","../crabit-data")).toRealPath().toFile());
        builder.environment().putAll(Map.of("CRABIT_RECAP_HOST","127.0.0.1","CRABIT_RECAP_PORT","0","CRABIT_RECAP_TOKEN",TOKEN,"PYTHONDONTWRITEBYTECODE","1"));builder.redirectError(temp.resolve("stderr.txt").toFile());
        var p=builder.start();try {String ready=CompletableFuture.supplyAsync(()->{try{return p.inputReader().readLine();}catch(Exception e){throw new CompletionException(e);}}).get(10,TimeUnit.SECONDS);
            return new Python(p,URI.create(JSON.readTree(ready).get("url").asString()+"/internal/v1/recap-generations"));
        }catch(Throwable e){p.destroyForcibly();throw e;}
    }
}
