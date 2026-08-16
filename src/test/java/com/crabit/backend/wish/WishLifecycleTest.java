package com.crabit.backend.wish;

import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.api.WishApiIntegrationSupport;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class WishLifecycleTest extends WishApiIntegrationSupport {

	@Test
	void createsAPrivateZeroFundedWishAndPermanentlyReplaysTheOriginalResponse() throws Exception {
		String request = """
				{"purpose":"\u00a0Cafe\u0301 laptop\u00a0","targetAmount":9007199254740991,"targetDate":"2026-12-31"}
				""";

		MvcResult original = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "create-laptop")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andExpect(jsonPath("$.wish.purpose").value("Café laptop"))
				.andExpect(jsonPath("$.wish.amount").value(0))
				.andExpect(jsonPath("$.wish.state").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.wish.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.wish.createdAt").value(COMMAND_TIME.toString()))
				.andExpect(jsonPath("$.wish.updatedAt").value(COMMAND_TIME.toString()))
				.andExpect(jsonPath("$.wish.version").value(0))
				.andReturn();

		MvcResult replay = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "create-laptop")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn();

		assertThat(replay.getResponse().getContentAsString())
				.isEqualTo(original.getResponse().getContentAsString());
		asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "create-laptop")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"purpose\":\"different\",\"targetAmount\":1000}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM wish WHERE purpose = 'Café laptop'", Long.class)).isOne();
		assertThat(jdbc.queryForObject(
				"SELECT jsonb_exists(wish_idempotency_records, 'create-laptop') "
						+ "FROM student WHERE id = ?",
				Boolean.class, OWNER_ID)).isTrue();
	}

	@Test
	void serializesConcurrentIdenticalCreatesIntoOneMutationAndOneReplay() throws Exception {
		String request = "{\"purpose\":\"concurrent wish\",\"targetAmount\":1000}";
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<MvcResult> first = executor.submit(() -> {
				start.await();
				return asOwner(post(WISHES_PATH)
						.header("Idempotency-Key", "concurrent-create")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request)).andReturn();
			});
			Future<MvcResult> second = executor.submit(() -> {
				start.await();
				return asOwner(post(WISHES_PATH)
						.header("Idempotency-Key", "concurrent-create")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request)).andReturn();
			});
			start.countDown();

			MvcResult firstResponse = first.get(10, TimeUnit.SECONDS);
			MvcResult secondResponse = second.get(10, TimeUnit.SECONDS);
			assertThat(List.of(
					firstResponse.getResponse().getStatus(),
					secondResponse.getResponse().getStatus())).containsOnly(201);
			assertThat(List.of(
					firstResponse.getResponse().getHeader("Idempotency-Replayed"),
					secondResponse.getResponse().getHeader("Idempotency-Replayed")))
					.containsExactlyInAnyOrder("false", "true");
			assertThat(secondResponse.getResponse().getContentAsString())
					.isEqualTo(firstResponse.getResponse().getContentAsString());
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM wish WHERE purpose = 'concurrent wish'", Long.class)).isOne();
	}

	@Test
	void appliesAllMergePatchFieldsAtomicallyWithOneVersionAndTimestampChange() throws Exception {
		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("""
						{
						  "expectedVersion":0,
						  "purpose":"\u00a0Cafe\u0301 laptop\u00a0",
						  "targetAmount":300000,
						  "targetDate":null,
						  "visibility":"PRIVATE"
						}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.purpose").value("Café laptop"))
				.andExpect(jsonPath("$.wish.targetAmount").value(300000))
				.andExpect(jsonPath("$.wish.targetDate").value((Object) null))
				.andExpect(jsonPath("$.wish.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.wish.updatedAt").value(COMMAND_TIME.toString()))
				.andExpect(jsonPath("$.wish.version").value(1));

		assertThat(jdbc.queryForMap(
				"SELECT purpose, target_amount, target_date, visibility, updated_at, version "
						+ "FROM wish WHERE id = ?", LAPTOP_WISH_ID))
				.containsEntry("purpose", "Café laptop")
				.containsEntry("target_amount", 300000L)
				.containsEntry("target_date", null)
				.containsEntry("visibility", "PRIVATE")
				.containsEntry("updated_at", Timestamp.from(COMMAND_TIME))
				.containsEntry("version", 1L);
	}

	@Test
	void rollsBackEveryPatchFieldWhenOneSuppliedValueViolatesTheWishInvariant() throws Exception {
		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("""
						{
						  "expectedVersion":0,
						  "purpose":"must roll back",
						  "targetAmount":100000,
						  "targetDate":"2027-01-01"
						}
						"""))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error.code").value("INVALID_AMOUNT"));

		assertThat(jdbc.queryForMap(
				"SELECT purpose, target_amount, target_date, version FROM wish WHERE id = ?",
				LAPTOP_WISH_ID))
				.containsEntry("purpose", "노트북")
				.containsEntry("target_amount", 1_500_000L)
				.containsEntry("target_date", Date.valueOf(LocalDate.of(2026, 12, 31)))
				.containsEntry("version", 0L);
	}
}
