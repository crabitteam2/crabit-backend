package com.crabit.backend.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crabit.backend.wish.BalanceLookupMethod;
import com.crabit.backend.wish.BalanceObservation;
import com.crabit.backend.wish.CardBalanceObservationService;
import com.crabit.backend.wish.DepositBalanceProof;
import com.crabit.backend.wish.KrwAmount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class CardBalanceSyncServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-17T01:02:03Z");

	private CardBalanceProvider provider;
	private CardBalanceObservationService observations;
	private CardBalanceSyncService service;

	@BeforeEach
	void setUp() {
		provider = mock(CardBalanceProvider.class);
		observations = mock(CardBalanceObservationService.class);
		service = new CardBalanceSyncService(
				provider, observations, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void callsTheProviderBeforeEnteringTransactionalSuccessPersistence() {
		UUID accountId = UUID.randomUUID();
		KrwAmount balance = KrwAmount.nonNegative(125_000);
		BalanceObservation persisted = mock(BalanceObservation.class);
		when(provider.lookup(accountId)).thenReturn(new CardBalanceProviderResult.Success(balance));
		when(observations.recordSuccess(
				accountId, BalanceLookupMethod.USER_REQUESTED, balance, NOW)).thenReturn(persisted);

		CardBalanceSyncResult result = service.refresh(
				accountId, BalanceLookupMethod.USER_REQUESTED);

		assertThat(result).isEqualTo(new CardBalanceSyncResult.Success(persisted));
		InOrder order = inOrder(provider, observations);
		order.verify(provider).lookup(accountId);
		order.verify(observations).recordSuccess(
				accountId, BalanceLookupMethod.USER_REQUESTED, balance, NOW);
		verify(observations, never()).recordFailure(any(), any(), any(), any());
	}

	@Test
	void persistsAStableFailureObservationWithoutRecordingSuccess() {
		UUID accountId = UUID.randomUUID();
		BalanceObservation persisted = BalanceObservation.failed(
				accountId, BalanceLookupMethod.AUTO_DAILY,
				CardBalanceSyncService.FAILURE_CODE, NOW);
		when(provider.lookup(accountId)).thenReturn(CardBalanceProviderResult.failure());
		when(observations.recordFailure(
				accountId, BalanceLookupMethod.AUTO_DAILY,
				CardBalanceSyncService.FAILURE_CODE, NOW)).thenReturn(persisted);

		CardBalanceSyncResult result = service.refresh(accountId, BalanceLookupMethod.AUTO_DAILY);

		assertThat(result).isEqualTo(new CardBalanceSyncResult.Failure(persisted));
		verify(observations).recordFailure(
				accountId, BalanceLookupMethod.AUTO_DAILY,
				CardBalanceSyncService.FAILURE_CODE, NOW);
		verify(observations, never()).recordSuccess(any(), any(), any(), any());
	}

	@Test
	void preDepositReturnsAProofOnlyForACommittedSuccess() {
		UUID accountId = UUID.randomUUID();
		UUID observationId = UUID.randomUUID();
		BalanceObservation persisted = mock(BalanceObservation.class);
		when(persisted.id()).thenReturn(observationId);
		when(persisted.accountLookupVersion()).thenReturn(7L);
		CardBalanceSyncService sync = mock(CardBalanceSyncService.class);
		PreDepositBalanceService preDeposit = new PreDepositBalanceService(sync);
		when(sync.refresh(accountId, BalanceLookupMethod.PRE_DEPOSIT))
				.thenReturn(new CardBalanceSyncResult.Success(persisted));

		assertThat(preDeposit.prepare(accountId))
				.isEqualTo(new DepositBalanceProof(observationId, 7));

		BalanceObservation failure = BalanceObservation.failed(
				accountId, BalanceLookupMethod.PRE_DEPOSIT,
				CardBalanceSyncService.FAILURE_CODE, NOW);
		when(sync.refresh(accountId, BalanceLookupMethod.PRE_DEPOSIT))
				.thenReturn(new CardBalanceSyncResult.Failure(failure));
		assertThatThrownBy(() -> preDeposit.prepare(accountId))
				.isInstanceOf(CardBalanceSyncFailedException.class)
				.hasMessageContaining(accountId.toString());
	}
}
