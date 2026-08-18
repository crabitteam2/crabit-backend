package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class FundMovementHistoryIT extends WishApiIntegrationSupport {

	private static final String ACCOUNT_HISTORY =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/fund-movements";
	private static final String TRANSFERS =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/transfers";

	@Test
	void projectsEveryLedgerKindWithSignedAvailabilityAndOneTransferItem() throws Exception {
		String sourceWishId = createWish("history-source", "카메라", 300_000);
		String destinationWishId = createWish("history-destination", "여행", 300_000);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");

		clock.set(COMMAND_TIME.plusSeconds(1));
		String depositBody = asOwner(post(WISHES_PATH + "/" + sourceWishId + "/deposits")
				.header("Idempotency-Key", "history-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":100000,\"expectedVersion\":0}"))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		String depositEventId = JsonPath.read(depositBody, "$.eventId");

		clock.set(COMMAND_TIME.plusSeconds(2));
		String transferBody = asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "history-transfer")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sourceWishId":"%s","destinationWishId":"%s","amount":30000,
						"sourceExpectedVersion":1,"destinationExpectedVersion":0}
						""".formatted(sourceWishId, destinationWishId)))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		String transferEventId = JsonPath.read(transferBody, "$.eventId");

		clock.set(COMMAND_TIME.plusSeconds(3));
		String withdrawalBody = asOwner(post(
				WISHES_PATH + "/" + destinationWishId + "/withdrawals")
				.header("Idempotency-Key", "history-withdrawal")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":10000,\"expectedVersion\":1}"))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		String withdrawalEventId = JsonPath.read(withdrawalBody, "$.eventId");

		String historyBody = asOwner(get(ACCOUNT_HISTORY))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		List<Map<String, Object>> items = JsonPath.read(historyBody, "$.items");
		assertThat(items).extracting(item -> item.get("eventType"))
				.contains("CARD_BALANCE_CHANGE", "WISH_DEPOSIT", "WISH_TRANSFER", "WISH_WITHDRAWAL");

		Map<String, Object> deposit = event(items, depositEventId);
		assertThat(deposit).containsEntry("accountAvailableBalanceDelta", -100000);
		assertThat(map(deposit.get("wish"))).containsEntry("wishPurposeSnapshot", "카메라");

		Map<String, Object> transfer = event(items, transferEventId);
		assertThat(transfer).containsEntry("eventType", "WISH_TRANSFER")
				.containsEntry("amount", 30000)
				.containsEntry("accountAvailableBalanceDelta", 0);
		assertThat(items.stream().filter(item -> transferEventId.equals(item.get("eventId"))))
				.hasSize(1);
		assertThat(map(transfer.get("sourceWish"))).containsEntry("wishId", sourceWishId);
		assertThat(map(transfer.get("destinationWish")))
				.containsEntry("wishId", destinationWishId);

		Map<String, Object> withdrawal = event(items, withdrawalEventId);
		assertThat(withdrawal).containsEntry("accountAvailableBalanceDelta", 10000);
		assertThat(((Number) withdrawal.get("accountAvailableBalanceAfter")).longValue())
				.isEqualTo(1_160_000L);
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
