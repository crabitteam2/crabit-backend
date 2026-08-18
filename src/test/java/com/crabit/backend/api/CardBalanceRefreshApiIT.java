package com.crabit.backend.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.balance.CardBalanceSyncResult;
import com.crabit.backend.balance.CardBalanceSyncService;
import com.crabit.backend.e2e.SeedBearerAuthenticationFilter;
import com.crabit.backend.e2e.SeedFixtureCatalog;
import com.crabit.backend.e2e.SeedTokenRegistry;
import com.crabit.backend.wish.BalanceAdjustmentPolicy;
import com.crabit.backend.wish.BalanceLookupMethod;
import com.crabit.backend.wish.BalanceObservation;
import com.crabit.backend.wish.BalanceObservationRepository;
import com.crabit.backend.wish.KrwAmount;
import com.crabit.backend.wish.Wish;
import com.crabit.backend.wish.WishRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CardBalanceRefreshApiIT {

	private static final UUID ACCOUNT_ID = SeedFixtureCatalog.OWNER_ACCOUNT_ID;
	private static final Instant OBSERVED_AT = Instant.parse("2026-08-17T01:02:03Z");

	private CardBalanceAccountRepository accounts;
	private CardBalanceSyncService sync;
	private WishRepository wishes;
	private BalanceAdjustmentPolicy adjustmentPolicy;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		accounts = mock(CardBalanceAccountRepository.class);
		sync = mock(CardBalanceSyncService.class);
		wishes = mock(WishRepository.class);
		BalanceObservationRepository observations = mock(BalanceObservationRepository.class);
		adjustmentPolicy = mock(BalanceAdjustmentPolicy.class);
		CardBalanceAccountProjectionService projections =
				new CardBalanceAccountProjectionService(
						accounts, observations, wishes, adjustmentPolicy);
		CardBalanceRefreshController controller = new CardBalanceRefreshController(
				accounts, sync, projections);
		SeedBearerAuthenticationFilter filter = new SeedBearerAuthenticationFilter(
				new SeedTokenRegistry(new SeedFixtureCatalog()));
		mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build();
	}

	@Test
	void refreshesAnOwnedAccountBodylesslyAsUserRequestedAndReturnsTheApprovedShape()
			throws Exception {
		CardBalanceAccount account = ownedAccount();
		BalanceObservation observation = mock(BalanceObservation.class);
		UUID observationId = UUID.randomUUID();
		when(observation.id()).thenReturn(observationId);
		when(observation.lookupMethod()).thenReturn(BalanceLookupMethod.USER_REQUESTED);
		when(observation.actualCardBalance()).thenReturn(KrwAmount.nonNegative(1_000_000));
		when(observation.observedAt()).thenReturn(OBSERVED_AT);
		when(accounts.findByIdAndStudentId(ACCOUNT_ID, SeedFixtureCatalog.OWNER_ID))
				.thenReturn(Optional.of(account));
		when(wishes.findByAccountIdAndDeletedAtIsNullAndStateIn(
				org.mockito.ArgumentMatchers.eq(ACCOUNT_ID), org.mockito.ArgumentMatchers.anyCollection()))
				.thenReturn(List.of());
		when(sync.refresh(ACCOUNT_ID, BalanceLookupMethod.USER_REQUESTED))
				.thenReturn(new CardBalanceSyncResult.Success(observation));

		mvc.perform(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", ACCOUNT_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + SeedFixtureCatalog.OWNER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.observationId").value(observationId.toString()))
				.andExpect(jsonPath("$.lookupMethod").value("USER_REQUESTED"))
				.andExpect(jsonPath("$.observedAt").value("2026-08-17T01:02:03Z"))
				.andExpect(jsonPath("$.account.cardBalanceAccountId").value(ACCOUNT_ID.toString()))
				.andExpect(jsonPath("$.account.academyId").value(account.academyId().toString()))
				.andExpect(jsonPath("$.account.balanceKnowledge").value("KNOWN"))
				.andExpect(jsonPath("$.account.actualCardBalance").value(1_000_000))
				.andExpect(jsonPath("$.account.ledgerAvailableBalance").value(1_000_000))
				.andExpect(jsonPath("$.account.displayAvailableBalance").value(1_000_000))
				.andExpect(jsonPath("$.account.unresolvedShortage").value(0))
				.andExpect(jsonPath("$.account.balanceAdjustmentInProgress").value(false))
				.andExpect(jsonPath("$.account.lastRefreshStatus").value("SUCCESS"))
				.andExpect(jsonPath("$.account.lastRefreshedAt").value("2026-08-17T01:02:03Z"));
	}

	@Test
	void returnsTheExactShortageProjectionForASuccessfulFirstBalanceRefresh()
			throws Exception {
		CardBalanceAccount account = ownedAccount();
		BalanceObservation observation = mock(BalanceObservation.class);
		Wish activeWish = mock(Wish.class);
		UUID observationId = UUID.randomUUID();
		when(observation.id()).thenReturn(observationId);
		when(observation.lookupMethod()).thenReturn(BalanceLookupMethod.USER_REQUESTED);
		when(observation.actualCardBalance()).thenReturn(KrwAmount.nonNegative(50));
		when(observation.observedAt()).thenReturn(OBSERVED_AT);
		when(activeWish.amount()).thenReturn(KrwAmount.positive(80));
		when(accounts.findByIdAndStudentId(ACCOUNT_ID, SeedFixtureCatalog.OWNER_ID))
				.thenReturn(Optional.of(account));
		when(wishes.findByAccountIdAndDeletedAtIsNullAndStateIn(
				org.mockito.ArgumentMatchers.eq(ACCOUNT_ID), org.mockito.ArgumentMatchers.anyCollection()))
				.thenReturn(List.of(activeWish));
		when(sync.refresh(ACCOUNT_ID, BalanceLookupMethod.USER_REQUESTED))
				.thenReturn(new CardBalanceSyncResult.Success(observation));
		when(adjustmentPolicy.isOpen(ACCOUNT_ID)).thenReturn(true);

		mvc.perform(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", ACCOUNT_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + SeedFixtureCatalog.OWNER_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.observationId").value(observationId.toString()))
				.andExpect(jsonPath("$.lookupMethod").value("USER_REQUESTED"))
				.andExpect(jsonPath("$.observedAt").value("2026-08-17T01:02:03Z"))
				.andExpect(jsonPath("$.account.actualCardBalance").value(50))
				.andExpect(jsonPath("$.account.ledgerAvailableBalance").value(-30))
				.andExpect(jsonPath("$.account.displayAvailableBalance").value(0))
				.andExpect(jsonPath("$.account.unresolvedShortage").value(30))
				.andExpect(jsonPath("$.account.balanceAdjustmentInProgress").value(true))
				.andExpect(jsonPath("$.account.lastRefreshStatus").value("SUCCESS"));
	}

	@Test
	void returnsRetryable503OnlyAfterTheSyncServiceReturnsAPersistedFailure() throws Exception {
		BalanceObservation failure = BalanceObservation.failed(
				ACCOUNT_ID, BalanceLookupMethod.USER_REQUESTED,
				CardBalanceSyncService.FAILURE_CODE, OBSERVED_AT);
		when(accounts.findByIdAndStudentId(ACCOUNT_ID, SeedFixtureCatalog.OWNER_ID))
				.thenReturn(Optional.of(ownedAccount()));
		when(sync.refresh(ACCOUNT_ID, BalanceLookupMethod.USER_REQUESTED))
				.thenReturn(new CardBalanceSyncResult.Failure(failure));

		mvc.perform(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", ACCOUNT_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + SeedFixtureCatalog.OWNER_TOKEN))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("BALANCE_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.message").value("Card balance could not be refreshed."))
				.andExpect(jsonPath("$.error.retryable").value(true))
				.andExpect(jsonPath("$.error.fieldErrors").isEmpty())
				.andExpect(jsonPath("$.error.details").isMap());
	}

	@Test
	void hidesNonOwnedAccountsAndPreservesAuthenticationErrors() throws Exception {
		when(accounts.findByIdAndStudentId(ACCOUNT_ID, SeedFixtureCatalog.FRIEND_ID))
				.thenReturn(Optional.empty());

		mvc.perform(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", ACCOUNT_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + SeedFixtureCatalog.FRIEND_TOKEN))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));
		verifyNoInteractions(sync);

		mvc.perform(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", ACCOUNT_ID))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));

		mvc.perform(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", ACCOUNT_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + SeedFixtureCatalog.STAFF_TOKEN))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	private static CardBalanceAccount ownedAccount() {
		return CardBalanceAccount.reconstitute(
				ACCOUNT_ID,
				SeedFixtureCatalog.OWNER_ID,
				SeedFixtureCatalog.PRIMARY_ACADEMY_ID,
				SeedFixtureCatalog.FIXTURE_TIME,
				null);
	}
}
