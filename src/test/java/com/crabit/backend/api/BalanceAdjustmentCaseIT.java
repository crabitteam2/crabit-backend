package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class BalanceAdjustmentCaseIT extends WishApiIntegrationSupport {

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
}
