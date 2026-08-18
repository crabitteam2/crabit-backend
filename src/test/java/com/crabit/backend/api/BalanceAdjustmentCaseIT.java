package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.e2e.SeedFixtureCatalog;
import com.crabit.backend.wish.BalanceAdjustmentPolicy;
import com.crabit.backend.wish.BalanceLookupMethod;
import com.crabit.backend.wish.BalanceObservationRepository;
import com.crabit.backend.wish.CardBalanceObservationService;
import com.crabit.backend.wish.KrwAmount;
import com.crabit.backend.wish.WishLifecycleService;
import com.crabit.backend.wish.WishRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BalanceAdjustmentCaseIT extends WishApiIntegrationSupport {

	@Autowired
	private CardBalanceAccountRepository accounts;

	@Autowired
	private BalanceObservationRepository observations;

	@Autowired
	private WishRepository wishes;

	@Autowired
	private BalanceAdjustmentPolicy adjustmentPolicy;

	@Autowired
	private CardBalanceObservationService observationService;

	@Autowired
	private WishLifecycleService wishLifecycle;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void keepsOneCaseThroughPartialAndOverResolutionThenCreatesANewCaseOnRecurrence()
			throws Exception {
		refreshTo(700_000)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.account.unresolvedShortage").value(50_000))
				.andExpect(jsonPath("$.account.balanceAdjustmentInProgress").value(true));

		withdraw("partial-resolution", 20_000, 0)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(230_000))
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(true));
		assertThat(openCaseCount()).isOne();

		withdraw("over-resolution", 40_000, 1)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(190_000))
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(false));
		assertThat(openCaseCount()).isZero();

		refreshTo(680_000)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.account.unresolvedShortage").value(10_000))
				.andExpect(jsonPath("$.account.balanceAdjustmentInProgress").value(true));

		assertThat(jdbc.queryForList("""
				SELECT status FROM balance_adjustment_case
				WHERE account_id = ?
				""", String.class, OWNER_ACCOUNT_ID))
				.containsExactlyInAnyOrder("RESOLVED", "OPEN");
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM mismatch_notification_outbox outbox
				JOIN balance_adjustment_case adjustment ON adjustment.id = outbox.adjustment_case_id
				WHERE adjustment.account_id = ?
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(2L);
	}

	@Test
	void laterExternalBalanceIncreaseNaturallyResolvesTheCurrentCase() throws Exception {
		refreshTo(700_000).andExpect(status().isOk());
		refreshTo(800_000)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.account.ledgerAvailableBalance").value(50_000))
				.andExpect(jsonPath("$.account.unresolvedShortage").value(0))
				.andExpect(jsonPath("$.account.balanceAdjustmentInProgress").value(false));

		assertThat(openCaseCount()).isZero();
		assertThat(jdbc.queryForMap("""
				SELECT status, resolution_event_id FROM balance_adjustment_case
				WHERE account_id = ?
				""", OWNER_ACCOUNT_ID))
				.containsEntry("status", "RESOLVED")
				.doesNotContainValue(null);
	}

	@Test
	void completionReturnsAllFundsAndResolvesInTheSameAtomicCommand() throws Exception {
		refreshTo(600_000).andExpect(status().isOk());

		asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "complete-resolves")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("COMPLETED"))
				.andExpect(jsonPath("$.wish.amount").value(0))
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(false));

		assertThat(openCaseCount()).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM balance_adjustment_case adjustment
				JOIN ledger_event event ON event.id = adjustment.resolution_event_id
				WHERE adjustment.account_id = ?
				  AND adjustment.status = 'RESOLVED'
				  AND event.event_type = 'WISH_COMPLETION_RETURN'
				""", Long.class, OWNER_ACCOUNT_ID)).isOne();
	}

	@Test
	void accountProjectionSerializesRefreshResolutionWithItsBalanceSnapshot() throws Exception {
		refreshTo(700_000).andExpect(status().isOk());
		CountDownLatch latestSuccessRead = new CountDownLatch(1);
		CountDownLatch releaseProjection = new CountDownLatch(1);
		BalanceObservationRepository gatedObservations = gatedLatestSuccessRepository(
				latestSuccessRead, releaseProjection);
		CardBalanceAccountProjectionService projections =
				new CardBalanceAccountProjectionService(
						accounts, gatedObservations, wishes, adjustmentPolicy);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CardBalanceAccountProjectionService.CardBalanceAccountPage> projected =
					executor.submit(() -> transaction.execute(status -> projections.listOwned(
							SeedFixtureCatalog.OWNER_ID,
							SeedFixtureCatalog.PRIMARY_ACADEMY_ID)));
			assertThat(latestSuccessRead.await(5, TimeUnit.SECONDS)).isTrue();

			Future<RefreshAttempt> concurrentRefresh = executor.submit(() -> {
				try {
					transaction.executeWithoutResult(status -> {
						jdbc.execute("SET LOCAL lock_timeout = '500ms'");
						observationService.recordSuccess(
								OWNER_ACCOUNT_ID,
								BalanceLookupMethod.USER_REQUESTED,
								KrwAmount.nonNegative(800_000),
								COMMAND_TIME.plusSeconds(1));
					});
					return new RefreshAttempt(true, null);
				} catch (RuntimeException failure) {
					return new RefreshAttempt(false, mostSpecificMessage(failure));
				}
			});
			RefreshAttempt refresh = concurrentRefresh.get(10, TimeUnit.SECONDS);
			releaseProjection.countDown();

			CardBalanceAccountProjectionService.CardBalanceAccountPage page =
					projected.get(10, TimeUnit.SECONDS);
			CardBalanceAccountProjectionService.KnownCardBalanceAccount account =
					(CardBalanceAccountProjectionService.KnownCardBalanceAccount)
							page.items().getFirst();
			assertThat(refresh.committed()).isFalse();
			assertThat(refresh.failure()).contains("lock timeout");
			assertThat(account.actualCardBalance()).isEqualTo(700_000);
			assertThat(account.ledgerAvailableBalance()).isEqualTo(-50_000);
			assertThat(account.unresolvedShortage()).isEqualTo(50_000);
			assertThat(account.balanceAdjustmentInProgress()).isTrue();
		} finally {
			releaseProjection.countDown();
			executor.shutdownNow();
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void wishProjectionWaitsForConcurrentRefreshResolutionBeforeReadingItsFlag()
			throws Exception {
		refreshTo(700_000).andExpect(status().isOk());
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		CountDownLatch refreshReadyToCommit = new CountDownLatch(1);
		CountDownLatch releaseRefresh = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> refresh = executor.submit(() -> transaction.executeWithoutResult(status -> {
				observationService.recordSuccess(
						OWNER_ACCOUNT_ID,
						BalanceLookupMethod.USER_REQUESTED,
						KrwAmount.nonNegative(800_000),
						COMMAND_TIME.plusSeconds(1));
				refreshReadyToCommit.countDown();
				await(releaseRefresh);
			}));
			assertThat(refreshReadyToCommit.await(5, TimeUnit.SECONDS)).isTrue();

			Future<WishLifecycleService.WishPage> projected = executor.submit(() ->
					wishLifecycle.list(
							SeedFixtureCatalog.OWNER_ID,
							SeedFixtureCatalog.PRIMARY_ACADEMY_ID,
							OWNER_ACCOUNT_ID,
							null,
							20,
							java.util.Set.of()));
			boolean blockedOnAccountSnapshot;
			try {
				projected.get(500, TimeUnit.MILLISECONDS);
				blockedOnAccountSnapshot = false;
			} catch (TimeoutException expected) {
				blockedOnAccountSnapshot = true;
			}
			releaseRefresh.countDown();
			refresh.get(10, TimeUnit.SECONDS);
			WishLifecycleService.WishPage page = projected.get(10, TimeUnit.SECONDS);

			assertThat(blockedOnAccountSnapshot).isTrue();
			assertThat(page.items())
					.isNotEmpty()
					.allMatch(wish -> !wish.balanceAdjustmentInProgress());
		} finally {
			releaseRefresh.countDown();
			executor.shutdownNow();
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	private org.springframework.test.web.servlet.ResultActions refreshTo(long balance)
			throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":" + balance + "}]");
		return asOwner(post(
				"/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID));
	}

	private org.springframework.test.web.servlet.ResultActions withdraw(
			String key, long amount, long expectedVersion) throws Exception {
		return asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/withdrawals")
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"amount":%d,"expectedVersion":%d}
						""".formatted(amount, expectedVersion)));
	}

	private long openCaseCount() {
		return jdbc.queryForObject("""
				SELECT count(*) FROM balance_adjustment_case
				WHERE account_id = ? AND status = 'OPEN'
				""", Long.class, OWNER_ACCOUNT_ID);
	}

	private BalanceObservationRepository gatedLatestSuccessRepository(
			CountDownLatch latestSuccessRead,
			CountDownLatch releaseProjection) {
		return (BalanceObservationRepository) Proxy.newProxyInstance(
				BalanceObservationRepository.class.getClassLoader(),
				new Class<?>[] {BalanceObservationRepository.class},
				(proxy, method, arguments) -> {
					try {
						Object result = method.invoke(observations, arguments);
						if (method.getName().equals(
								"findFirstByAccountIdAndStatusAndAccountLookupVersionIsNotNullOrderByAccountLookupVersionDesc")) {
							latestSuccessRead.countDown();
							await(releaseProjection);
						}
						return result;
					} catch (InvocationTargetException reflectedFailure) {
						throw reflectedFailure.getCause();
					}
				});
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out coordinating projection concurrency");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Projection concurrency was interrupted", interrupted);
		}
	}

	private static String mostSpecificMessage(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current.getMessage();
	}

	private record RefreshAttempt(boolean committed, String failure) {
	}
}
