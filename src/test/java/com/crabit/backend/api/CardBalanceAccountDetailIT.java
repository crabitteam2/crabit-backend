package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.STAFF_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

class CardBalanceAccountDetailIT extends WishApiIntegrationSupport {

	private static final String DETAIL =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID;

	@Test
	void returnsTheOwnedUnknownProjectionWithoutFabricatingZeroBalances() throws Exception {
		asOwner(get(DETAIL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cardBalanceAccountId")
						.value(OWNER_ACCOUNT_ID.toString()))
				.andExpect(jsonPath("$.academyId")
						.value(com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID.toString()))
				.andExpect(jsonPath("$.balanceKnowledge").value("UNKNOWN"))
				.andExpect(jsonPath("$.actualCardBalance").value((Object) null))
				.andExpect(jsonPath("$.ledgerAvailableBalance").value((Object) null))
				.andExpect(jsonPath("$.displayAvailableBalance").value((Object) null))
				.andExpect(jsonPath("$.unresolvedShortage").value((Object) null))
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").value(false))
				.andExpect(jsonPath("$.lastRefreshStatus").value((Object) null))
				.andExpect(jsonPath("$.lastRefreshedAt").value((Object) null));
	}

	@Test
	void retainsTheLatestSuccessfulProjectionAfterALaterFailureAndReflectsTheOpenCase()
			throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":700000}]");
		asOwner(post(DETAIL + "/balance-refreshes"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.account.balanceAdjustmentInProgress").value(true));

		clock.set(COMMAND_TIME.plusSeconds(1));
		setBalanceScenario("[{\"type\":\"FAILURE\"}]");
		asOwner(post(DETAIL + "/balance-refreshes"))
				.andExpect(status().isServiceUnavailable());

		asOwner(get(DETAIL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceKnowledge").value("KNOWN"))
				.andExpect(jsonPath("$.actualCardBalance").value(700_000))
				.andExpect(jsonPath("$.ledgerAvailableBalance").value(-50_000))
				.andExpect(jsonPath("$.displayAvailableBalance").value(0))
				.andExpect(jsonPath("$.unresolvedShortage").value(50_000))
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").value(true))
				.andExpect(jsonPath("$.lastRefreshStatus").value("FAILED"))
				.andExpect(jsonPath("$.lastRefreshedAt").value(COMMAND_TIME.toString()));
	}

	@Test
	void collapsesForeignCrossAcademyClosedAndAbsentAccountsIntoTheSameNotFoundShape()
			throws Exception {
		List<HiddenError> hidden = new java.util.ArrayList<>();
		hidden.add(hiddenError(asToken(FRIEND_TOKEN, get(DETAIL)).andReturn()));
		hidden.add(hiddenError(asToken(OTHER_ACADEMY_TOKEN, get(DETAIL)).andReturn()));

		jdbc.update("UPDATE card_balance_account SET closed_at = ? WHERE id = ?",
				Timestamp.from(COMMAND_TIME), OWNER_ACCOUNT_ID);
		hidden.add(hiddenError(asOwner(get(DETAIL)).andReturn()));
		hidden.add(hiddenError(asOwner(get(
				"/v1/card-balance-accounts/" + UUID.randomUUID())).andReturn()));

		assertThat(hidden).allSatisfy(error -> {
			assertThat(error.status()).isEqualTo(404);
			assertThat(error.code()).isEqualTo("CARD_BALANCE_ACCOUNT_NOT_FOUND");
			assertThat(error.message()).isEqualTo("Card Balance Account not found.");
			assertThat(error.retryable()).isFalse();
			assertThat(error.fieldErrors()).isEmpty();
			assertThat(error.details()).isEmpty();
		});
	}

	@Test
	void requiresAKnownStudentBearerBeforeLookingUpTheAccount() throws Exception {
		mockMvc.perform(get(DETAIL))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));

		asToken(STAFF_TOKEN, get(DETAIL))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void readsWithoutConsumingTheProviderScenarioOrMutatingPersistentState() throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":1234567}]");
		PersistentState before = persistentState();

		asOwner(get(DETAIL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceKnowledge").value("UNKNOWN"));

		mockMvc.perform(get("/e2e/card-balance-accounts/{accountId}/balance-scenario",
				OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps.length()").value(1))
				.andExpect(jsonPath("$.steps[0].type").value("SUCCESS"))
				.andExpect(jsonPath("$.steps[0].balance").value(1_234_567));
		assertThat(persistentState()).isEqualTo(before);

		asOwner(post(DETAIL + "/balance-refreshes"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.account.actualCardBalance").value(1_234_567));
		mockMvc.perform(get("/e2e/card-balance-accounts/{accountId}/balance-scenario",
				OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps").isEmpty());
	}

	private PersistentState persistentState() {
		Map<String, Object> account = jdbc.queryForMap("""
				SELECT balance_lookup_version, version
				FROM card_balance_account
				WHERE id = ?
				""", OWNER_ACCOUNT_ID);
		return new PersistentState(
				count("balance_observation"),
				count("ledger_event"),
				count("balance_adjustment_case"),
				count("balance_adjustment_case_event"),
				((Number) account.get("balance_lookup_version")).longValue(),
				((Number) account.get("version")).longValue());
	}

	private long count(String table) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM " + table + " WHERE account_id = ?",
				Long.class, OWNER_ACCOUNT_ID);
	}

	@SuppressWarnings("unchecked")
	private static HiddenError hiddenError(MvcResult result) throws Exception {
		String body = result.getResponse().getContentAsString();
		return new HiddenError(
				result.getResponse().getStatus(),
				JsonPath.read(body, "$.error.code"),
				JsonPath.read(body, "$.error.message"),
				JsonPath.read(body, "$.error.retryable"),
				JsonPath.read(body, "$.error.fieldErrors"),
				JsonPath.read(body, "$.error.details"));
	}

	private record PersistentState(
			long observationCount,
			long ledgerEventCount,
			long adjustmentCaseCount,
			long adjustmentCaseEventCount,
			long balanceLookupVersion,
			long optimisticVersion) {
	}

	private record HiddenError(
			int status,
			String code,
			String message,
			boolean retryable,
			List<Object> fieldErrors,
			Map<String, Object> details) {
	}
}
