package com.crabit.backend.recap;

import com.crabit.backend.account.CardBalanceAccountRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Component
@ConditionalOnProperty(name = "crabit.recap.generation.enabled", havingValue = "true")
final class RecapGenerationJob {
	private static final Logger LOG = LoggerFactory.getLogger(RecapGenerationJob.class);
	private final RecapGenerationCoordinator coordinator;
	private final RecapPythonClient client;
	private final RecapSnapshotService snapshots;
	private final CardBalanceAccountRepository accounts;
	private final Clock clock = Clock.systemUTC();
	// Retain failed reservation batches for the next poll. Lost batches across a process outage use the trusted recovery command.
	private final Map<String, ReservationBatch> pendingReservations = new ConcurrentHashMap<>();
	RecapGenerationJob(RecapGenerationCoordinator coordinator, RecapPythonClient client,
			RecapSnapshotService snapshots, CardBalanceAccountRepository accounts) {
		this.coordinator = coordinator; this.client = client; this.snapshots = snapshots; this.accounts = accounts;
	}
	@Scheduled(cron = "0 10 0 * * MON", zone = "Asia/Seoul")
	void reserveWeekly() {
		LocalDate end = LocalDate.now(clock.withZone(RecapPeriods.SEOUL)).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		reserve(RecapKind.WEEKLY, new RecapPeriods.Period(end.minusWeeks(1), end));
	}
	@Scheduled(cron = "0 20 0 1 * *", zone = "Asia/Seoul")
	void reserveMonthly() {
		YearMonth month = YearMonth.now(clock.withZone(RecapPeriods.SEOUL)).minusMonths(1);
		reserve(RecapKind.MONTHLY, new RecapPeriods.Period(month.atDay(1), month.plusMonths(1).atDay(1)));
	}
	void reserve(RecapKind kind, RecapPeriods.Period period) {
		String key = kind + ":" + period.start();
		pendingReservations.putIfAbsent(key, new ReservationBatch(kind, period));
		retryReservations();
	}
	private void retryReservations() {
		for (var entry : pendingReservations.entrySet()) {
			boolean complete = true;
			try {
				for (var account : accounts.findByClosedAtIsNullOrderByIdAsc()) {
					try { coordinator.reserveScheduled(account.id(), entry.getValue().kind(), entry.getValue().period(), clock.instant()); }
					catch (RuntimeException e) { complete = false; LOG.warn("recap_reservation_failed account_id={} kind={}", account.id(), entry.getValue().kind()); }
				}
			} catch (RuntimeException e) { complete = false; LOG.warn("recap_reservation_batch_unavailable"); }
			if (complete) pendingReservations.remove(entry.getKey(), entry.getValue());
		}
	}
	@Scheduled(fixedDelayString = "${crabit.recap.generation.poll-delay-ms:30000}")
	void runReady() {
		retryReservations();
		prepareReady();
		for (int i = 0; i < 100; i++) {
			try {
				var next = coordinator.claim(clock.instant());
				if (next.isEmpty()) return;
				var claim = next.get();
				try {
					var result = client.generate(claim);
					coordinator.succeed(claim, result.viewJson(), result.internalMetricsJson(), clock.instant());
				} catch (RecapTransportException e) { coordinator.fail(claim, e.code(), e.retryable(), clock.instant()); }
				catch (RuntimeException e) { coordinator.fail(claim, "GENERATION_STORAGE_FAILED", transientFailure(e), clock.instant()); }
			} catch (RuntimeException e) { LOG.warn("recap_generation_poll_unavailable"); return; }
		}
	}
	private void prepareReady() {
		for (int i = 0; i < 100; i++) {
			try {
				var next = coordinator.claimPreparation(clock.instant());
				if (next.isEmpty()) return;
				var claim = next.get();
				try {
					var snapshot = snapshots.build(claim.id(), claim.accountId(), claim.kind(), claim.period());
					coordinator.prepared(claim, snapshot, clock.instant());
				} catch (RuntimeException e) { coordinator.failPreparation(claim, transientFailure(e), clock.instant()); }
			} catch (RuntimeException e) { LOG.warn("recap_preparation_poll_unavailable"); return; }
		}
	}
	private static boolean transientFailure(RuntimeException e) {
		return e instanceof DataAccessException || e instanceof TransactionException;
	}
	private record ReservationBatch(RecapKind kind, RecapPeriods.Period period) {}
}
