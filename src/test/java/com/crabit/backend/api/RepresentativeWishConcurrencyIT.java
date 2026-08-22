package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class RepresentativeWishConcurrencyIT extends WishApiIntegrationSupport {

	private static final String REPRESENTATIVE_PATH =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/representative-wish";

	@Test
	void serializesConcurrentSelectionsAndCommitsExactlyOneFinalRepresentative()
			throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<String> laptop = executor.submit(() -> selectAfter(start, LAPTOP_WISH_ID));
			Future<String> camp = executor.submit(() -> selectAfter(start, CAMP_WISH_ID));
			start.countDown();

			assertThat(Set.of(laptop.get(), camp.get()))
					.isEqualTo(Set.of(LAPTOP_WISH_ID.toString(), CAMP_WISH_ID.toString()));
		}

		String selected = jdbc.queryForObject(
				"SELECT wish_id FROM representative_wish_selection WHERE account_id = ?",
				String.class, OWNER_ACCOUNT_ID);
		assertThat(selected).isIn(LAPTOP_WISH_ID.toString(), CAMP_WISH_ID.toString());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM representative_wish_selection WHERE account_id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isOne();
	}

	@Test
	void selectionRacingRepresentativeCompletionCannotLeaveADanglingSelection()
			throws Exception {
		select(CAMP_WISH_ID);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<MvcResult> selection = executor.submit(() -> {
				start.await();
				return select(LAPTOP_WISH_ID);
			});
			Future<MvcResult> completion = executor.submit(() -> {
				start.await();
				return mockMvc.perform(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + OWNER_TOKEN)
						.header("Idempotency-Key", "representative-race-completion")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":0}"))
						.andReturn();
			});
			start.countDown();

			assertThat(List.of(
					selection.get().getResponse().getStatus(),
					completion.get().getResponse().getStatus()))
					.containsOnly(200);
		}

		assertThat(jdbc.queryForObject(
				"SELECT wish_id FROM representative_wish_selection WHERE account_id = ?",
				String.class, OWNER_ACCOUNT_ID)).isEqualTo(LAPTOP_WISH_ID.toString());
	}

	private String selectAfter(CountDownLatch start, UUID wishId) throws Exception {
		start.await();
		MvcResult result = select(wishId);
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

	private MvcResult select(UUID wishId) throws Exception {
		return mockMvc.perform(put(REPRESENTATIVE_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + OWNER_TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"wishId\":\"" + wishId + "\"}"))
				.andReturn();
	}
}
