package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class WishFundMovementRollbackIT extends WishApiIntegrationSupport {

	private static final String DEPOSITS = WISHES_PATH + "/" + LAPTOP_WISH_ID + "/deposits";

	@Test
	void failedPreDepositLookupPersistsAuditAttemptButRollsBackMoneyLedgerAndIdempotency()
			throws Exception {
		setBalanceScenario("[{\"type\":\"FAILURE\"}]");

		asOwner(post(DEPOSITS)
				.header("Idempotency-Key", "deposit-provider-failure")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":10000,\"expectedVersion\":0}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("BALANCE_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.retryable").value(true));

		assertThat(jdbc.queryForObject(
				"SELECT wish_amount FROM wish WHERE id = ?", Long.class, LAPTOP_WISH_ID))
				.isEqualTo(250_000L);
		assertThat(jdbc.queryForObject(
				"SELECT balance_lookup_version FROM card_balance_account WHERE id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isOne();
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM balance_observation WHERE account_id = ? AND status = 'FAILED'",
				Long.class, OWNER_ACCOUNT_ID)).isOne();
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ? AND event_type = 'WISH_DEPOSIT'",
				Long.class, OWNER_ACCOUNT_ID)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT jsonb_exists(wish_idempotency_records, ?) FROM student WHERE id = "
						+ "(SELECT student_id FROM card_balance_account WHERE id = ?)",
				Boolean.class, "deposit-provider-failure", OWNER_ACCOUNT_ID)).isFalse();
	}

	@Test
	void mismatchObservationCommitsButRejectedAllocationLeavesWishAndDepositLedgerUnchanged()
			throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":800000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":700000}]");
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes",
				OWNER_ACCOUNT_ID)).andExpect(status().isOk());

		asOwner(post(DEPOSITS)
				.header("Idempotency-Key", "deposit-mismatch")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":10000,\"expectedVersion\":0}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BALANCE_MISMATCH_LOCKED"))
				.andExpect(jsonPath("$.error.details.adjustmentStatus").value("OPEN"));

		assertThat(jdbc.queryForObject(
				"SELECT wish_amount FROM wish WHERE id = ?", Long.class, LAPTOP_WISH_ID))
				.isEqualTo(250_000L);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM balance_observation WHERE account_id = ? AND status = 'SUCCEEDED'",
				Long.class, OWNER_ACCOUNT_ID)).isEqualTo(2L);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM balance_adjustment_case WHERE account_id = ? AND status = 'OPEN'",
				Long.class, OWNER_ACCOUNT_ID)).isOne();
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ? AND event_type = 'WISH_DEPOSIT'",
				Long.class, OWNER_ACCOUNT_ID)).isZero();
	}

	@Test
	void staleVersionIsRejectedBeforeAnyPreDepositProviderAttempt() throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");

		asOwner(post(DEPOSITS)
				.header("Idempotency-Key", "deposit-stale")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":10000,\"expectedVersion\":1}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM balance_observation WHERE account_id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT balance_lookup_version FROM card_balance_account WHERE id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isZero();
	}

	@Test
	void sameKeyRetriesAfterBalanceSyncFailureAndKeepsBothObservationsWithOneFinalDeposit()
			throws Exception {
		setBalanceScenario("[{\"type\":\"FAILURE\"},"
				+ "{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		String request = "{\"amount\":10000,\"expectedVersion\":0}";

		asOwner(post(DEPOSITS)
				.header("Idempotency-Key", "deposit-failure-then-success")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("BALANCE_SYNC_FAILED"));

		asOwner(post(DEPOSITS)
				.header("Idempotency-Key", "deposit-failure-then-success")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(260_000))
				.andExpect(jsonPath("$.wish.version").value(1));

		assertThat(jdbc.queryForList("""
				SELECT status, lookup_method, account_lookup_version, failure_code
				FROM balance_observation WHERE account_id = ?
				ORDER BY account_lookup_version
				""", OWNER_ACCOUNT_ID))
				.extracting(row -> row.get("status") + ":" + row.get("lookup_method")
						+ ":" + row.get("account_lookup_version") + ":" + row.get("failure_code"))
				.containsExactly(
						"FAILED:PRE_DEPOSIT:1:BALANCE_SYNC_FAILED",
						"SUCCEEDED:PRE_DEPOSIT:2:null");
		assertThat(jdbc.queryForObject(
				"SELECT balance_lookup_version FROM card_balance_account WHERE id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isEqualTo(2L);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ? AND event_type = 'WISH_DEPOSIT'",
				Long.class, OWNER_ACCOUNT_ID)).isOne();
		assertThat(jdbc.queryForObject("""
				SELECT jsonb_exists(wish_idempotency_records, 'deposit-failure-then-success')
				FROM student WHERE id = (SELECT student_id FROM card_balance_account WHERE id = ?)
				""", Boolean.class, OWNER_ACCOUNT_ID)).isTrue();
		mockMvc.perform(get("/e2e/card-balance-accounts/{accountId}/balance-scenario",
				OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps").isEmpty());
	}
}
