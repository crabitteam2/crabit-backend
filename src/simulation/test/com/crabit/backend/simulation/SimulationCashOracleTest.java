package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import static com.crabit.backend.simulation.SimulationCashOracle.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SimulationCashOracleTest {
    static final Path ROOT=Path.of("src/test/resources/simulation/cash-oracle");
    static final JsonMapper JSON=JsonMapper.builder().build();
    static ObjectNode fixture()throws Exception{return (ObjectNode)JSON.readTree(Files.readAllBytes(ROOT.resolve("valid.json")));}
    static Result check(JsonNode n)throws Exception{return SimulationCashCheck.check(JSON.writeValueAsBytes(n));}
    @Test void recomputesCashWithoutAcceptingFailedCommandsOrTreatingPurchasesAsAllocations()throws Exception {
        var result=check(fixture());
        assertThat(result.balances()).containsEntry("a",new Balance("a",7000,2)).containsEntry("b",new Balance("b",20000,1));
        assertThat(result.applied()).isEqualTo(3);assertThat(result.rejected()).isEqualTo(1);assertThat(result.failed()).isEqualTo(1);
        assertThat(result.grants().get("a")).containsEntry(YearMonth.of(2026,6),10000L);
        new SimulationCashOracle().verifyFullMonthBudget(result,"a",YearMonth.of(2026,6));
        assertThatThrownBy(()->new SimulationCashOracle().verifyFullMonthBudget(result,"a",YearMonth.of(2026,9)))
            .hasMessageContaining("BUDGET_PARTIAL_MONTH");
        assertThatThrownBy(()->new SimulationCashOracle().verifyFullMonthBudget(result,"a",YearMonth.of(2026,7)))
            .hasMessageContaining("MONTHLY_GRANT_BUDGET");
        assertThatThrownBy(()->result.balances().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->result.grants().get("a").clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void delayedGrantsBelongToActualReceiptMonthAtKstBoundary() {
        Instant received=Instant.parse("2026-06-30T15:00:00Z");
        var oracle=new SimulationCashOracle();
        var result=oracle.verify(List.of(new Account("a",START)),
            List.of(new Command("delayed",1,"a",received,Kind.GRANT,10000,"entry",Outcome.APPLIED,
                "2026-06",Instant.parse("2026-06-30T14:59:59Z"))),
            List.of(new Entry("entry","delayed","a",1,received,Kind.GRANT,10000,10000)),
            List.of(new Balance("a",10000,1)));
        assertThat(result.grants().get("a")).containsOnlyKeys(YearMonth.of(2026,7));
        oracle.verifyFullMonthBudget(result,"a",YearMonth.of(2026,7));
        assertThatThrownBy(()->oracle.verifyFullMonthBudget(result,"a",YearMonth.of(2026,6)))
            .hasMessageContaining("MONTHLY_GRANT_BUDGET");
    }
    @TestFactory List<DynamicTest> sharedCashRejectionVectors()throws Exception {
        List<DynamicTest> cases=new ArrayList<>();
        for(JsonNode vector:JSON.readTree(Files.readAllBytes(ROOT.resolve("rejection-vectors.json")))) {
            cases.add(DynamicTest.dynamicTest(vector.get("name").asString(),()->{
                ObjectNode n=fixture();String pointer=vector.get("path").asString();int split=pointer.lastIndexOf('/');
                ((ObjectNode)n.at(pointer.substring(0,split))).set(pointer.substring(split+1),vector.get("value"));
                assertThatThrownBy(()->check(n)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(vector.get("rule").asString());
            }));
        }
        return cases;
    }
    @Test void detectsMissingOrExtraLedgerAndMissingBalances()throws Exception {
        ObjectNode missing=fixture();((ArrayNode)missing.get("ledger")).remove(1);
        assertThatThrownBy(()->check(missing)).hasMessageContaining("APPLIED_COMMAND_MISSING_LEDGER");
        ObjectNode orphan=fixture();ObjectNode extra=(ObjectNode)orphan.get("ledger").get(0).deepCopy();
        extra.put("eventId","orphan");extra.put("id","cash-orphan");((ArrayNode)orphan.get("ledger")).add(extra);
        assertThatThrownBy(()->check(orphan)).hasMessageContaining("ORPHAN_LEDGER");
        ObjectNode balances=fixture();((ArrayNode)balances.get("balances")).remove(1);
        assertThatThrownBy(()->check(balances)).hasMessageContaining("FINAL_BALANCE_MISMATCH");
    }
    @Test void rejectsLedgerReorderingInsteadOfSortingAwayCausality()throws Exception {
        ObjectNode n=fixture();ArrayNode ledger=(ArrayNode)n.get("ledger");JsonNode first=ledger.get(0),second=ledger.get(1);
        ledger.set(0,second);ledger.set(1,first);
        assertThatThrownBy(()->check(n)).hasMessageContaining("LEDGER_CAUSAL_ORDER");
    }
    @Test void safeIntegerOverflowIsRejectedBeforeSerialization()throws Exception {
        ObjectNode n=fixture();ObjectNode grant=(ObjectNode)n.get("commands").get(0);grant.put("amountKrw",MAX_WON);
        ObjectNode entry=(ObjectNode)n.get("ledger").get(0);entry.put("amountKrw",MAX_WON);entry.put("balanceAfter",MAX_WON);
        ObjectNode next=(ObjectNode)n.get("commands").get(1);next.put("kind","GRANT");next.put("amountKrw",1);
        next.put("budgetMonth","2026-06");next.put("scheduledAt",next.get("occurredAt").asString());
        assertThatThrownBy(()->check(n)).hasMessageContaining("CASH_RANGE");
    }
    @Test void rejectsDuplicateKeysAndUnsupportedVersions()throws Exception {
        assertThatThrownBy(()->SimulationCashCheck.check("{\"schemaVersion\":1,\"schemaVersion\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .hasMessageContaining("SCHEMA_INVALID");
        ObjectNode n=fixture();n.put("schemaVersion",2);
        assertThatThrownBy(()->check(n)).hasMessageContaining("SCHEMA_INVALID");
    }
    @Test void emptyLedgerCannotHideFundedExport()throws Exception {
        ObjectNode n=fixture();n.set("commands",JSON.createArrayNode());n.set("ledger",JSON.createArrayNode());
        assertThatThrownBy(()->check(n)).hasMessageContaining("FINAL_BALANCE_MISMATCH");
        for(JsonNode balance:n.get("balances")){((ObjectNode)balance).put("amountKrw",0);((ObjectNode)balance).put("sequence",0);}
        assertThat(check(n).applied()).isZero();
    }
}
