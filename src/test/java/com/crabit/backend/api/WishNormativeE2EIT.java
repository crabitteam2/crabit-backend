package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Normative end-to-end acceptance suite. Inherited scenarios keep the immutable
 * account and Wish history projections tied to the same PostgreSQL ledger facts.
 */
class WishNormativeE2EIT extends FundMovementHistoryIT {

	@Test
	void firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts()
			throws Exception {
		String wishId = createWish("normative-repeat-create", "다음 날에도 모으기", 300_000);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":2000000}]");

		asOwner(post(WISHES_PATH + "/" + wishId + "/deposits")
				.header("Idempotency-Key", "normative-first-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":100000,\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(100_000));

		clock.set(COMMAND_TIME.plus(Duration.ofDays(1)));
		asOwner(post(WISHES_PATH + "/" + wishId + "/deposits")
				.header("Idempotency-Key", "normative-next-day-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":100000,\"expectedVersion\":1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(200_000))
				.andExpect(jsonPath("$.wish.version").value(2));

		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM balance_observation
				WHERE account_id = ? AND status = 'SUCCEEDED' AND lookup_method = 'PRE_DEPOSIT'
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(2L);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ledger_event
				WHERE account_id = ? AND event_type = 'CARD_BALANCE_CHANGE'
				  AND account_delta = 2000000
				""", Long.class, OWNER_ACCOUNT_ID)).isOne();
		assertThat(jdbc.queryForList("""
				SELECT event.occurred_at FROM ledger_event event
				JOIN ledger_wish_effect effect ON effect.event_id = event.id
				WHERE effect.wish_id = ?::uuid AND event.event_type = 'WISH_DEPOSIT'
				ORDER BY event.occurred_at
				""", Timestamp.class, wishId))
				.extracting(Timestamp::toInstant)
				.containsExactly(COMMAND_TIME, COMMAND_TIME.plus(Duration.ofDays(1)));
	}
}
