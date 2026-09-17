package com.crabit.backend.simulation;

import com.crabit.backend.recap.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Local preparation only: freezes the actual snapshot; never fabricates a Python result. */
public final class SimulationRecapPreparation {
    private SimulationRecapPreparation() {}
    public record Prepared(UUID generationId, String inputDigest, String requestJson,
                           String state, long generationVersion, boolean reused) {}

    public static RecapPeriods.Period period(String command, LocalDate start, LocalDate end, Clock clock) {
        RecapPeriods.Period period=switch(command) {
            case "CLOSE_WEEK" -> RecapPeriods.weekly(start.toString(),clock);
            case "CLOSE_MONTH" -> RecapPeriods.monthly(YearMonth.from(start).toString(),clock);
            default -> throw new IllegalArgumentException("RECAP_COMMAND_KIND");
        };
        require(period.start().equals(start) && period.endExclusive().equals(end),"RECAP_PERIOD_BOUNDARY");
        require(!start.atStartOfDay(RecapPeriods.SEOUL).toInstant().isBefore(SimulationCashOracle.START)
            && !end.atStartOfDay(RecapPeriods.SEOUL).toInstant().isAfter(SimulationCashOracle.END),"RECAP_PERIOD_OUTSIDE_SIMULATION");
        return period;
    }

    /** Invoke synchronously inside executeAt; generation identity is retained by the caller. */
    public static Prepared prepare(SimulationDomainRuntime.Services services,String dataset,UUID student,
            UUID account,UUID generation,String command,LocalDate start,LocalDate end) {
        Objects.requireNonNull(generation);
        var period=period(command,start,end,services.clock());
        RecapKind kind=command.equals("CLOSE_WEEK")?RecapKind.WEEKLY:RecapKind.MONTHLY;
        var owners=services.jdbc().query("""
            SELECT a.academy_id FROM card_balance_account a
            JOIN demo_simulation_account x ON x.account_id=a.id AND x.dataset_id=?
            JOIN demo_simulation_dataset d ON d.dataset_id=x.dataset_id AND d.state='BUILDING'
            WHERE a.id=? AND a.student_id=? AND a.closed_at IS NULL AND a.opened_at<?
            AND EXISTS (SELECT 1 FROM academy_membership m WHERE m.student_id=a.student_id
                AND m.academy_id=a.academy_id AND m.left_at IS NULL)
            """,(rs,n)->rs.getObject(1,UUID.class),dataset,account,student,
            java.sql.Timestamp.from(end.atStartOfDay(RecapPeriods.SEOUL).toInstant()));
        require(owners.size()==1,"RECAP_ACTIVE_SIMULATION_OWNER");
        var repository=services.service(RecapGenerationRepository.class);
        var existing=repository.findById(generation);
        if(existing.isPresent()) {
            var g=existing.get();
            require(g.accountId().equals(account) && g.studentId().equals(student) && g.academyId().equals(owners.getFirst())
                && g.kind()==kind && g.periodStart().equals(start) && g.periodEndExclusive().equals(end),"RECAP_GENERATION_CONFLICT");
            require(g.requestJson()!=null && g.inputDigest()!=null,"RECAP_GENERATION_NOT_FROZEN");
            return result(g,true);
        }
        var snapshot=services.service(RecapSnapshotService.class).build(generation,account,kind,period);
        verifySnapshot(snapshot,student,account,owners.getFirst(),generation,kind,period,services.clock().instant());
        var coordinator=services.service(RecapGenerationCoordinator.class);
        var saved=kind==RecapKind.MONTHLY && snapshot.effectiveDepositCount()<3
            ?coordinator.reserveNotEligible(generation,account,student,owners.getFirst(),kind,start,end,
                snapshot.inputDigest(),snapshot.requestJson(),services.clock().instant())
            :coordinator.reserve(generation,account,student,owners.getFirst(),kind,start,end,
                snapshot.inputDigest(),snapshot.requestJson(),services.clock().instant());
        // reserve can deduplicate an identical frozen input: expose its authoritative identity.
        return result(repository.findById(saved.id()).orElseThrow(),false);
    }

    static void verifySnapshot(RecapSnapshotService.Snapshot snapshot,UUID student,UUID account,UUID academy,
            UUID generation,RecapKind kind,RecapPeriods.Period period,Instant now) {
        JsonNode request=SimulationBundleReader.parse(snapshot.requestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        require(snapshot.generationId().equals(generation) && snapshot.studentId().equals(student)
            && snapshot.academyId().equals(academy),"RECAP_SNAPSHOT_IDENTITY");
        for(var binding:Map.of("student_id",student,"card_balance_account_id",account,"academy_id",academy,"generation_id",generation).entrySet())
            require(binding.getValue().toString().equals(request.get(binding.getKey()).asString()),"RECAP_REQUEST_IDENTITY");
        require(request.get("kind").asString().equals(kind.name())
            && request.get("input_digest").asString().equals(snapshot.inputDigest())
            && Instant.parse(request.get("snapshot_at").asString()).equals(now),"RECAP_REQUEST_BINDING");
        var p=request.get("period");
        require(p.get("start_date").asString().equals(period.start().toString())
            && p.get("end_date_exclusive").asString().equals(period.endExclusive().toString())
            && p.get("timezone").asString().equals("Asia/Seoul")
            && request.get("reference_date").asString().equals(period.endExclusive().minusDays(1).toString()),"RECAP_REQUEST_PERIOD");
        Instant from=period.start().atStartOfDay(RecapPeriods.SEOUL).toInstant();
        Instant until=period.endExclusive().atStartOfDay(RecapPeriods.SEOUL).toInstant();
        long deposits=0;
        for(JsonNode tx:request.get("input").get("effective_transactions")) {
            Instant at=Instant.parse(tx.get("occurred_at").asString());
            require(at.isBefore(until) && !at.isAfter(now),"RECAP_FUTURE_TRANSACTION");
            if(!at.isBefore(from) && tx.get("type").asString().equals("DEPOSIT"))deposits++;
        }
        require(deposits==snapshot.effectiveDepositCount(),"RECAP_DEPOSIT_COUNT");
    }
    private static Prepared result(RecapGeneration g,boolean reused) {
        return new Prepared(g.id(),g.inputDigest(),g.requestJson(),g.state().name(),g.generationVersion(),reused);
    }
    private static void require(boolean condition,String code) { if(!condition)throw new IllegalArgumentException(code); }
}
