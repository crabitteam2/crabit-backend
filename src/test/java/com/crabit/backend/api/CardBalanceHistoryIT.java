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
import java.util.List;
import java.util.Map;
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
}
