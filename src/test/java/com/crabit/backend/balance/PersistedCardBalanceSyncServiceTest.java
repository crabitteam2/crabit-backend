package com.crabit.backend.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.crabit.backend.account.Academy;
import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.Student;
import com.crabit.backend.wish.BalanceAdjustmentCase;
import com.crabit.backend.wish.BalanceAdjustmentCaseRepository;
import com.crabit.backend.wish.BalanceAdjustmentEventRole;
import com.crabit.backend.wish.BalanceLookupMethod;
import com.crabit.backend.wish.BalanceObservation;
import com.crabit.backend.wish.BalanceObservationRepository;
import com.crabit.backend.wish.BalanceObservationStatus;
import com.crabit.backend.wish.CardBalanceObservationService;
import com.crabit.backend.wish.KrwAmount;
import com.crabit.backend.wish.LedgerEvent;
import com.crabit.backend.wish.LedgerEventRepository;
import com.crabit.backend.wish.Wish;
import com.crabit.backend.wish.WishState;
import com.crabit.backend.wish.WishVisibility;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@Import(CardBalanceObservationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PersistedCardBalanceSyncServiceTest {

	private static final Instant FIXED_TIME = Instant.parse("2026-08-16T00:00:00Z");

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private CardBalanceObservationService observationService;

	@Autowired
	private BalanceObservationRepository observationRepository;

	@Autowired
	private LedgerEventRepository eventRepository;

	@Autowired
	private BalanceAdjustmentCaseRepository adjustmentRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void firstSuccessfulShortageAndRepeatBothSucceedWithOneCaseAndNotification() {
		UUID accountId = persistAccount();
		persistActiveWish(accountId, 80);
		CardBalanceSyncService syncService = new CardBalanceSyncService(
				ignored -> new CardBalanceProviderResult.Success(KrwAmount.nonNegative(50)),
				observationService,
				Clock.fixed(FIXED_TIME, ZoneOffset.UTC));

		CardBalanceSyncResult firstResult = syncService.refresh(
				accountId, BalanceLookupMethod.USER_REQUESTED);
		CardBalanceSyncResult repeatedResult = syncService.refresh(
				accountId, BalanceLookupMethod.USER_REQUESTED);

		assertThat(firstResult).isInstanceOf(CardBalanceSyncResult.Success.class);
		assertThat(repeatedResult).isInstanceOf(CardBalanceSyncResult.Success.class);
		List<BalanceObservation> observations = observations(accountId);
		assertThat(observations).hasSize(2);
		assertThat(observations.getFirst()).satisfies(observation -> {
			assertThat(observation.isFirstConnection()).isTrue();
			assertThat(observation.actualCardBalance()).isEqualTo(KrwAmount.nonNegative(50));
			assertThat(observation.balanceChangeEventDelta()).isEqualTo(KrwAmount.positive(50));
		});
		assertThat(observations.getLast().balanceChangeEventId()).isNull();
		requiredTransaction().executeWithoutResult(status -> {
			List<BalanceAdjustmentCase> adjustments = adjustmentRepository.findAll().stream()
					.filter(candidate -> accountId.equals(candidate.accountId()))
					.toList();
			assertThat(adjustments).singleElement().satisfies(adjustment -> {
				assertThat(adjustment.isOpen()).isTrue();
				assertThat(adjustment.openingBalanceObservationId())
						.isEqualTo(observations.getFirst().id());
				assertThat(adjustment.openingEventId()).isNull();
				assertThat(adjustment.eventLinks()).isEmpty();
			});
			assertThat(entityManager.createQuery(
					"select count(outbox) from MismatchNotificationOutbox outbox where outbox.adjustmentCase.accountId = :accountId",
					Long.class)
					.setParameter("accountId", accountId)
					.getSingleResult()).isOne();
		});
	}

	@Test
	void concurrentFirstShortageRefreshesCreateOneCaseAndOneNotification() throws Exception {
		UUID accountId = persistAccount();
		persistActiveWish(accountId, 80);
		CountDownLatch bothLookupsStarted = new CountDownLatch(2);
		CardBalanceProvider provider = ignored -> {
			bothLookupsStarted.countDown();
			await(bothLookupsStarted, "both concurrent balance lookups");
			return new CardBalanceProviderResult.Success(KrwAmount.nonNegative(50));
		};
		CardBalanceSyncService syncService = new CardBalanceSyncService(
				provider, observationService, Clock.fixed(FIXED_TIME, ZoneOffset.UTC));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CardBalanceSyncResult> first = executor.submit(
					() -> syncService.refresh(accountId, BalanceLookupMethod.USER_REQUESTED));
			Future<CardBalanceSyncResult> second = executor.submit(
					() -> syncService.refresh(accountId, BalanceLookupMethod.USER_REQUESTED));

			assertThat(first.get(10, TimeUnit.SECONDS))
					.isInstanceOf(CardBalanceSyncResult.Success.class);
			assertThat(second.get(10, TimeUnit.SECONDS))
					.isInstanceOf(CardBalanceSyncResult.Success.class);
		} finally {
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(observations(accountId)).hasSize(2);
		requiredTransaction().executeWithoutResult(status -> {
			assertThat(adjustmentRepository.findAll().stream()
					.filter(candidate -> accountId.equals(candidate.accountId())))
					.hasSize(1);
			assertThat(entityManager.createQuery(
					"select count(outbox) from MismatchNotificationOutbox outbox where outbox.adjustmentCase.accountId = :accountId",
					Long.class)
					.setParameter("accountId", accountId)
					.getSingleResult()).isOne();
		});
	}

	@Test
	void persistsTheCompleteFixedClockScriptAcrossRecreatedSyncServices() {
		UUID accountId = persistAccount();
		DeterministicCardBalanceAdapter adapter = new DeterministicCardBalanceAdapter();
		CardBalanceSyncService firstService = new CardBalanceSyncService(
				adapter, observationService, Clock.fixed(FIXED_TIME, ZoneOffset.UTC));
		adapter.enqueueSuccess(accountId, KrwAmount.nonNegative(100));
		adapter.enqueueSuccess(accountId, KrwAmount.nonNegative(100));
		adapter.enqueueFailure(accountId);
		adapter.enqueueSuccess(accountId, KrwAmount.nonNegative(130));
		adapter.enqueueSuccess(accountId, KrwAmount.nonNegative(80));

		List<CardBalanceSyncResult> results = List.of(
				firstService.refresh(accountId, BalanceLookupMethod.USER_REQUESTED),
				firstService.refresh(accountId, BalanceLookupMethod.USER_REQUESTED),
				newSyncService(adapter).refresh(accountId, BalanceLookupMethod.USER_REQUESTED),
				newSyncService(adapter).refresh(accountId, BalanceLookupMethod.USER_REQUESTED),
				newSyncService(adapter).refresh(accountId, BalanceLookupMethod.USER_REQUESTED));

		assertThat(results).extracting(result -> result instanceof CardBalanceSyncResult.Success)
				.containsExactly(true, true, false, true, true);
		List<BalanceObservation> observations = observations(accountId);
		assertThat(observations).extracting(BalanceObservation::accountLookupVersion)
				.containsExactly(1L, 2L, 3L, 4L, 5L);
		assertThat(observations).extracting(BalanceObservation::observedAt)
				.containsOnly(FIXED_TIME);
		assertThat(observations).extracting(BalanceObservation::status)
				.containsExactly(
						BalanceObservationStatus.SUCCEEDED,
						BalanceObservationStatus.SUCCEEDED,
						BalanceObservationStatus.FAILED,
						BalanceObservationStatus.SUCCEEDED,
						BalanceObservationStatus.SUCCEEDED);
		assertThat(observations).extracting(BalanceObservation::actualCardBalance)
				.containsExactly(
						KrwAmount.nonNegative(100),
						KrwAmount.nonNegative(100),
						null,
						KrwAmount.nonNegative(130),
						KrwAmount.nonNegative(80));
		assertThat(observations).extracting(BalanceObservation::previousSuccessfulObservationId)
				.containsExactly(
						null,
						observations.get(0).id(),
						null,
						observations.get(1).id(),
						observations.get(3).id());
		assertThat(observations).extracting(BalanceObservation::balanceChangeEventDelta)
				.containsExactly(
						KrwAmount.of(100), null, null, KrwAmount.of(30), KrwAmount.of(-50));
		assertThat(observations.get(2).failureCode())
				.isEqualTo(CardBalanceSyncService.FAILURE_CODE);
		assertThat(events(accountId)).extracting(LedgerEvent::accountDelta)
				.containsExactlyInAnyOrder(KrwAmount.of(100), KrwAmount.of(30), KrwAmount.of(-50));
	}

	@Test
	void persistsCompletionInversionAcrossIndependentSyncServiceInstances()
			throws Exception {
		UUID accountId = persistAccount();
		BlockingProvider earlierProvider = new BlockingProvider(KrwAmount.nonNegative(100));
		CardBalanceProvider laterProvider = ignored ->
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(200));
		CardBalanceSyncService earlierService = new CardBalanceSyncService(
				earlierProvider, observationService, Clock.fixed(FIXED_TIME, ZoneOffset.UTC));
		CardBalanceSyncService laterService = new CardBalanceSyncService(
				laterProvider, observationService,
				Clock.fixed(FIXED_TIME.plusSeconds(1), ZoneOffset.UTC));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CardBalanceSyncResult> earlierRefresh = executor.submit(
					() -> earlierService.refresh(accountId, BalanceLookupMethod.USER_REQUESTED));
			assertThat(earlierProvider.awaitLookup()).isTrue();
			Future<CardBalanceSyncResult> laterRefresh = executor.submit(
					() -> laterService.refresh(accountId, BalanceLookupMethod.USER_REQUESTED));

			assertThat(laterRefresh.get(10, TimeUnit.SECONDS))
					.isInstanceOf(CardBalanceSyncResult.Success.class);
			earlierProvider.releaseLookup();
			assertThat(earlierRefresh.get(10, TimeUnit.SECONDS))
					.isInstanceOf(CardBalanceSyncResult.Success.class);
		} finally {
			earlierProvider.releaseLookup();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		List<BalanceObservation> observations = observations(accountId);
		assertThat(observations).extracting(BalanceObservation::accountLookupVersion)
				.containsExactly(1L, 2L);
		assertThat(observations).extracting(BalanceObservation::actualCardBalance)
				.containsExactly(KrwAmount.nonNegative(200), KrwAmount.nonNegative(100));
		assertThat(observations).extracting(BalanceObservation::observedAt)
				.containsExactly(FIXED_TIME.plusSeconds(1), FIXED_TIME);
		assertThat(observations).extracting(BalanceObservation::previousSuccessfulObservationId)
				.containsExactly(null, observations.get(0).id());
		assertThat(observations).extracting(BalanceObservation::balanceChangeEventDelta)
				.containsExactly(KrwAmount.of(200), KrwAmount.of(-100));
		assertThat(observationRepository
				.findFirstByAccountIdAndStatusAndAccountLookupVersionIsNotNullOrderByAccountLookupVersionDesc(
						accountId, BalanceObservationStatus.SUCCEEDED)
				.map(BalanceObservation::id))
				.contains(observations.get(1).id());
	}

	@Test
	void persistsVersionOrderedCompletionInversionThroughMismatchResolution()
			throws Exception {
		UUID accountId = persistAccount();
		CardBalanceSyncService baselineService = new CardBalanceSyncService(
				ignored -> new CardBalanceProviderResult.Success(KrwAmount.nonNegative(200)),
				observationService,
				Clock.fixed(FIXED_TIME.minusSeconds(1), ZoneOffset.UTC));
		assertThat(baselineService.refresh(accountId, BalanceLookupMethod.USER_REQUESTED))
				.isInstanceOf(CardBalanceSyncResult.Success.class);
		persistActiveWish(accountId, 150);

		BlockingProvider earlierProvider = new BlockingProvider(KrwAmount.nonNegative(200));
		CardBalanceProvider laterProvider = ignored ->
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(100));
		CardBalanceSyncService earlierService = new CardBalanceSyncService(
				earlierProvider, observationService, Clock.fixed(FIXED_TIME, ZoneOffset.UTC));
		CardBalanceSyncService laterService = new CardBalanceSyncService(
				laterProvider, observationService,
				Clock.fixed(FIXED_TIME.plusSeconds(1), ZoneOffset.UTC));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CardBalanceSyncResult> earlierRefresh = executor.submit(
					() -> earlierService.refresh(accountId, BalanceLookupMethod.USER_REQUESTED));
			assertThat(earlierProvider.awaitLookup()).isTrue();
			Future<CardBalanceSyncResult> laterRefresh = executor.submit(
					() -> laterService.refresh(accountId, BalanceLookupMethod.USER_REQUESTED));

			assertThat(laterRefresh.get(10, TimeUnit.SECONDS))
					.isInstanceOf(CardBalanceSyncResult.Success.class);
			earlierProvider.releaseLookup();
			assertThat(earlierRefresh.get(10, TimeUnit.SECONDS))
					.isInstanceOf(CardBalanceSyncResult.Success.class);
		} finally {
			earlierProvider.releaseLookup();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		List<BalanceObservation> observations = observations(accountId);
		assertThat(observations).extracting(BalanceObservation::accountLookupVersion)
				.containsExactly(1L, 2L, 3L);
		assertThat(observations).extracting(BalanceObservation::actualCardBalance)
				.containsExactly(
						KrwAmount.nonNegative(200),
						KrwAmount.nonNegative(100),
						KrwAmount.nonNegative(200));
		assertThat(observations).extracting(BalanceObservation::observedAt)
				.containsExactly(
						FIXED_TIME.minusSeconds(1),
						FIXED_TIME.plusSeconds(1),
						FIXED_TIME);
		assertThat(observations).extracting(BalanceObservation::previousSuccessfulObservationId)
				.containsExactly(null, observations.get(0).id(), observations.get(1).id());
		assertThat(observations).extracting(BalanceObservation::balanceChangeEventDelta)
				.containsExactly(KrwAmount.of(200), KrwAmount.of(-100), KrwAmount.of(100));
		assertThat(events(accountId))
				.extracting(LedgerEvent::accountDelta, LedgerEvent::occurredAt)
				.containsExactlyInAnyOrder(
						tuple(KrwAmount.of(200), FIXED_TIME.minusSeconds(1)),
						tuple(KrwAmount.of(-100), FIXED_TIME.plusSeconds(1)),
						tuple(KrwAmount.of(100), FIXED_TIME));

		requiredTransaction().executeWithoutResult(status -> {
			List<BalanceAdjustmentCase> adjustments = adjustmentRepository.findAll().stream()
					.filter(candidate -> accountId.equals(candidate.accountId()))
					.toList();
			assertThat(adjustments).hasSize(1);
			BalanceAdjustmentCase adjustment = adjustments.getFirst();
			assertThat(adjustment.isOpen()).isFalse();
			assertThat(adjustment.openedAt()).isEqualTo(FIXED_TIME.plusSeconds(1));
			assertThat(adjustment.resolvedAt()).isEqualTo(FIXED_TIME.plusSeconds(1));
			assertThat(adjustment.eventLinks())
					.extracting(link -> link.role())
					.containsExactly(
							BalanceAdjustmentEventRole.OPENING_DECREASE,
							BalanceAdjustmentEventRole.RESOLUTION);
			assertThat(adjustment.ledgerEvents()).extracting(LedgerEvent::id)
					.containsExactly(
							observations.get(1).balanceChangeEventId(),
							observations.get(2).balanceChangeEventId());
		});
	}

	private CardBalanceSyncService newSyncService(CardBalanceProvider provider) {
		return new CardBalanceSyncService(
				provider, observationService, Clock.fixed(FIXED_TIME, ZoneOffset.UTC));
	}

	private UUID persistAccount() {
		UUID academyId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		CardBalanceAccount account = CardBalanceAccount.open(studentId, academyId, FIXED_TIME);
		requiredTransaction().executeWithoutResult(status -> {
			entityManager.persist(new Academy(academyId, "Balance Sync Academy"));
			entityManager.persist(new Student(studentId, "Balance Sync Student"));
			entityManager.persist(account);
		});
		return account.id();
	}

	private void persistActiveWish(UUID accountId, long amount) {
		requiredTransaction().executeWithoutResult(status -> {
			CardBalanceAccount account = entityManager.find(CardBalanceAccount.class, accountId);
			entityManager.persist(Wish.reconstitute(
					UUID.randomUUID(),
					account.id(),
					account.academyId(),
					"Mismatch Wish",
					KrwAmount.positive(amount + 50),
					KrwAmount.positive(amount),
					WishState.IN_PROGRESS,
					WishVisibility.PRIVATE,
					null,
					FIXED_TIME.minusSeconds(10),
					null,
					null,
					null));
		});
	}

	private List<BalanceObservation> observations(UUID accountId) {
		return observationRepository.findAll().stream()
				.filter(observation -> accountId.equals(observation.accountId()))
				.sorted((left, right) -> Long.compare(
						left.accountLookupVersion(), right.accountLookupVersion()))
				.toList();
	}

	private List<LedgerEvent> events(UUID accountId) {
		return eventRepository.findAll().stream()
				.filter(event -> accountId.equals(event.accountId()))
				.toList();
	}

	private TransactionTemplate requiredTransaction() {
		return new TransactionTemplate(transactionManager);
	}

	private static void await(CountDownLatch latch, String target) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for " + target);
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for " + target, interrupted);
		}
	}

	private static final class BlockingProvider implements CardBalanceProvider {

		private final KrwAmount balance;
		private final AtomicInteger invocation = new AtomicInteger();
		private final CountDownLatch lookupEntered = new CountDownLatch(1);
		private final CountDownLatch releaseLookup = new CountDownLatch(1);

		private BlockingProvider(KrwAmount balance) {
			this.balance = balance;
		}

		@Override
		public CardBalanceProviderResult lookup(UUID accountId) {
			if (invocation.incrementAndGet() != 1) {
				throw new IllegalStateException("Blocking provider must be invoked exactly once");
			}
			lookupEntered.countDown();
			await(releaseLookup);
			return new CardBalanceProviderResult.Success(balance);
		}

		boolean awaitLookup() throws InterruptedException {
			return lookupEntered.await(10, TimeUnit.SECONDS);
		}

		void releaseLookup() {
			releaseLookup.countDown();
		}

		private static void await(CountDownLatch latch) {
			PersistedCardBalanceSyncServiceTest.await(latch, "release of earlier lookup");
		}
	}

}
