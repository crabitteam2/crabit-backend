package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class WishTransferIT extends WishApiIntegrationSupport {

	private static final String TRANSFERS =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/transfers";

	@Test
	void transfersExactDestinationRemainderAsOneEventAndReplaysBothSnapshots()
			throws Exception {
		String destinationId = createWish("transfer-destination", "새 자전거", 100_000);
		String request = """
				{"sourceWishId":"%s","destinationWishId":"%s","amount":100000,
				"sourceExpectedVersion":0,"destinationExpectedVersion":0}
				""".formatted(LAPTOP_WISH_ID, destinationId);

		MvcResult first = asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "transfer-exact")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andExpect(jsonPath("$.sourceWish.amount").value(150_000))
				.andExpect(jsonPath("$.destinationWish.amount").value(100_000))
				.andExpect(jsonPath("$.destinationWish.state").value("AMOUNT_REACHED"))
				.andExpect(jsonPath("$.sourceWish.version").value(1))
				.andExpect(jsonPath("$.destinationWish.version").value(1))
				.andExpect(jsonPath("$.eventId").isString())
				.andExpect(jsonPath("$.occurredAt").value(COMMAND_TIME.toString()))
				.andReturn();

		MvcResult replay = asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "transfer-exact")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn();

		assertThat(replay.getResponse().getContentAsString())
				.isEqualTo(first.getResponse().getContentAsString());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ? AND event_type = 'WISH_TRANSFER'",
				Long.class, OWNER_ACCOUNT_ID)).isOne();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ledger_wish_effect effect
				JOIN ledger_event event ON event.id = effect.event_id
				WHERE event.account_id = ? AND event.event_type = 'WISH_TRANSFER'
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(2L);
	}

	@Test
	void rejectsDestinationOverflowAndSourceInsufficiencyWithoutPartialMovement()
			throws Exception {
		String smallDestination = createWish("transfer-small", "작은 목표", 99_999);
		asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "transfer-overflow")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sourceWishId":"%s","destinationWishId":"%s","amount":100000,
						"sourceExpectedVersion":0,"destinationExpectedVersion":0}
						""".formatted(LAPTOP_WISH_ID, smallDestination)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("TARGET_AMOUNT_EXCEEDED"));

		String largeDestination = createWish("transfer-large", "큰 목표", 500_000);
		asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "transfer-insufficient")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sourceWishId":"%s","destinationWishId":"%s","amount":250001,
						"sourceExpectedVersion":0,"destinationExpectedVersion":0}
						""".formatted(LAPTOP_WISH_ID, largeDestination)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_WISH_AMOUNT"));

		assertThat(jdbc.queryForObject(
				"SELECT wish_amount FROM wish WHERE id = ?", Long.class, LAPTOP_WISH_ID))
				.isEqualTo(250_000L);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ? AND event_type = 'WISH_TRANSFER'",
				Long.class, OWNER_ACCOUNT_ID)).isZero();
	}

	@Test
	void rejectsCrossAccountDestinationWithContractError() throws Exception {
		UUID otherAccountId = UUID.randomUUID();
		UUID otherWishId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO card_balance_account
				(id, student_id, academy_id, opened_at, closed_at, balance_lookup_version, version)
				VALUES (?, ?, ?, ?, NULL, 0, 0)
				""", otherAccountId, FRIEND_ID, PRIMARY_ACADEMY_ID,
				Timestamp.from(COMMAND_TIME));
		jdbc.update("""
				INSERT INTO wish
				(id, account_id, academy_id, purpose, target_amount, wish_amount, state,
				 visibility, created_at, version)
				VALUES (?, ?, ?, '다른 계정 위시', 100000, 0, 'IN_PROGRESS', 'PRIVATE', ?, 0)
				""", otherWishId, otherAccountId, PRIMARY_ACADEMY_ID,
				Timestamp.from(COMMAND_TIME));

		asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "transfer-cross-account")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sourceWishId":"%s","destinationWishId":"%s","amount":1,
						"sourceExpectedVersion":0,"destinationExpectedVersion":0}
						""".formatted(LAPTOP_WISH_ID, otherWishId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code")
						.value("CROSS_ACCOUNT_TRANSFER_FORBIDDEN"));

		assertThat(jdbc.queryForObject(
				"SELECT wish_amount FROM wish WHERE id = ?", Long.class, LAPTOP_WISH_ID))
				.isEqualTo(250_000L);
		assertThat(jdbc.queryForObject(
				"SELECT wish_amount FROM wish WHERE id = ?", Long.class, otherWishId))
				.isZero();
	}
}
