package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SimulationMonthlyBudgetVerifierTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String DATASET="sha256:"+"a".repeat(64);
    private static final Instant START=SimulationCashOracle.START, JULY=Instant.parse("2026-06-30T15:00:00Z");
    private final List<JsonNode> events=new ArrayList<>();
    private final ArrayNode ledger=JSON.createArrayNode();
    private Instant joined=START;
    private long balance=0, cashSequence=0;
    private ArrayNode people() {
        return JSON.createArrayNode().add(JSON.createObjectNode().put("logicalStudentId","student")
            .put("logicalAccountId","account").put("joinedAt",joined.toString()));
    }
    private ObjectNode cash() {
        var out=JSON.createObjectNode().put("datasetId",DATASET);
        out.set("ledger",ledger.deepCopy());
        out.putArray("balances").addObject().put("accountId","account").put("amountKrw",balance).put("sequence",cashSequence);
        return out;
    }
    private ObjectNode grant(long amount,Instant scheduled,Instant occurred,String status) {
        String id="event-"+(events.size()+1);
        var event=JSON.createObjectNode().put("eventId",id).put("sequence",events.size()+1)
            .put("actorStudentId","student").put("kind","GRANT").put("occurredAt",occurred.toString());
        event.putObject("outcome").put("status",status);
        event.putObject("command").put("accountId","account").put("amountKrw",amount).put("cashEntryId","cash-"+id)
            .put("budgetMonth",YearMonth.from(scheduled.atZone(ZoneId.of("Asia/Seoul"))).toString()).put("scheduledAt",scheduled.toString());
        events.add(event);
        if(status.equals("APPLIED")) {
            balance+=amount;
            ledger.addObject().put("id","cash-"+id).put("eventId",id).put("accountId","account").put("sequence",++cashSequence)
                .put("occurredAt",occurred.toString()).put("kind","GRANT").put("amountKrw",amount).put("balanceAfter",balance);
        }
        return event;
    }
    private SimulationMonthlyBudgetVerifier.Verification verify(Instant frontier) {
        return SimulationMonthlyBudgetVerifier.verify(DATASET,people(),events,cash(),frontier);
    }
    @Test void splitGrantsCountOnceAndFailedAttemptsDoNotInflateTheBudget() {
        grant(4000,START,START,"APPLIED");grant(6000,START.plusSeconds(1),START.plusSeconds(1),"APPLIED");
        grant(25000,START.plusSeconds(2),START.plusSeconds(2),"FAILED");
        grant(25000,START.plusSeconds(3),START.plusSeconds(3),"REJECTED");
        var verified=verify(JULY);
        assertThat(verified.completeMonths()).isEqualTo(1);assertThat(verified.partialMonths()).isEqualTo(1);
        assertThat(verified.months().getFirst()).isEqualTo(new SimulationMonthlyBudgetVerifier.Month("account","2026-06","COMPLETE",10000,2));
        assertThat(verified.months().getLast().grantedKrw()).isZero();
    }
    @Test void maximumIsInclusive() {
        grant(30000,START,START,"APPLIED");assertThat(verify(JULY).completeMonths()).isEqualTo(1);
    }
    @Test void lowerAndUpperLimitsFailWithAccountAndMonth() {
        grant(9999,START,START,"APPLIED");assertThatThrownBy(()->verify(JULY)).hasMessage("MONTHLY_BUDGET_COMPLETE_MONTH_RANGE:account:2026-06");
        events.clear();ledger.removeAll();balance=0;cashSequence=0;
        grant(30001,START,START,"APPLIED");assertThatThrownBy(()->verify(JULY)).hasMessageContaining("COMPLETE_MONTH_RANGE");
    }
    @Test void aMonthWithNoSuccessfulGrantCannotDisappearFromVerification() {
        assertThatThrownBy(()->verify(JULY)).hasMessageContaining("COMPLETE_MONTH_RANGE:account:2026-06");
    }
    @Test void exactSeoulBoundaryCompletesThePriorMonthWithoutCompletingTheNewMonth() {
        grant(9999,START,START,"APPLIED");
        assertThat(verify(JULY.minusNanos(1000)).completeMonths()).isZero();
        assertThatThrownBy(()->verify(JULY)).hasMessageContaining("COMPLETE_MONTH_RANGE");
    }
    @Test void joiningPartwayThroughAMonthDoesNotInventProratedOrMinimumGrants() {
        joined=START.plusSeconds(1);
        var report=verify(JULY);
        assertThat(report.completeMonths()).isZero();assertThat(report.partialMonths()).isEqualTo(2);
        assertThat(report.months()).allMatch(month->month.grantedKrw()==0);
    }
    @Test void futureOccurredGrantCannotRepairAnEarlierObservedMonth() {
        grant(20000,START,JULY.plusSeconds(1),"APPLIED");
        assertThatThrownBy(()->verify(JULY)).hasMessage("MONTHLY_BUDGET_FUTURE_EVENT");
    }
    @Test void delayedReceiptCannotRetroactivelyRepairAClosedMonth() {
        grant(20000,START,JULY.plusSeconds(1),"APPLIED");
        assertThatThrownBy(()->verify(JULY.plusSeconds(1)))
            .hasMessage("MONTHLY_BUDGET_COMPLETE_MONTH_RANGE:account:2026-06");
    }
    @Test void delayedGrantCountsInActualSeoulReceiptMonthAndPreservesScheduledEvidence() {
        grant(10000,START,START,"APPLIED");
        var delayed=grant(20000,START,JULY,"APPLIED");
        var report=verify(JULY);
        assertThat(report.months().getFirst().grantedKrw()).isEqualTo(10000);
        assertThat(report.months().getFirst().appliedGrants()).isEqualTo(1);
        assertThat(report.months().getLast().grantedKrw()).isEqualTo(20000);
        assertThat(report.months().getLast().appliedGrants()).isEqualTo(1);
        assertThat(delayed.get("command").get("budgetMonth").asString()).isEqualTo("2026-06");
        assertThat(delayed.get("command").get("scheduledAt").asString()).isEqualTo(START.toString());
    }
    @Test void nextMonthsScheduledBudgetCannotHideActualReceiptMonthOverflow() {
        grant(10000,START,START,"APPLIED");
        grant(20000,START,JULY,"APPLIED");
        grant(10001,JULY,JULY.plusSeconds(1),"APPLIED");
        assertThatThrownBy(()->verify(Instant.parse("2026-07-31T15:00:00Z")))
            .hasMessage("MONTHLY_BUDGET_COMPLETE_MONTH_RANGE:account:2026-07");
    }
    @Test void grantJustBeforeSeoulMonthBoundaryBelongsToPriorMonth() {
        grant(10000,START,JULY.minusNanos(1000),"APPLIED");
        grant(10000,START,JULY,"APPLIED");
        var report=verify(JULY);
        assertThat(report.months()).extracting(SimulationMonthlyBudgetVerifier.Month::grantedKrw)
            .containsExactly(10000L,10000L);
    }
    @Test void scheduledMonthAndActorMustMatchTheVerifiedGrant() {
        var event=grant(20000,START,START,"APPLIED");
        ((ObjectNode)event.get("command")).put("budgetMonth","2026-07");
        assertThatThrownBy(()->verify(JULY)).hasMessageContaining("GRANT_BUDGET_MONTH");
        ((ObjectNode)event.get("command")).put("budgetMonth","2026-06");event.put("actorStudentId","other");
        assertThatThrownBy(()->verify(JULY)).hasMessage("MONTHLY_BUDGET_GRANT_ACTOR");
    }
    @Test void otherwisePlausibleMonthlyTotalsCannotReplacePersistedCashEvidence() {
        grant(20000,START,START,"APPLIED");
        ((ObjectNode)ledger.get(0)).put("amountKrw",21000).put("balanceAfter",21000);balance=21000;
        assertThatThrownBy(()->verify(JULY)).hasMessageContaining("LEDGER_COMMAND_MISMATCH");
    }
    @Test void cutoffLeavesSeptemberPartialAndIncludesEveryCompletedMonth() {
        for(int month=6;month<=9;month++) {
            Instant at=LocalDate.of(2026,month,1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
            grant(month==9?1:10000,at,at,"APPLIED");
        }
        var report=verify(SimulationCashOracle.END);
        assertThat(report.completeMonths()).isEqualTo(3);assertThat(report.partialMonths()).isEqualTo(1);
        assertThat(report.months().getLast().budgetMonth()).isEqualTo("2026-09");
        assertThat(report.months().getLast().coverage()).isEqualTo("PARTIAL");
        assertThat(report.months().getLast().grantedKrw()).isEqualTo(1);
    }
    @Test void futureEnrollmentAndOutOfWindowFrontierAreRejected() {
        joined=JULY;
        assertThatThrownBy(()->verify(START)).hasMessage("MONTHLY_BUDGET_FUTURE_ENROLLMENT");
        assertThatThrownBy(()->verify(SimulationCashOracle.END.plusSeconds(1))).hasMessage("MONTHLY_BUDGET_FRONTIER");
    }
}
