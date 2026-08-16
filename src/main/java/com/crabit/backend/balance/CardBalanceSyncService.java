package com.crabit.backend.balance;

import com.crabit.backend.wish.BalanceLookupMethod;
import com.crabit.backend.wish.BalanceObservation;
import com.crabit.backend.wish.CardBalanceObservationService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CardBalanceSyncService {

	public static final String FAILURE_CODE = "BALANCE_SYNC_FAILED";

	private final CardBalanceProvider provider;
	private final CardBalanceObservationService observations;
	private final Clock clock;

	public CardBalanceSyncService(
			CardBalanceProvider provider,
			CardBalanceObservationService observations,
			Clock clock) {
		this.provider = provider;
		this.observations = observations;
		this.clock = clock;
	}

	/**
	 * Calls the external boundary before entering the observation service's database transaction.
	 */
	public CardBalanceSyncResult refresh(UUID accountId, BalanceLookupMethod lookupMethod) {
		UUID targetAccountId = Objects.requireNonNull(accountId, "accountId");
		BalanceLookupMethod method = Objects.requireNonNull(lookupMethod, "lookupMethod");
		Instant observedAt = clock.instant();
		CardBalanceProviderResult providerResult;
		try {
			providerResult = provider.lookup(targetAccountId);
		} catch (RuntimeException providerFailure) {
			providerResult = CardBalanceProviderResult.failure();
		}

		if (providerResult instanceof CardBalanceProviderResult.Success success) {
			BalanceObservation observation = observations.recordSuccess(
					targetAccountId, method, success.balance(), observedAt);
			return new CardBalanceSyncResult.Success(observation);
		}
		BalanceObservation observation = observations.recordFailure(
				targetAccountId, method, FAILURE_CODE, observedAt);
		return new CardBalanceSyncResult.Failure(observation);
	}
}
