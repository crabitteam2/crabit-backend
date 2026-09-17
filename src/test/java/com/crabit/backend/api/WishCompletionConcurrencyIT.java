package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

class WishCompletionConcurrencyIT extends WishApiIntegrationSupport {
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void concurrentIdenticalCompletionMutatesOnce(boolean zero) throws Exception {
		String wishId = zero ? createWish("zero-concurrent-create", "Zero", 1000) : LAPTOP_WISH_ID.toString();
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			java.util.concurrent.Callable<MockHttpServletResponse> command = () -> {
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return asOwner(post(WISHES_PATH + "/" + wishId + "/completion")
						.header("Idempotency-Key", "concurrent-completion")
						.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
						.andReturn().getResponse();
			};
			var first = executor.submit(command);
			var second = executor.submit(command);
			start.countDown();
			var a = first.get(30, TimeUnit.SECONDS);
			var b = second.get(30, TimeUnit.SECONDS);
			assertThat(a.getStatus()).isEqualTo(200);
			assertThat(b.getStatus()).isEqualTo(200);
			assertThat(a.getContentAsString()).isEqualTo(b.getContentAsString());
			assertThat(List.of(a.getHeader("Idempotency-Replayed"), b.getHeader("Idempotency-Replayed")))
					.containsExactlyInAnyOrder("false", "true");
		}
		assertThat(jdbc.queryForMap("SELECT state, wish_amount, version FROM wish WHERE id = ?::uuid", wishId))
				.containsEntry("state", "COMPLETED").containsEntry("wish_amount", 0L).containsEntry("version", 1L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_event WHERE event_type = 'WISH_COMPLETION_RETURN'",
				Long.class)).isEqualTo(zero ? 0L : 1L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_wish_effect WHERE wish_id = ?::uuid",
				Long.class, wishId)).isEqualTo(zero ? 0L : 1L);
	}
}
