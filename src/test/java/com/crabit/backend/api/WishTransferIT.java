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

	@Test
	void successfulTransferPreservesActualBalanceAllocationAndAccountAvailabilityAsOneBalancedFact()
			throws Exception {
		String destinationId = createWish("transfer-invariant-destination", "새 책상", 200_000);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":1900000}]");
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk());

		long actualBefore = latestActualBalance();
		long allocationBefore = activeAllocation();
		long availabilityBefore = actualBefore - allocationBefore;

		asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "transfer-invariant")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sourceWishId":"%s","destinationWishId":"%s","amount":50000,
						"sourceExpectedVersion":0,"destinationExpectedVersion":0}
						""".formatted(LAPTOP_WISH_ID, destinationId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceWish.amount").value(200_000))
				.andExpect(jsonPath("$.destinationWish.amount").value(50_000));

		long actualAfter = latestActualBalance();
		long allocationAfter = activeAllocation();
		assertThat(actualAfter).isEqualTo(actualBefore);
		assertThat(allocationAfter).isEqualTo(allocationBefore);
		assertThat(actualAfter - allocationAfter).isEqualTo(availabilityBefore);
		assertThat(jdbc.queryForMap("""
				SELECT event_type, account_delta FROM ledger_event
				WHERE account_id = ? AND event_type = 'WISH_TRANSFER'
				""", OWNER_ACCOUNT_ID))
				.containsEntry("event_type", "WISH_TRANSFER")
				.containsEntry("account_delta", 0L);
		assertThat(jdbc.queryForList("""
				SELECT effect.wish_id, effect.wish_delta FROM ledger_wish_effect effect
				JOIN ledger_event event ON event.id = effect.event_id
				WHERE effect.account_id = ? AND event.event_type = 'WISH_TRANSFER'
				ORDER BY wish_delta
				""", OWNER_ACCOUNT_ID))
				.extracting(row -> ((Number) row.get("wish_delta")).longValue())
				.containsExactly(-50_000L, 50_000L);
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
				"/e2e/card-balance-accounts/{accountId}/balance-scenario", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps.length()").value(1));
	}

	@Test
	void identicalEndpointsAndOpenMismatchRejectWithoutTransferOrProviderConsumption()
			throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		String identical = """
				{"sourceWishId":"%s","destinationWishId":"%s","amount":1,
				"sourceExpectedVersion":0,"destinationExpectedVersion":0}
				""".formatted(LAPTOP_WISH_ID, LAPTOP_WISH_ID);
		asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "transfer-identical")
				.contentType(MediaType.APPLICATION_JSON)
				.content(identical))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
		assertThat(jdbc.queryForObject(
				"SELECT wish_amount FROM wish WHERE id = ?", Long.class, LAPTOP_WISH_ID))
				.isEqualTo(250_000L);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE event_type = 'WISH_TRANSFER'",
				Long.class)).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT jsonb_exists(wish_idempotency_records, 'transfer-identical')
				FROM student WHERE id = (SELECT student_id FROM card_balance_account WHERE id = ?)
				""", Boolean.class, OWNER_ACCOUNT_ID)).isFalse();

		String destinationId = createWish("transfer-mismatch-destination", "조정 중 목표", 100_000);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":800000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":700000}]");
		for (int refresh = 0; refresh < 2; refresh++) {
			asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes",
					OWNER_ACCOUNT_ID)).andExpect(status().isOk());
		}
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM balance_adjustment_case
				WHERE account_id = ? AND status = 'OPEN'
				""", Long.class, OWNER_ACCOUNT_ID)).isOne();
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":900000}]");

		asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "transfer-open-mismatch")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sourceWishId":"%s","destinationWishId":"%s","amount":1,
						"sourceExpectedVersion":0,"destinationExpectedVersion":0}
						""".formatted(LAPTOP_WISH_ID, destinationId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BALANCE_MISMATCH_LOCKED"));

		assertThat(jdbc.queryForObject(
				"SELECT wish_amount FROM wish WHERE id = ?", Long.class, LAPTOP_WISH_ID))
				.isEqualTo(250_000L);
		assertThat(jdbc.queryForObject(
				"SELECT wish_amount FROM wish WHERE id = ?::uuid", Long.class, destinationId))
				.isZero();
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE event_type = 'WISH_TRANSFER'",
				Long.class)).isZero();
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
				"/e2e/card-balance-accounts/{accountId}/balance-scenario", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps.length()").value(1));
	}

	private long latestActualBalance() {
		return jdbc.queryForObject("""
				SELECT actual_card_balance FROM balance_observation
				WHERE account_id = ? AND status = 'SUCCEEDED'
				ORDER BY account_lookup_version DESC LIMIT 1
				""", Long.class, OWNER_ACCOUNT_ID);
	}

	private long activeAllocation() {
		return jdbc.queryForObject("""
				SELECT coalesce(sum(wish_amount), 0) FROM wish
				WHERE account_id = ? AND deleted_at IS NULL
				AND state IN ('IN_PROGRESS', 'AMOUNT_REACHED')
				""", Long.class, OWNER_ACCOUNT_ID);
	}
}
