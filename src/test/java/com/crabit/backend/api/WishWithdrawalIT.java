package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class WishWithdrawalIT extends WishApiIntegrationSupport {

	@Test
	void fullWithdrawalFromAmountReachedLeavesWishActiveAtZeroAndReplaysOriginalResult()
			throws Exception {
		String request = "{\"amount\":500000,\"expectedVersion\":0}";
		MvcResult first = asOwner(post(withdrawals(CAMP_WISH_ID))
				.header("Idempotency-Key", "withdraw-full")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andExpect(jsonPath("$.wish.amount").value(0))
				.andExpect(jsonPath("$.wish.state").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.wish.version").value(1))
				.andExpect(jsonPath("$.eventId").isString())
				.andReturn();

		MvcResult replay = asOwner(post(withdrawals(CAMP_WISH_ID))
				.header("Idempotency-Key", "withdraw-full")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn();

		assertThat(replay.getResponse().getContentAsString())
				.isEqualTo(first.getResponse().getContentAsString());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ? AND event_type = 'WISH_WITHDRAWAL'",
				Long.class, OWNER_ACCOUNT_ID)).isOne();
	}

	@Test
	void partialWithdrawalFromAmountReachedRecalculatesStateToInProgress() throws Exception {
		asOwner(post(withdrawals(CAMP_WISH_ID))
				.header("Idempotency-Key", "withdraw-partial-reached")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":1,\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(499_999))
				.andExpect(jsonPath("$.wish.state").value("IN_PROGRESS"));
	}

	@Test
	void rejectsAmountAboveCurrentWishAmountWithoutCreatingLedgerFact() throws Exception {
		asOwner(post(withdrawals(LAPTOP_WISH_ID))
				.header("Idempotency-Key", "withdraw-too-much")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":250001,\"expectedVersion\":0}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_WISH_AMOUNT"));

		assertThat(jdbc.queryForObject(
				"SELECT wish_amount FROM wish WHERE id = ?", Long.class, LAPTOP_WISH_ID))
				.isEqualTo(250_000L);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ? AND event_type = 'WISH_WITHDRAWAL'",
				Long.class, OWNER_ACCOUNT_ID)).isZero();
	}

	private static String withdrawals(java.util.UUID wishId) {
		return WISHES_PATH + "/" + wishId + "/withdrawals";
	}
}
