package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class WishFundMovementConcurrencyIT extends WishApiIntegrationSupport {

	private static final String TRANSFERS =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/transfers";

	@Test
	void concurrentIdenticalHttpRequestsExecuteOnceAndReplayOnceWithOneLedgerFact()
			throws Exception {
		String destinationId = createWish("concurrent-identical-destination", "동시 목표", 100_000);
		String body = transferBody(destinationId);

		List<HttpResult> results = runTogether(
				() -> transfer("concurrent-identical", body),
				() -> transfer("concurrent-identical", body));

		assertThat(results).extracting(HttpResult::status).containsExactly(200, 200);
		assertThat(results).extracting(HttpResult::replayed)
				.containsExactlyInAnyOrder("false", "true");
		assertThat(results).extracting(HttpResult::body).containsOnly(results.getFirst().body());
		assertSingleTransfer(destinationId);
	}

	@Test
	void concurrentDifferentKeysAtOneOptimisticVersionYieldOneSuccessAndOneVersionConflict()
			throws Exception {
		String destinationId = createWish("concurrent-version-destination", "버전 목표", 100_000);
		String body = transferBody(destinationId);

		List<HttpResult> results = runTogether(
				() -> transfer("concurrent-version-a", body),
				() -> transfer("concurrent-version-b", body));

		assertThat(results).extracting(HttpResult::status)
				.containsExactlyInAnyOrder(200, 409);
		HttpResult conflict = results.stream()
				.filter(result -> result.status() == 409)
				.findFirst()
				.orElseThrow();
		assertThat(WishApiIntegrationSupport.<String>json(
				conflict.body(), "$.error.code")).isEqualTo("VERSION_CONFLICT");
		assertSingleTransfer(destinationId);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM student,
				LATERAL jsonb_object_keys(wish_idempotency_records) AS keys(key)
				WHERE id = (SELECT student_id FROM card_balance_account WHERE id = ?)
				AND key IN ('concurrent-version-a', 'concurrent-version-b')
				""", Long.class, OWNER_ACCOUNT_ID)).isOne();
	}

	private HttpResult transfer(String key, String body) throws Exception {
		MvcResult result = asOwner(post(TRANSFERS)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andReturn();
		return new HttpResult(
				result.getResponse().getStatus(),
				result.getResponse().getHeader("Idempotency-Replayed"),
				result.getResponse().getContentAsString());
	}

	private List<HttpResult> runTogether(
			Callable<HttpResult> first, Callable<HttpResult> second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<HttpResult> firstResult = executor.submit(gated(first, ready, start));
			Future<HttpResult> secondResult = executor.submit(gated(second, ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			return List.of(
					firstResult.get(20, TimeUnit.SECONDS),
					secondResult.get(20, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	private static Callable<HttpResult> gated(
			Callable<HttpResult> request, CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Concurrent HTTP start was not released");
			}
			return request.call();
		};
	}

	private static String transferBody(String destinationId) {
		return """
				{"sourceWishId":"%s","destinationWishId":"%s","amount":10000,
				"sourceExpectedVersion":0,"destinationExpectedVersion":0}
				""".formatted(LAPTOP_WISH_ID, destinationId);
	}

	private void assertSingleTransfer(String destinationId) {
		assertThat(jdbc.queryForMap(
				"SELECT wish_amount, version FROM wish WHERE id = ?", LAPTOP_WISH_ID))
				.containsEntry("wish_amount", 240_000L)
				.containsEntry("version", 1L);
		assertThat(jdbc.queryForMap(
				"SELECT wish_amount, version FROM wish WHERE id = ?::uuid", destinationId))
				.containsEntry("wish_amount", 10_000L)
				.containsEntry("version", 1L);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ledger_event
				WHERE account_id = ? AND event_type = 'WISH_TRANSFER'
				""", Long.class, OWNER_ACCOUNT_ID)).isOne();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ledger_wish_effect effect
				JOIN ledger_event event ON event.id = effect.event_id
				WHERE effect.account_id = ? AND event.event_type = 'WISH_TRANSFER'
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(2L);
	}

	private record HttpResult(int status, String replayed, String body) {
	}
}
