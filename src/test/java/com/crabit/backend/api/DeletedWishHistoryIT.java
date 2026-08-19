package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class DeletedWishHistoryIT extends WishApiIntegrationSupport {

	@Test
	void keepsOwnedTombstoneHistoryReadableWithPurposeAndNoDetailLink() throws Exception {
		String wishId = createWish("deleted-history", "새 노트북", 200_000);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		clock.set(COMMAND_TIME.plusSeconds(1));
		String depositEventId = JsonPath.read(asOwner(post(
				WISHES_PATH + "/" + wishId + "/deposits")
				.header("Idempotency-Key", "deleted-history-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":70000,\"expectedVersion\":0}"))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.eventId");

		clock.set(COMMAND_TIME.plusSeconds(2));
		String deletionEventId = JsonPath.read(asOwner(delete(WISHES_PATH + "/" + wishId)
				.header(HttpHeaders.IF_MATCH, "1")
				.header("Idempotency-Key", "deleted-history-delete"))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.eventId");

		asOwner(get(WISHES_PATH + "/" + wishId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("WISH_NOT_FOUND"));

		String body = asOwner(get(historyPath(wishId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.wishId").value(wishId))
				.andExpect(jsonPath("$.wish.displayPurpose").value("새 노트북"))
				.andExpect(jsonPath("$.wish.deletedWish").value(true))
				.andExpect(jsonPath("$.wish.detailAvailable").value(false))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> items = JsonPath.read(body, "$.items");
		assertThat(items).extracting(item -> item.get("eventId"))
				.containsExactly(deletionEventId, depositEventId);
		assertThat(items.getFirst())
				.containsEntry("eventType", "WISH_DELETION_RETURN")
				.containsEntry("wishPurposeSnapshot", "새 노트북")
				.containsEntry("wishAmountDelta", -70000)
				.containsEntry("wishAmountAfter", 0);
		assertThat(allFieldNames(JsonPath.parse(body).read("$")))
				.doesNotContain("href", "url", "detailPath");
	}

	@Test
	void returnsAnEmptyPageForAnOwnedZeroBalanceTombstone() throws Exception {
		String wishId = createWish("empty-deleted-history", "오래된 저축 목표", 100_000);
		asOwner(delete(WISHES_PATH + "/" + wishId)
				.header(HttpHeaders.IF_MATCH, "0")
				.header("Idempotency-Key", "empty-deleted-history-delete"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventId").doesNotExist());

		asOwner(get(historyPath(wishId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.displayPurpose").value("오래된 저축 목표"))
				.andExpect(jsonPath("$.wish.deletedWish").value(true))
				.andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	private static String historyPath(String wishId) {
		return "/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID
				+ "/wishes/" + wishId + "/fund-movements";
	}

	private static Set<String> allFieldNames(Object root) {
		java.util.HashSet<String> names = new java.util.HashSet<>();
		collect(root, names);
		return names;
	}

	private static void collect(Object value, Set<String> names) {
		if (value instanceof Map<?, ?> map) {
			map.forEach((key, child) -> {
				names.add(key.toString());
				collect(child, names);
			});
		} else if (value instanceof Iterable<?> values) {
			values.forEach(child -> collect(child, names));
		}
	}
}
