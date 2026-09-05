package com.crabit.backend.recap;

import com.crabit.backend.account.CardBalanceAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecapGenerationCoordinator {
	static final Duration RUNNING_LEASE = Duration.ofMinutes(2);
	private final RecapGenerationRepository generations;
	private final CardBalanceAccountRepository accounts;
	public RecapGenerationCoordinator(RecapGenerationRepository generations, CardBalanceAccountRepository accounts) {
		this.generations = generations; this.accounts = accounts;
	}

	@Transactional
	public RecapGeneration reserveScheduled(UUID account, RecapKind kind, RecapPeriods.Period period, Instant now) {
		String key = "scheduled:1:recap-1:" + account + ":" + kind + ":" + period.start() + ":" + period.endExclusive();
		return reservePreparation(key, true, account, kind, period, now);
	}

	@Transactional
	public RecapGeneration reserveRegeneration(UUID requestKey, UUID account, RecapKind kind, RecapPeriods.Period period, Instant now) {
		return reservePreparation("explicit:" + Objects.requireNonNull(requestKey), false, account, kind, period, now);
	}

	private RecapGeneration reservePreparation(String key, boolean scheduled, UUID account, RecapKind kind,
			RecapPeriods.Period period, Instant now) {
		var owner = accounts.lockById(account).filter(a -> a.isActive()).orElseThrow(() -> new IllegalArgumentException("Active account is required"));
		var existing = generations.findByReservationKey(key);
		if (existing.isPresent()) {
			var g = existing.get();
			if (!g.accountId().equals(account) || g.kind() != kind || !g.periodStart().equals(period.start())
					|| !g.periodEndExclusive().equals(period.endExclusive())) throw new IllegalArgumentException("Reservation key is bound to another target");
			return g;
		}
		var rows = generations.lockLogical(account, kind, period.start(), period.endExclusive());
		if (scheduled) {
			var legacy = rows.stream().filter(g -> g.reservationKey() == null && g.stage() == RecapGenerationStage.GENERATION)
					.min(Comparator.comparingLong(RecapGeneration::generationVersion).thenComparing(RecapGeneration::id));
			if (legacy.isPresent()) { legacy.get().bindReservation(key); return legacy.get(); }
		}
		long version = nextVersion(rows);
		var generation = new RecapGeneration(UUID.randomUUID(), account, owner.studentId(), owner.academyId(), kind,
				period.start(), period.endExclusive(), version, null, null, now);
		generation.bindReservation(key);
		return generations.save(generation);
	}

	/** Compatibility entry point for an already frozen input; scheduling uses reservation identity instead. */
	@Transactional
	public RecapGeneration reserve(UUID id, UUID account, UUID student, UUID academy, RecapKind kind,
			LocalDate start, LocalDate end, String inputDigest, String requestJson, Instant now) {
		accounts.lockById(account).orElseThrow();
		var byId = generations.findById(id);
		if (byId.isPresent()) {
			var g = byId.get();
			if (!Objects.equals(g.inputDigest(), inputDigest) || !Objects.equals(g.requestJson(), requestJson)
					|| !g.accountId().equals(account) || g.kind() != kind || !g.periodStart().equals(start)
					|| !g.periodEndExclusive().equals(end)) throw new IllegalStateException("Generation id is already bound to another input");
			return g;
		}
		var rows = generations.lockLogical(account, kind, start, end);
		for (var row : rows) if (Objects.equals(row.inputDigest(), inputDigest)) return row;
		return generations.save(new RecapGeneration(id, account, student, academy, kind, start, end, nextVersion(rows), inputDigest, requestJson, now));
	}

	@Transactional
	public RecapGeneration reserveNotEligible(UUID id, UUID account, UUID student, UUID academy, RecapKind kind,
			LocalDate start, LocalDate end, String inputDigest, String requestJson, Instant now) {
		var g = reserve(id, account, student, academy, kind, start, end, inputDigest, requestJson, now);
		if (g.state() == RecapGenerationState.PENDING) {
			g.notEligible(now); publish(g, generations.lockLogical(account, kind, start, end));
		}
		return g;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<PreparationClaim> claimPreparation(Instant now) {
		for (var g : generations.lockPreparationReady(now, now.minus(RUNNING_LEASE))) {
			if (g.preparationAttemptCount() >= 3) { g.fail("PREPARATION_RETRY_EXHAUSTED", false, now, null); continue; }
			g.startPreparation(now);
			return Optional.of(new PreparationClaim(g.id(), g.accountId(), g.studentId(), g.academyId(), g.kind(),
					new RecapPeriods.Period(g.periodStart(), g.periodEndExclusive()), g.preparationAttemptCount()));
		}
		return Optional.empty();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void prepared(PreparationClaim claim, RecapSnapshotService.Snapshot snapshot, Instant now) {
		accounts.lockById(claim.accountId()).orElseThrow();
		var rows = generations.lockLogical(claim.accountId(), claim.kind(), claim.period().start(), claim.period().endExclusive());
		var g = rows.stream().filter(row -> row.id().equals(claim.id())).findFirst().orElseThrow();
		if (!g.ownsPreparation(claim.attempt())) return;
		if (!snapshot.generationId().equals(g.id()) || !snapshot.studentId().equals(g.studentId())
				|| !snapshot.academyId().equals(g.academyId())) throw new IllegalArgumentException("Snapshot identity mismatch");
		g.freeze(snapshot.inputDigest(), snapshot.requestJson());
		if (g.kind() == RecapKind.MONTHLY && snapshot.effectiveDepositCount() < 3) { g.notEligible(now); publish(g, rows); }
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failPreparation(PreparationClaim claim, boolean retryable, Instant now) {
		var g = generations.lockById(claim.id()).orElseThrow();
		if (!g.ownsPreparation(claim.attempt())) return;
		fail(g, "PREPARATION_FAILED", retryable, g.preparationAttemptCount(), now);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<Claim> claim(Instant now) {
		for (var g : generations.lockReady(now, now.minus(RUNNING_LEASE))) {
			if (g.attemptCount() >= 3) { g.fail("RETRY_EXHAUSTED", false, now, null); continue; }
			g.start(now);
			return Optional.of(new Claim(g.id(), g.accountId(), g.studentId(), g.academyId(), g.kind(), g.inputDigest(), g.requestJson(), g.attemptCount()));
		}
		return Optional.empty();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void succeed(Claim claim, String view, String metrics, Instant now) {
		accounts.lockById(claim.accountId()).orElseThrow();
		var observed = generations.findByIdAndInputDigest(claim.id(), claim.inputDigest()).orElseThrow();
		var rows = generations.lockLogical(observed.accountId(), observed.kind(), observed.periodStart(), observed.periodEndExclusive());
		var g = rows.stream().filter(row -> row.id().equals(claim.id()) && Objects.equals(row.inputDigest(), claim.inputDigest())).findFirst().orElseThrow();
		if (g.attemptCount() == claim.attempt() && g.generatedAt() != null) {
			if (!Objects.equals(g.viewJson(), view) || !Objects.equals(g.internalMetricsJson(), metrics))
				throw new IllegalStateException("Completed recap result conflict");
			return;
		}
		if (!g.ownsClaim(claim.attempt())) return;
		g.succeed(view, metrics, now); publish(g, rows);
	}

	private void publish(RecapGeneration g, List<RecapGeneration> rows) {
		if (rows.stream().anyMatch(row -> row.currentVersion() && row.generationVersion() > g.generationVersion())) return;
		for (var row : rows) if (row.currentVersion() && !row.id().equals(g.id())) row.supersede();
		// The partial unique index is immediate: release the old current before setting the new one.
		generations.flush();
		g.makeCurrent();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(Claim claim, String code, boolean retryable, Instant now) {
		var g = generations.lockByIdAndInputDigest(claim.id(), claim.inputDigest()).orElseThrow();
		if (!g.ownsClaim(claim.attempt())) return;
		fail(g, code, retryable, g.attemptCount(), now);
	}
	private static void fail(RecapGeneration g, String code, boolean retryable, int attempt, Instant now) {
		boolean retry = retryable && attempt < 3;
		g.fail(code, retry, now, retry ? now.plusSeconds((1L << Math.max(0, attempt - 1)) * 60) : null);
	}
	private static long nextVersion(List<RecapGeneration> rows) {
		return Math.addExact(rows.stream().mapToLong(RecapGeneration::generationVersion).max().orElse(0), 1);
	}
	public record PreparationClaim(UUID id, UUID accountId, UUID studentId, UUID academyId, RecapKind kind,
			RecapPeriods.Period period, int attempt) {}
	public record Claim(UUID id, UUID accountId, UUID studentId, UUID academyId, RecapKind kind,
			String inputDigest, String requestJson, int attempt) {}
}
