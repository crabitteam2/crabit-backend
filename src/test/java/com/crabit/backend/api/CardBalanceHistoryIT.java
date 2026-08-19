package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.STAFF_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class CardBalanceHistoryIT extends WishApiIntegrationSupport {

	private static final String CARD_HISTORY =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/card-balance-changes";
	private static final String ACCOUNT_HISTORY =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/fund-movements";

	@Test
	void returnsOnlyNonzeroSuccessfulEventsWithSharedIdentityAdjustmentAndStablePagination()
			throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":1000000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":1000000},"
				+ "{\"type\":\"FAILURE\"},"
				+ "{\"type\":\"SUCCESS\",\"balance\":700000}]");
		for (int attempt = 0; attempt < 4; attempt++) {
			clock.set(COMMAND_TIME.plusSeconds(attempt));
			var refresh = asOwner(post(
					"/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID));
			if (attempt == 2) refresh.andExpect(status().isServiceUnavailable());
			else refresh.andExpect(status().isOk());
		}

		MvcResult firstPage = asOwner(get(CARD_HISTORY).queryParam("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].eventType").value("CARD_BALANCE_CHANGE"))
				.andExpect(jsonPath("$.items[0].actualCardBalanceDelta").value(-300000))
				.andExpect(jsonPath("$.items[0].actualCardBalanceAfter").value(700000))
				.andExpect(jsonPath("$.items[0].balanceAdjustment.eventRole")
						.value("OPENING_DECREASE"))
				.andExpect(jsonPath("$.nextCursor").isString())
				.andReturn();
		String firstBody = firstPage.getResponse().getContentAsString();
		String firstEventId = JsonPath.read(firstBody, "$.items[0].eventId");
		String cursor = JsonPath.read(firstBody, "$.nextCursor");

		MvcResult secondPage = asOwner(get(CARD_HISTORY)
				.queryParam("limit", "1").queryParam("cursor", cursor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].actualCardBalanceDelta").value(1000000))
				.andExpect(jsonPath("$.nextCursor").doesNotExist())
				.andReturn();
		String secondEventId = JsonPath.read(
				secondPage.getResponse().getContentAsString(), "$.items[0].eventId");
		assertThat(secondEventId).isNotEqualTo(firstEventId);

		String accountBody = asOwner(get(ACCOUNT_HISTORY))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		List<Map<String, Object>> accountItems = JsonPath.read(accountBody, "$.items");
		assertThat(accountItems).extracting(item -> item.get("eventId"))
				.containsExactlyInAnyOrder(firstEventId, secondEventId);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM balance_observation WHERE account_id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isEqualTo(4L);
	}

	@Test
	void enforcesCursorScopeAuthenticationAndOwnerOnlyVisibility() throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":1000000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":900000}]");
		for (int attempt = 0; attempt < 2; attempt++) {
			clock.set(COMMAND_TIME.plusSeconds(attempt));
			asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID))
					.andExpect(status().isOk());
		}
		String cursor = JsonPath.read(asOwner(get(CARD_HISTORY).queryParam("limit", "1"))
				.andReturn().getResponse().getContentAsString(), "$.nextCursor");
		assertThat(cursor).isNotNull();
		asOwner(get(ACCOUNT_HISTORY).queryParam("cursor", cursor))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
		asToken(FRIEND_TOKEN, get(CARD_HISTORY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));
		asToken(STAFF_TOKEN, get(CARD_HISTORY))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		mockMvc.perform(get(CARD_HISTORY))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
		asOwner(get(CARD_HISTORY).queryParam("cursor", "not-a-cursor"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("cursor"));
		asOwner(get(CARD_HISTORY).queryParam("limit", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("limit"));
	}

	@Test
	void preservesEqualTimestampBalancesAndTraversesEachEventOnceRegardlessOfUuidOrder()
			throws Exception {
		jdbc.update("UPDATE wish SET wish_amount = 0, state = 'IN_PROGRESS' WHERE account_id = ?",
				OWNER_ACCOUNT_ID);
		Instant occurredAt = COMMAND_TIME.plusSeconds(20);
		UUID firstEventId = UUID.fromString("10000000-0000-0000-0000-000000000003");
		UUID secondEventId = UUID.fromString("10000000-0000-0000-0000-000000000001");
		UUID thirdEventId = UUID.fromString("10000000-0000-0000-0000-000000000002");
		UUID firstObservationId = insertCardChange(
				firstEventId, null, null, 1, 100_000, 100_000, occurredAt);
		UUID secondObservationId = insertCardChange(
				secondEventId, firstObservationId, 100_000L,
				2, 130_000, 30_000, occurredAt);
		insertCardChange(
				thirdEventId, secondObservationId, 130_000L,
				3, 80_000, -50_000, occurredAt);
		jdbc.update("UPDATE card_balance_account SET balance_lookup_version = 3 WHERE id = ?",
				OWNER_ACCOUNT_ID);

		List<Map<String, Object>> cardItems = readEveryPage(CARD_HISTORY);
		List<Map<String, Object>> accountItems = readEveryPage(ACCOUNT_HISTORY);
		assertThat(cardItems).extracting(item -> item.get("eventId"))
				.containsExactly(
						firstEventId.toString(), thirdEventId.toString(), secondEventId.toString());
		assertThat(new HashSet<>(cardItems.stream()
				.map(item -> item.get("eventId")).toList())).hasSize(3);
		assertThat(accountItems).extracting(item -> item.get("eventId"))
				.containsExactlyElementsOf(cardItems.stream()
						.map(item -> item.get("eventId")).toList());
		assertAvailableAfter(accountItems, firstEventId, 100_000);
		assertAvailableAfter(accountItems, secondEventId, 130_000);
		assertAvailableAfter(accountItems, thirdEventId, 80_000);
	}

	@Test
	void preservesCompletionOrderedBalancesWhenObservedTimesAreInverted() throws Exception {
		jdbc.update("UPDATE wish SET wish_amount = 0, state = 'IN_PROGRESS' WHERE account_id = ?",
				OWNER_ACCOUNT_ID);
		UUID baselineEventId = UUID.fromString("20000000-0000-0000-0000-000000000001");
		UUID laterCompletedFirstEventId =
				UUID.fromString("20000000-0000-0000-0000-000000000002");
		UUID earlierCompletedLastEventId =
				UUID.fromString("20000000-0000-0000-0000-000000000003");
		UUID baselineObservationId = insertCardChange(
				baselineEventId, null, null, 1, 200_000, 200_000,
				COMMAND_TIME.minusSeconds(1));
		UUID laterCompletedFirstObservationId = insertCardChange(
				laterCompletedFirstEventId, baselineObservationId, 200_000L,
				2, 100_000, -100_000, COMMAND_TIME.plusSeconds(1));
		insertCardChange(
				earlierCompletedLastEventId, laterCompletedFirstObservationId, 100_000L,
				3, 200_000, 100_000, COMMAND_TIME);
		jdbc.update("UPDATE card_balance_account SET balance_lookup_version = 3 WHERE id = ?",
				OWNER_ACCOUNT_ID);

		List<Map<String, Object>> cardItems = readEveryPage(CARD_HISTORY);
		List<Map<String, Object>> accountItems = readEveryPage(ACCOUNT_HISTORY);
		assertThat(accountItems).extracting(item -> item.get("eventId"))
				.containsExactlyElementsOf(cardItems.stream()
						.map(item -> item.get("eventId")).toList());
		assertAvailableAfter(accountItems, baselineEventId, 200_000);
		assertAvailableAfter(accountItems, laterCompletedFirstEventId, 100_000);
		assertAvailableAfter(accountItems, earlierCompletedLastEventId, 200_000);
	}

	private UUID insertCardChange(
			UUID eventId,
			UUID previousObservationId,
			Long previousBalance,
			long lookupVersion,
			long actualBalance,
			long delta,
			Instant occurredAt) {
		jdbc.update("""
				INSERT INTO ledger_event
				    (id, account_id, event_type, account_delta, occurred_at)
				VALUES (?, ?, 'CARD_BALANCE_CHANGE', ?, ?)
				""", eventId, OWNER_ACCOUNT_ID, delta, Timestamp.from(occurredAt));
		UUID observationId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO balance_observation
				    (id, account_id, status, lookup_method, actual_card_balance,
				     account_lookup_version, first_successful,
				     previous_successful_observation_id, previous_successful_balance,
				     balance_change_event_id, balance_change_event_type,
				     balance_change_event_delta, observed_at)
				VALUES (?, ?, 'SUCCEEDED', 'USER_REQUESTED', ?, ?, ?, ?, ?, ?,
				        'CARD_BALANCE_CHANGE', ?, ?)
				""", observationId, OWNER_ACCOUNT_ID, actualBalance, lookupVersion,
				lookupVersion == 1 ? Boolean.TRUE : null,
				previousObservationId, lookupVersion == 1 ? 0L : previousBalance,
				eventId, delta, Timestamp.from(occurredAt));
		return observationId;
	}

	private List<Map<String, Object>> readEveryPage(String path) throws Exception {
		List<Map<String, Object>> items = new ArrayList<>();
		String cursor = null;
		do {
			var request = get(path).queryParam("limit", "1");
			if (cursor != null) request.queryParam("cursor", cursor);
			String body = asOwner(request)
					.andExpect(status().isOk()).andReturn()
					.getResponse().getContentAsString();
			Map<String, Object> page = JsonPath.read(body, "$");
			items.addAll(JsonPath.read(body, "$.items"));
			cursor = (String) page.get("nextCursor");
			assertThat(items.size()).isLessThanOrEqualTo(3);
		} while (cursor != null);
		return items;
	}

	private static void assertAvailableAfter(
			List<Map<String, Object>> items, UUID eventId, long expected) {
		Map<String, Object> item = items.stream()
				.filter(candidate -> eventId.toString().equals(candidate.get("eventId")))
				.findFirst().orElseThrow();
		assertThat(((Number) item.get("accountAvailableBalanceAfter")).longValue())
				.isEqualTo(expected);
	}
}
