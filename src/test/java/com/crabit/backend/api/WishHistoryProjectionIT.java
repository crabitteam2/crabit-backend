package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class WishHistoryProjectionIT extends WishApiIntegrationSupport {

	private static final String TRANSFERS =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/transfers";

	@Test
	void preservesEventTimePurposeBothTransferSidesAndWishAmountsWithoutCardLeakage()
			throws Exception {
		String sourceWishId = createWish("wish-history-source", "카메라", 300_000);
		String destinationWishId = createWish("wish-history-destination", "여행", 300_000);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");

		clock.set(COMMAND_TIME.plusSeconds(1));
		String depositEventId = JsonPath.read(asOwner(post(
				WISHES_PATH + "/" + sourceWishId + "/deposits")
				.header("Idempotency-Key", "wish-history-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":100000,\"expectedVersion\":0}"))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.eventId");

		clock.set(COMMAND_TIME.plusSeconds(2));
		asOwner(patch(WISHES_PATH + "/" + sourceWishId)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":1,\"purpose\":\"영상 장비\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.purpose").value("영상 장비"))
				.andExpect(jsonPath("$.wish.version").value(2));

		clock.set(COMMAND_TIME.plusSeconds(3));
		String transferEventId = JsonPath.read(asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "wish-history-transfer")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sourceWishId":"%s","destinationWishId":"%s","amount":30000,
						"sourceExpectedVersion":2,"destinationExpectedVersion":0}
						""".formatted(sourceWishId, destinationWishId)))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.eventId");

		clock.set(COMMAND_TIME.plusSeconds(4));
		String withdrawalEventId = JsonPath.read(asOwner(post(
				WISHES_PATH + "/" + sourceWishId + "/withdrawals")
				.header("Idempotency-Key", "wish-history-withdrawal")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":10000,\"expectedVersion\":3}"))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.eventId");

		String sourcePath = historyPath(sourceWishId);
		String sourceBody = asOwner(get(sourcePath))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.displayPurpose").value("영상 장비"))
				.andExpect(jsonPath("$.wish.deletedWish").value(false))
				.andExpect(jsonPath("$.items.length()").value(3))
				.andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> sourceItems = JsonPath.read(sourceBody, "$.items");
		assertThat(sourceItems).extracting(item -> item.get("eventType"))
				.containsExactly("WISH_WITHDRAWAL", "WISH_TRANSFER", "WISH_DEPOSIT")
				.doesNotContain("CARD_BALANCE_CHANGE");

		assertThat(event(sourceItems, depositEventId))
				.containsEntry("wishPurposeSnapshot", "카메라")
				.containsEntry("wishAmountDelta", 100000)
				.containsEntry("wishAmountAfter", 100000);
		assertThat(event(sourceItems, withdrawalEventId))
				.containsEntry("wishPurposeSnapshot", "영상 장비")
				.containsEntry("wishAmountDelta", -10000)
				.containsEntry("wishAmountAfter", 60000);
		Map<String, Object> sourceTransfer = event(sourceItems, transferEventId);
		assertThat(sourceTransfer).containsEntry("direction", "SOURCE")
				.containsEntry("wishAmountDelta", -30000)
				.containsEntry("wishAmountAfter", 70000);
		assertThat(map(sourceTransfer.get("counterpartyWish")))
				.containsEntry("wishId", destinationWishId)
				.containsEntry("wishPurposeSnapshot", "여행");

		List<Map<String, Object>> destinationItems = JsonPath.read(
				asOwner(get(historyPath(destinationWishId))).andExpect(status().isOk())
						.andReturn().getResponse().getContentAsString(), "$.items");
		Map<String, Object> destinationTransfer = event(destinationItems, transferEventId);
		assertThat(destinationTransfer).containsEntry("direction", "DESTINATION")
				.containsEntry("wishAmountDelta", 30000)
				.containsEntry("wishAmountAfter", 30000);

		String firstCursor = JsonPath.read(asOwner(get(sourcePath).queryParam("limit", "1"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.nextCursor");
		asOwner(get(sourcePath).queryParam("limit", "2").queryParam("cursor", firstCursor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2));
		asToken(FRIEND_TOKEN, get(sourcePath))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));
	}

	private static String historyPath(String wishId) {
		return WISHES_PATH + "/" + wishId + "/fund-movements";
	}

	private static Map<String, Object> event(
			List<Map<String, Object>> items, String eventId) {
		return items.stream().filter(item -> eventId.equals(item.get("eventId")))
				.findFirst().orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}
}
