package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Exercises real HTTP serialization, authorization and PostgreSQL filtering, not a mock repository. */
class AccountFundMovementQueryIT extends WishApiIntegrationSupport {
	private static final String HISTORY = "/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/fund-movements";

	@Test
	void searchesOutsideTheFirstPageAndPreservesHistoricalBalances() throws Exception {
		String wish = createWish("query-wish", "CaM 100%_A", 300000);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		String deposit = deposit(wish, 5000, 0, "query-deposit");
		clock.set(COMMAND_TIME.plusSeconds(20));
		asOwner(post(WISHES_PATH + "/" + wish + "/withdrawals")
				.header("Idempotency-Key", "query-withdraw")
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000,\"expectedVersion\":1}"))
				.andExpect(status().isOk());
		List<Map<String, Object>> all = items(read(get(HISTORY)));
		assertThat(items(read(get(HISTORY).queryParam("limit", "1"))).getFirst().get("eventId")).isNotEqualTo(deposit);
		Map<String, Object> expected = all.stream().filter(item -> deposit.equals(item.get("eventId"))).findFirst().orElseThrow();
		// Seed allocates 750,000 won; card observation is 2,000,000 and this deposit allocates 5,000.
		assertThat(expected).containsEntry("accountAvailableBalanceAfter", 1245000)
				.containsEntry("accountAvailableBalanceDelta", -5000);
		assertThat(items(read(get(HISTORY).queryParam("q", "위시 빼기"))))
				.singleElement().satisfies(item -> assertThat(item)
					.containsEntry("accountAvailableBalanceAfter", 1246000)
					.containsEntry("accountAvailableBalanceDelta", 1000));
		for (String query : List.of("위시 넣기", "5000", "5,000", "+5,000원", "-5,000 원", "0005000", "0".repeat(60) + "5000")) {
			List<Map<String, Object>> results = items(read(get(HISTORY).queryParam("q", query).queryParam("limit", "1")));
			assertThat(results).containsExactly(expected);
		}
		assertThat(items(read(get(HISTORY).queryParam("q", " CAM\u00a0 100%_a "))))
				.extracting(item -> item.get("eventId")).contains(deposit);
		assertThat(items(read(get(HISTORY).queryParam("q", "100%_")))).hasSize(2);
		assertThat(items(read(get(HISTORY).queryParam("q", "100%X")))).isEmpty();
		for (String query : List.of("5,00", "5000.0", "9007199254740992", "\\", "no-match")) {
			assertThat(items(read(get(HISTORY).queryParam("q", query)))).isEmpty();
		}
		assertThat(items(read(get(HISTORY).queryParam("from", COMMAND_TIME.toString())
				.queryParam("to", COMMAND_TIME.plusSeconds(1).toString()).queryParam("q", "위시 넣기"))))
				.containsExactly(expected);
	}

	@Test
	void searchesBothTransferNamesOnceAndRetainsRenamedDeletedSnapshots() throws Exception {
		String source = createWish("search-source", "출발 카메라", 300000);
		String destination = createWish("search-destination", "도착 여행", 300000);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		deposit(source, 10000, 0, "search-deposit");
		String eventId = json(asOwner(post("/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/transfers")
				.header("Idempotency-Key", "search-transfer").contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"sourceWishId":"%s","destinationWishId":"%s","amount":5000,
					 "sourceExpectedVersion":1,"destinationExpectedVersion":0}
					""".formatted(source, destination))).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.eventId");
		// A later current-name change must never rewrite immutable historical names.
		jdbc.update("update wish set purpose = '현재 다른 이름' where id = ?::uuid", source);
		asOwner(delete(WISHES_PATH + "/" + source).header("If-Match", "2")
				.header("Idempotency-Key", "search-delete")).andExpect(status().isOk());
		for (String query : List.of("출발 카메라", "도착 여행", "위시 간 이동", "5,000원")) {
			List<Map<String, Object>> matches = items(read(get(HISTORY).queryParam("q", query))).stream()
					.filter(item -> eventId.equals(item.get("eventId"))).toList();
			assertThat(matches).hasSize(1);
			assertThat(matches.getFirst()).containsEntry("amount", 5000).containsEntry("accountAvailableBalanceDelta", 0);
			Map<String, Object> reference = (Map<String, Object>) matches.getFirst().get("sourceWish");
			assertThat(reference).containsEntry("wishPurposeSnapshot", "출발 카메라").containsEntry("deletedWish", true);
		}
		assertThat(items(read(get(HISTORY).queryParam("q", "현재 다른 이름")))).noneMatch(item -> eventId.equals(item.get("eventId")));
	}

	@Test
	void honorsInclusiveExclusiveAndSubMicrosecondBoundsAndEquivalentOffsets() throws Exception {
		refresh(1000000, COMMAND_TIME);
		refresh(1100000, COMMAND_TIME.plusSeconds(1));
		refresh(1200000, COMMAND_TIME.plusSeconds(2));
		List<Map<String, Object>> expected = items(read(get(HISTORY).queryParam("from", "2026-08-18T09:00:00+09:00")
				.queryParam("to", COMMAND_TIME.plusSeconds(1).toString())));
		assertThat(expected).hasSize(1);
		assertThat(expected.getFirst().get("occurredAt")).isEqualTo(COMMAND_TIME.toString());
		assertThat(items(read(get(HISTORY).queryParam("from", COMMAND_TIME.plusNanos(1).toString())
				.queryParam("to", COMMAND_TIME.plusSeconds(1).toString())))).isEmpty();
		assertThat(items(read(get(HISTORY).queryParam("from", COMMAND_TIME.toString())
				.queryParam("to", COMMAND_TIME.plusNanos(1).toString())))).containsExactlyElementsOf(expected);
		assertThat(items(read(get(HISTORY).queryParam("from", COMMAND_TIME.plusSeconds(2).toString())))).hasSize(1);
	}

	@Test
	void traversesTimestampTiesInBothDirectionsAndExcludesLaterBackdatedInsertions() throws Exception {
		refresh(1000000, COMMAND_TIME);
		refresh(1100000, COMMAND_TIME);
		refresh(1200000, COMMAND_TIME);
		List<Map<String, Object>> original = items(read(get(HISTORY).queryParam("sort", "asc")));
		assertThat(original).hasSize(3);
		List<String> ordered = original.stream().map(item -> (String) item.get("eventId")).toList();
		assertThat(ordered).isSorted();
		Map<String, Object> ascending = read(get(HISTORY).queryParam("sort", "asc").queryParam("limit", "1"));
		Map<String, Object> descending = read(get(HISTORY).queryParam("limit", "1"));
		refresh(1300000, COMMAND_TIME.minusSeconds(1));
		refresh(1400000, COMMAND_TIME);
		refresh(1500000, COMMAND_TIME.plusSeconds(1));
		assertThat(traverse(ascending, "asc")).containsExactlyElementsOf(ordered);
		List<String> reverse = new ArrayList<>(ordered);
		java.util.Collections.reverse(reverse);
		assertThat(traverse(descending, "desc")).containsExactlyElementsOf(reverse);
		assertThat(items(read(get(HISTORY)))).hasSize(6);
	}

	@Test
	void bindsCursorToNormalizedFiltersButAllowsPageSizeChangesAndRechecksOwnership() throws Exception {
		refresh(1000000, COMMAND_TIME);
		refresh(1100000, COMMAND_TIME.plusSeconds(1));
		refresh(1200000, COMMAND_TIME.plusSeconds(2));
		String cursor = (String) read(get(HISTORY).queryParam("q", " 카드  잔액 ").queryParam("from", COMMAND_TIME.toString())
				.queryParam("limit", "1")).get("nextCursor");
		assertThat(items(read(get(HISTORY).queryParam("q", "카드 잔액").queryParam("from", "2026-08-18T09:00:00+09:00")
				.queryParam("cursor", cursor).queryParam("limit", "2")))).hasSize(2);
		for (MockHttpServletRequestBuilder request : List.of(
				get(HISTORY).queryParam("q", "카드 잔액"),
				get(HISTORY).queryParam("q", "카드 잔액").queryParam("from", COMMAND_TIME.toString()).queryParam("sort", "asc"),
				get(HISTORY).queryParam("q", "위시").queryParam("from", COMMAND_TIME.toString()),
				get(HISTORY).queryParam("q", "카드 잔액").queryParam("from", COMMAND_TIME.toString()).queryParam("to", COMMAND_TIME.plusSeconds(5).toString()),
				get(HISTORY.replace("fund-movements", "card-balance-changes")))) {
			asOwner(request.queryParam("cursor", cursor)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.fieldErrors[0].field").value("cursor"));
		}
		for (String token : List.of(FRIEND_TOKEN, OTHER_ACADEMY_TOKEN)) {
			asToken(token, get(HISTORY).queryParam("cursor", cursor)).andExpect(status().isNotFound());
		}
		asToken(STAFF_TOKEN, get(HISTORY)).andExpect(status().isForbidden());
		mockMvc.perform(get(HISTORY)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
		jdbc.update("update card_balance_account set closed_at = now() where id = ?", OWNER_ACCOUNT_ID);
		asOwner(get(HISTORY).queryParam("cursor", cursor)).andExpect(status().isNotFound());
	}

	@Test
	void rejectsInvalidParametersWithFieldErrorsAndTreatsWhitespaceAsNoQuery() throws Exception {
		for (String[] parameter : List.of(new String[]{"from", ""}, new String[]{"to", ""},
				new String[]{"from", "2026-08-18T00:00:00"}, new String[]{"from", "2026-02-30T00:00:00Z"},
				new String[]{"sort", ""}, new String[]{"sort", "ASC"}, new String[]{"sort", "latest"},
				new String[]{"q", "😀".repeat(101)}, new String[]{"cursor", ""}, new String[]{"cursor", "broken"})) {
			asOwner(get(HISTORY).queryParam(parameter[0], parameter[1])).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.fieldErrors[0].field").value(parameter[0]));
		}
		asOwner(get(HISTORY).queryParam("from", COMMAND_TIME.toString()).queryParam("to", COMMAND_TIME.toString()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fieldErrors[0].field").value("to"));
		assertThat(items(read(get(HISTORY).queryParam("q", "😀".repeat(100))))).isEmpty();
		refresh(1000000, COMMAND_TIME);
		assertThat(items(read(get(HISTORY).queryParam("q", " \u00a0\t ")))).hasSize(1);
	}

	@Test
	void preservesLegacyDefaultCursorTraversalAndRejectsUnsupportedVersionAndCeiling() throws Exception {
		refresh(1000000, COMMAND_TIME);
		refresh(1100000, COMMAND_TIME.plusSeconds(1));
		refresh(1200000, COMMAND_TIME.plusSeconds(2));
		Map<String, Object> first = read(get(HISTORY).queryParam("limit", "1"));
		Map<String, Object> item = items(first).getFirst();
		String legacy = encode("1|listAccountFundMovements|" + OWNER_ACCOUNT_ID + "||" + item.get("occurredAt") + "|" + item.get("eventId"));
		Map<String, Object> page = read(get(HISTORY).queryParam("cursor", legacy).queryParam("limit", "1"));
		assertThat(items(page)).hasSize(1);
		assertThat(decode((String) page.get("nextCursor"))).startsWith("1|");
		asOwner(get(HISTORY).queryParam("cursor", legacy).queryParam("sort", "asc")).andExpect(status().isBadRequest());
		String v2 = decode((String) first.get("nextCursor"));
		for (String invalid : List.of(v2.replaceFirst("^2", "9"), v2.substring(0, v2.lastIndexOf('|') + 1) + "-1",
				v2.replace(OWNER_ACCOUNT_ID.toString(), "00000000-0000-0000-0000-000000000999"))) {
			asOwner(get(HISTORY).queryParam("cursor", encode(invalid))).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.fieldErrors[0].field").value("cursor"));
		}
	}

	private List<String> traverse(Map<String, Object> first, String sort) throws Exception {
		List<String> ids = new ArrayList<>();
		Map<String, Object> page = first;
		do {
			ids.addAll(items(page).stream().map(item -> (String) item.get("eventId")).toList());
			String cursor = (String) page.get("nextCursor");
			if (cursor == null) break;
			page = read(get(HISTORY).queryParam("sort", sort).queryParam("cursor", cursor).queryParam("limit", "2"));
		} while (ids.size() < 20);
		return ids;
	}

	private String deposit(String wish, long amount, long version, String key) throws Exception {
		return json(asOwner(post(WISHES_PATH + "/" + wish + "/deposits").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":%d,\"expectedVersion\":%d}".formatted(amount, version)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.eventId");
	}

	private void refresh(long balance, Instant time) throws Exception {
		clock.set(time);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":" + balance + "}]");
		asOwner(post("/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/balance-refreshes")).andExpect(status().isOk());
	}

	private Map<String, Object> read(MockHttpServletRequestBuilder request) throws Exception {
		return json(asOwner(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
	}
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> items(Map<String, Object> page) {
		return (List<Map<String, Object>>) page.get("items");
	}
	private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
	private static String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
}
