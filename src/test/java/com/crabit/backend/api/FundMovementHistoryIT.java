package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
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

	@Test
	void projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage() throws Exception {
		String completionWishId = createWish("history-completion", "완료 반환", 10_000);
		String abandonmentWishId = createWish("history-abandonment", "포기 반환", 100_000);
		String deletionWishId = createWish("history-deletion", "삭제 반환", 100_000);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":1000000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":1000000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":1000000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":760000}]");

		deposit(completionWishId, 10_000, "terminal-completion-deposit");
		deposit(abandonmentWishId, 20_000, "terminal-abandonment-deposit");
		deposit(deletionWishId, 30_000, "terminal-deletion-deposit");
		clock.set(COMMAND_TIME.plusSeconds(4));
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk());

		clock.set(COMMAND_TIME.plusSeconds(5));
		String completionEventId = JsonPath.read(asOwner(post(
				WISHES_PATH + "/" + completionWishId + "/completion")
				.header("Idempotency-Key", "terminal-completion")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":1}"))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.eventId");

		clock.set(COMMAND_TIME.plusSeconds(6));
		String abandonmentEventId = JsonPath.read(asOwner(post(
				WISHES_PATH + "/" + abandonmentWishId + "/abandonment")
				.header("Idempotency-Key", "terminal-abandonment")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":1}"))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.eventId");

		clock.set(COMMAND_TIME.plusSeconds(7));
		String deletionEventId = JsonPath.read(asOwner(delete(
				WISHES_PATH + "/" + deletionWishId)
				.header(HttpHeaders.IF_MATCH, "1")
				.header("Idempotency-Key", "terminal-deletion"))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.eventId");

		List<Map<String, Object>> items = JsonPath.read(
				asOwner(get(ACCOUNT_HISTORY)).andExpect(status().isOk()).andReturn()
						.getResponse().getContentAsString(), "$.items");
		Map<String, Object> completion = event(items, completionEventId);
		Map<String, Object> abandonment = event(items, abandonmentEventId);
		Map<String, Object> deletion = event(items, deletionEventId);
		assertTerminalReturn(completion, "WISH_COMPLETION_RETURN", 10_000, -40_000,
				"INTERMEDIATE");
		assertTerminalReturn(abandonment, "WISH_ABANDONMENT_RETURN", 20_000, -20_000,
				"INTERMEDIATE");
		assertTerminalReturn(deletion, "WISH_DELETION_RETURN", 30_000, 10_000,
				"RESOLUTION");
		assertThat(map(completion.get("balanceAdjustment")).get("adjustmentCaseId"))
				.isEqualTo(map(abandonment.get("balanceAdjustment")).get("adjustmentCaseId"))
				.isEqualTo(map(deletion.get("balanceAdjustment")).get("adjustmentCaseId"));

		assertWishTerminalReason(completionWishId, completionEventId, "WISH_COMPLETION_RETURN");
		assertWishTerminalReason(abandonmentWishId, abandonmentEventId,
				"WISH_ABANDONMENT_RETURN");
		assertWishTerminalReason(deletionWishId, deletionEventId, "WISH_DELETION_RETURN");
	}

	private void deposit(String wishId, long amount, String idempotencyKey) throws Exception {
		clock.set(clock.instant().plusSeconds(1));
		asOwner(post(WISHES_PATH + "/" + wishId + "/deposits")
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":%d,\"expectedVersion\":0}".formatted(amount)))
				.andExpect(status().isOk());
	}

	private void assertWishTerminalReason(
			String wishId, String eventId, String expectedEventType) throws Exception {
		List<Map<String, Object>> wishItems = JsonPath.read(
				asOwner(get(WISHES_PATH + "/" + wishId + "/fund-movements"))
						.andExpect(status().isOk()).andReturn()
						.getResponse().getContentAsString(), "$.items");
		assertThat(event(wishItems, eventId))
				.containsEntry("eventType", expectedEventType)
				.containsEntry("wishAmountAfter", 0);
	}

	private static void assertTerminalReturn(
			Map<String, Object> item,
			String eventType,
			long availableDelta,
			long availableAfter,
			String adjustmentRole) {
		assertThat(item)
				.containsEntry("eventType", eventType)
				.containsEntry("accountAvailableBalanceDelta", (int) availableDelta)
				.containsEntry("accountAvailableBalanceAfter", (int) availableAfter);
		assertThat(map(item.get("balanceAdjustment")))
				.containsEntry("eventRole", adjustmentRole);
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
