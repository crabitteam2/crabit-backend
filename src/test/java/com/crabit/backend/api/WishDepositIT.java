package com.crabit.backend.api;

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

class WishDepositIT extends WishApiIntegrationSupport {

	private static final String DEPOSITS = WISHES_PATH + "/" + LAPTOP_WISH_ID + "/deposits";

	@Test
	void depositsExactMaximumReachesTargetAndIdenticalReplayDoesNotRefreshAgain()
			throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		String request = "{\"amount\":1250000,\"expectedVersion\":0}";

		MvcResult first = asOwner(post(DEPOSITS)
				.header("Idempotency-Key", "deposit-maximum")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andExpect(jsonPath("$.wish.amount").value(1_500_000))
				.andExpect(jsonPath("$.wish.state").value("AMOUNT_REACHED"))
				.andExpect(jsonPath("$.wish.version").value(1))
				.andExpect(jsonPath("$.eventId").isString())
				.andReturn();

		MvcResult replay = asOwner(post(DEPOSITS)
				.header("Idempotency-Key", "deposit-maximum")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn();

		assertThat(replay.getResponse().getContentAsString())
				.isEqualTo(first.getResponse().getContentAsString());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM balance_observation WHERE account_id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isOne();
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ? AND event_type = 'WISH_DEPOSIT'",
				Long.class, OWNER_ACCOUNT_ID)).isOne();
	}

	@Test
	void rejectsZeroTargetOverflowAndDisplayAvailabilityOverflowWithoutMutation()
			throws Exception {
		asOwner(post(DEPOSITS)
				.header("Idempotency-Key", "deposit-zero")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":0,\"expectedVersion\":0}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error.code").value("INVALID_AMOUNT"));

		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":3000000}]");
		asOwner(post(DEPOSITS)
				.header("Idempotency-Key", "deposit-target-overflow")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":1250001,\"expectedVersion\":0}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("TARGET_AMOUNT_EXCEEDED"));

		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":850000}]");
		asOwner(post(DEPOSITS)
				.header("Idempotency-Key", "deposit-availability-overflow")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":100001,\"expectedVersion\":0}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_AVAILABLE_BALANCE"));

		assertThat(jdbc.queryForObject(
				"SELECT wish_amount FROM wish WHERE id = ?", Long.class, LAPTOP_WISH_ID))
				.isEqualTo(250_000L);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ? AND event_type = 'WISH_DEPOSIT'",
				Long.class, OWNER_ACCOUNT_ID)).isZero();
	}
}
