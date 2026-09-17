package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

/** Real bundle read-back with recomputed file checksums: semantic corruption must still fail. */
class SimulationBundleCashVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String ACCOUNT="account-3-01", REF="state/export.json", TIME="2026-06-01T01:00:00.123456Z";
    @TempDir Path temp;
    SimulationBundleReaderTest helper;
    @BeforeEach void setup(){helper=new SimulationBundleReaderTest();helper.temp=temp;}
    ObjectNode state(Path p)throws Exception{return (ObjectNode)JSON.readTree(Files.readAllBytes(p.resolve(REF)));}
    ObjectNode balance(ObjectNode state){for(JsonNode b:state.get("balances"))if(b.get("accountId").asString().equals(ACCOUNT))return (ObjectNode)b;throw new AssertionError();}
    ObjectNode cash(String status){
        ObjectNode e=JSON.createObjectNode().put("eventId","grant").put("sequence",2).put("occurredAt",TIME)
            .put("actorStudentId","student-3-01").put("kind","GRANT");
        e.set("causes",JSON.createArrayNode().add("join")); e.set("artifactRefs",JSON.createArrayNode().add(REF));
        e.set("command",JSON.createObjectNode().put("accountId",ACCOUNT).put("amountKrw",20000).put("cashEntryId","cash-grant")
            .put("budgetMonth","2026-06").put("scheduledAt","2026-06-01T00:00:00Z"));
        e.set("outcome",JSON.createObjectNode().put("status",status).put("resultRef",REF));return e;
    }
    void events(Path p,ObjectNode... cash)throws Exception {
        var fixture=new SimulationEventTimelineTest();ObjectNode join=fixture.join().put("eventId","join");
        join.set("artifactRefs",JSON.createArrayNode().add(REF));((ObjectNode)join.get("outcome")).put("resultRef",REF);
        StringBuilder lines=new StringBuilder(JSON.writeValueAsString(join)).append('\n');
        for(ObjectNode e:cash)lines.append(JSON.writeValueAsString(e)).append('\n');
        helper.replace(p,"events.ndjson",lines.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ObjectNode manifest=helper.manifest(p);
        for(JsonNode f:manifest.get("files"))if(f.get("path").asString().equals("events.ndjson"))((ObjectNode)f).put("recordCount",cash.length+1);
        helper.save(p,manifest);
    }
    ObjectNode entry(){return JSON.createObjectNode().put("id","cash-grant").put("eventId","grant").put("accountId",ACCOUNT)
        .put("sequence",1).put("occurredAt",TIME).put("kind","GRANT").put("amountKrw",20000).put("balanceAfter",20000);}
    Path funded()throws Exception{
        Path p=helper.copy();events(p,cash("APPLIED"));ObjectNode s=state(p);
        ((ArrayNode)s.get("ledger")).add(entry());balance(s).put("amountKrw",20000).put("sequence",1);
        helper.replace(p,REF,JSON.writeValueAsBytes(s));return p;
    }
    void save(Path p,ObjectNode state)throws Exception{helper.replace(p,REF,JSON.writeValueAsBytes(state));}
    @Test void joinsActualBundleEventsToCashLedgerAndAll100BalancesAtMicrosecondPrecision()throws Exception{
        assertThat(helper.read(funded()).artifacts()).containsKey(REF);
    }
    @Test void cannotInventFundsInAnOtherwiseChecksummedEmptyBundle()throws Exception{
        Path p=helper.copy();ObjectNode s=state(p);balance(s).put("amountKrw",1);save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("FINAL_BALANCE_MISMATCH");
    }
    @Test void cannotReplaceCommandsWithAConsistentButDifferentLedgerAndCache()throws Exception{
        Path p=funded();ObjectNode s=state(p);((ObjectNode)s.get("ledger").get(0)).put("amountKrw",21000).put("balanceAfter",21000);
        balance(s).put("amountKrw",21000);save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("LEDGER_COMMAND_MISMATCH");
    }
    @Test void successfulEventRequiresExactlyOneMatchingEntry()throws Exception{
        Path p=funded();ObjectNode s=state(p);((ArrayNode)s.get("ledger")).removeAll();save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("APPLIED_COMMAND_MISSING_LEDGER");
    }
    @Test void failedAndRejectedEventsHaveNoCashEffects()throws Exception{
        for(String status:List.of("REJECTED","FAILED")){
            Path p=helper.copy();events(p,cash(status));assertThat(helper.read(p)).isNotNull();
            ObjectNode s=state(p);((ArrayNode)s.get("ledger")).add(entry());balance(s).put("amountKrw",20000).put("sequence",1);save(p,s);
            assertThatThrownBy(()->helper.read(p)).hasMessageContaining("FAILED_COMMAND_HAS_LEDGER");
        }
    }
    @Test void orphanAndDuplicateEntriesAreRejected()throws Exception{
        Path p=funded();ObjectNode s=state(p);((ObjectNode)s.get("ledger").get(0)).put("eventId","orphan");save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("APPLIED_COMMAND_MISSING_LEDGER");
        s=state(p);((ArrayNode)s.get("ledger")).removeAll().add(entry()).add(entry());save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("LEDGER_DUPLICATE");
    }
    @Test void validatesEveryAccountIncludingOwnerAndZeroBalanceAccounts()throws Exception{
        Path p=helper.copy();ObjectNode s=state(p);((ArrayNode)s.get("balances")).remove(0);save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("FINAL_BALANCE_MISMATCH");
    }
    @Test void cashBalancesMustUseLogicalAccountOrdering()throws Exception{
        Path p=helper.copy();ObjectNode s=state(p);ArrayNode a=(ArrayNode)s.get("balances");
        JsonNode first=a.get(0);a.set(0,a.get(1));a.set(1,first);save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("CASH_BALANCE_CANONICAL_ORDER");
    }
    @TestFactory List<DynamicTest> sharedCashStateSchemaRejections()throws Exception{
        JsonNode schema=JSON.readTree(Files.readAllBytes(SimulationBundleReaderTest.SCHEMA)).get("$defs").get("cashState");
        JsonNode vectors=JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/cash-state-schema-vectors.json")));
        List<DynamicTest> tests=new ArrayList<>();
        for(JsonNode v:vectors)tests.add(DynamicTest.dynamicTest(v.get("name").asString(),()->{
            ObjectNode doc=state(helper.copy());String pointer=v.get("path").asString();int slash=pointer.lastIndexOf('/');
            ((ObjectNode)doc.at(pointer.substring(0,slash))).set(pointer.substring(slash+1),v.get("value"));
            assertThatThrownBy(()->SimulationBundleReader.validate(schema,doc,"cashState")).hasMessageContaining("SCHEMA_INVALID");
        }));return tests;
    }
    @Test void allocationEventCannotMintCash()throws Exception{
        Path p=helper.copy();ObjectNode deposit=cash("APPLIED").put("kind","DEPOSIT");
        deposit.set("command",JSON.createObjectNode().put("accountId",ACCOUNT).put("wishId","wish-1").put("amount",20000).put("expectedVersion",0).put("idempotencyKey","deposit-1"));
        events(p,deposit);ObjectNode s=state(p);balance(s).put("amountKrw",20000);save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("FINAL_BALANCE_MISMATCH");
    }
    @Test void stateFromAnotherDatasetFailsEvenIfFinancialAmountsMatch()throws Exception{
        Path p=funded();ObjectNode s=state(p).put("datasetId","sha256:"+"a".repeat(64));save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("CASH_STATE_DATASET_BINDING");
    }
    @Test void rejectsArbitraryTablesFractionalAmountsAndForgedSequence()throws Exception{
        Path p=funded();ObjectNode s=state(p).put("sql","DELETE FROM ledger_event");save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("SCHEMA_INVALID");
        s.remove("sql");balance(s).put("amountKrw",0.5);save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("SCHEMA_INVALID");
        balance(s).put("amountKrw",20000).put("sequence",2);save(p,s);
        assertThatThrownBy(()->helper.read(p)).hasMessageContaining("FINAL_BALANCE_MISMATCH");
    }
}
