package com.crabit.backend.balance;

import com.crabit.backend.wish.BalanceLookupMethod;
import com.crabit.backend.wish.BalanceObservation;
import com.crabit.backend.wish.CardBalanceObservationService;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

@Service
public class CardBalanceSyncService {

	public static final String FAILURE_CODE = "BALANCE_SYNC_FAILED";

	private final CardBalanceProvider provider;
	private final CardBalanceObservationService observations;
	private final Clock clock;
	private final ConcurrentMap<UUID, AccountRefreshLock> accountRefreshLocks =
			new ConcurrentHashMap<>();
	private final Object observationTimeMonitor = new Object();
	private Instant lastObservedAt;

	public CardBalanceSyncService(
			CardBalanceProvider provider,
			CardBalanceObservationService observations,
			Clock clock) {
		this.provider = provider;
		this.observations = observations;
		this.clock = clock;
	}

	/**
	 * Serializes one account's provider boundary and persistence while allowing different accounts
	 * to refresh independently. The provider call still completes before the observation service's
	 * database transaction begins.
	 */
	public CardBalanceSyncResult refresh(UUID accountId, BalanceLookupMethod lookupMethod) {
		UUID targetAccountId = Objects.requireNonNull(accountId, "accountId");
		BalanceLookupMethod method = Objects.requireNonNull(lookupMethod, "lookupMethod");
		AccountRefreshLock accountLock = retainAccountLock(targetAccountId);
		accountLock.lock.lock();
		try {
			return refreshSerially(targetAccountId, method);
		} finally {
			accountLock.lock.unlock();
			releaseAccountLock(targetAccountId, accountLock);
		}
	}

	private CardBalanceSyncResult refreshSerially(
			UUID accountId, BalanceLookupMethod lookupMethod) {
		Instant observedAt = nextObservedAt();
		CardBalanceProviderResult providerResult;
		try {
			providerResult = provider.lookup(accountId);
		} catch (RuntimeException providerFailure) {
			providerResult = CardBalanceProviderResult.failure();
		}

		if (providerResult instanceof CardBalanceProviderResult.Success success) {
			BalanceObservation observation = observations.recordSuccess(
					accountId, lookupMethod, success.balance(), observedAt);
			return new CardBalanceSyncResult.Success(observation);
		}
		BalanceObservation observation = observations.recordFailure(
				accountId, lookupMethod, FAILURE_CODE, observedAt);
		return new CardBalanceSyncResult.Failure(observation);
	}

	private AccountRefreshLock retainAccountLock(UUID accountId) {
		return accountRefreshLocks.compute(accountId, (ignored, current) -> {
			AccountRefreshLock retained = current == null ? new AccountRefreshLock() : current;
			retained.references++;
			return retained;
		});
	}

	private void releaseAccountLock(UUID accountId, AccountRefreshLock released) {
		accountRefreshLocks.compute(accountId, (ignored, current) -> {
			if (current != released || current.references <= 0) {
				throw new IllegalStateException("Account refresh lock ownership was lost");
			}
			current.references--;
			return current.references == 0 ? null : current;
		});
	}

	private Instant nextObservedAt() {
		synchronized (observationTimeMonitor) {
			Instant clockTime = clock.instant();
			if (lastObservedAt == null || clockTime.isAfter(lastObservedAt)) {
				lastObservedAt = clockTime;
				return clockTime;
			}
			try {
				lastObservedAt = lastObservedAt.plusNanos(1_000);
				return lastObservedAt;
			} catch (DateTimeException exhaustedInstantRange) {
				throw new IllegalStateException(
						"Cannot allocate another card balance observation time",
						exhaustedInstantRange);
			}
		}
	}

	private static final class AccountRefreshLock {

		private final ReentrantLock lock = new ReentrantLock(true);
		private int references;
	}
}
