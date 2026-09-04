package com.crabit.backend.recap;

import com.crabit.backend.account.CardBalanceAccountRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "crabit.recap.generation.enabled", havingValue = "true")
final class RecapGenerationJob {
	private static final Logger LOG=LoggerFactory.getLogger(RecapGenerationJob.class);
	private final RecapGenerationCoordinator coordinator; private final RecapPythonClient client;
	private final RecapSnapshotService snapshots; private final CardBalanceAccountRepository accounts;
	private final Clock clock=Clock.systemUTC();
	RecapGenerationJob(RecapGenerationCoordinator coordinator, RecapPythonClient client,
			RecapSnapshotService snapshots, CardBalanceAccountRepository accounts){this.coordinator=coordinator;this.client=client;this.snapshots=snapshots;this.accounts=accounts;}
	@Scheduled(cron="0 10 0 * * MON",zone="Asia/Seoul")
	void reserveWeekly(){LocalDate end=LocalDate.now(clock.withZone(RecapPeriods.SEOUL)).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));reserve(RecapKind.WEEKLY,new RecapPeriods.Period(end.minusWeeks(1),end));}
	@Scheduled(cron="0 20 0 1 * *",zone="Asia/Seoul")
	void reserveMonthly(){YearMonth month=YearMonth.now(clock.withZone(RecapPeriods.SEOUL)).minusMonths(1);reserve(RecapKind.MONTHLY,new RecapPeriods.Period(month.atDay(1),month.plusMonths(1).atDay(1)));}
	private void reserve(RecapKind kind,RecapPeriods.Period period){for(var account:accounts.findByClosedAtIsNullOrderByIdAsc())try{var s=snapshots.build(account.id(),kind,period);if(kind==RecapKind.MONTHLY&&s.effectiveDepositCount()<3)coordinator.reserveNotEligible(s.generationId(),account.id(),s.studentId(),s.academyId(),kind,period.start(),period.endExclusive(),s.inputDigest(),s.requestJson(),clock.instant());else coordinator.reserve(s.generationId(),account.id(),s.studentId(),s.academyId(),kind,period.start(),period.endExclusive(),s.inputDigest(),s.requestJson(),clock.instant());}catch(RuntimeException e){LOG.warn("recap_reservation_failed account_id={} kind={} period_start={}",account.id(),kind,period.start(),e);}}
	@Scheduled(fixedDelayString="${crabit.recap.generation.poll-delay-ms:30000}")
	void runReady(){for(int i=0;i<100;i++){var claim=coordinator.claim(clock.instant());if(claim.isEmpty())return;try{var result=client.generate(claim.get());coordinator.succeed(claim.get(),result.viewJson(),result.internalMetricsJson(),clock.instant());}catch(RecapTransportException e){coordinator.fail(claim.get(),e.code(),e.retryable(),clock.instant());}}}
}
