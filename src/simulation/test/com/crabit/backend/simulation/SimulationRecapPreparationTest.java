package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.*;
import java.util.*;
import com.crabit.backend.recap.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class SimulationRecapPreparationTest {
    @Test void closureUsesKstCompletedPeriodsAndExactWeekAndMonthBoundaries() {
        var weeklyEnd=Instant.parse("2026-06-07T15:00:00Z");
        var start=LocalDate.parse("2026-06-01");var end=LocalDate.parse("2026-06-08");
        assertThat(SimulationRecapPreparation.period("CLOSE_WEEK",start,end,Clock.fixed(weeklyEnd,ZoneOffset.UTC)).endExclusive()).isEqualTo(end);
        assertThatThrownBy(()->SimulationRecapPreparation.period("CLOSE_WEEK",start,end,Clock.fixed(weeklyEnd.minusNanos(1000),ZoneOffset.UTC))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->SimulationRecapPreparation.period("CLOSE_WEEK",start,end.plusDays(1),Clock.fixed(weeklyEnd,ZoneOffset.UTC))).hasMessage("RECAP_PERIOD_BOUNDARY");
        var monthlyEnd=Clock.fixed(Instant.parse("2026-06-30T15:00:00Z"),ZoneOffset.UTC);
        assertThat(SimulationRecapPreparation.period("CLOSE_MONTH",start,LocalDate.parse("2026-07-01"),monthlyEnd).start()).isEqualTo(start);
        assertThatThrownBy(()->SimulationRecapPreparation.period("CLOSE_MONTH",start.plusDays(1),LocalDate.parse("2026-07-01"),monthlyEnd)).hasMessage("RECAP_PERIOD_BOUNDARY");
        assertThatThrownBy(()->SimulationRecapPreparation.period("CLOSE_MONTH",LocalDate.parse("2026-05-01"),start,monthlyEnd)).hasMessage("RECAP_PERIOD_OUTSIDE_SIMULATION");
    }
    @TestFactory java.util.List<DynamicTest> alteredSnapshotIdentityPeriodTimeAndCountAreRejected() {
        UUID student=UUID.randomUUID(),account=UUID.randomUUID(),academy=UUID.randomUUID(),generation=UUID.randomUUID();
        var json=JsonMapper.builder().build();
        var now=Instant.parse("2026-06-07T15:00:00Z");
        var period=new RecapPeriods.Period(LocalDate.parse("2026-06-01"),LocalDate.parse("2026-06-08"));
        var base=json.createObjectNode().put("student_id",student.toString()).put("card_balance_account_id",account.toString())
            .put("academy_id",academy.toString()).put("generation_id",generation.toString()).put("kind","WEEKLY")
            .put("input_digest","sha256:"+"a".repeat(64)).put("snapshot_at",now.toString()).put("reference_date","2026-06-07");
        base.putObject("period").put("start_date","2026-06-01").put("end_date_exclusive","2026-06-08").put("timezone","Asia/Seoul");
        base.putObject("input").putArray("effective_transactions").addObject().put("occurred_at",now.minusNanos(1000).toString()).put("type","DEPOSIT");
        Map<String,java.util.function.Consumer<ObjectNode>> cases=new LinkedHashMap<>();
        cases.put("RECAP_REQUEST_IDENTITY",n->n.put("generation_id",UUID.randomUUID().toString()));
        cases.put("RECAP_REQUEST_BINDING",n->n.put("snapshot_at",now.plusSeconds(1).toString()));
        cases.put("RECAP_REQUEST_PERIOD",n->((ObjectNode)n.get("period")).put("timezone","UTC"));
        cases.put("RECAP_FUTURE_TRANSACTION",n->((ObjectNode)n.get("input").get("effective_transactions").get(0)).put("occurred_at",now.toString()));
        cases.put("RECAP_DEPOSIT_COUNT",n->((ObjectNode)n.get("input")).putArray("effective_transactions"));
        return cases.entrySet().stream().map(entry->DynamicTest.dynamicTest(entry.getKey(),()->{
            var snapshot=new RecapSnapshotService.Snapshot(generation,student,academy,base.get("input_digest").asString(),json.writeValueAsString(base),1);
            SimulationRecapPreparation.verifySnapshot(snapshot,student,account,academy,generation,RecapKind.WEEKLY,period,now);
            var changed=base.deepCopy();entry.getValue().accept(changed);
            var altered=new RecapSnapshotService.Snapshot(generation,student,academy,base.get("input_digest").asString(),json.writeValueAsString(changed),1);
            assertThatThrownBy(()->SimulationRecapPreparation.verifySnapshot(altered,student,account,academy,generation,RecapKind.WEEKLY,period,now)).hasMessage(entry.getKey());
        })).toList();
    }

}
