package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class WishTerminalTransitionIT extends WishApiIntegrationSupport {

	@Test
	void completesOnlyAnAmountReachedWishAtTheInjectedTimeAndReplaysOneReturnEvent()
			throws Exception {
		MvcResult original = asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "complete-camp")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andExpect(jsonPath("$.wish.state").value("COMPLETED"))
				.andExpect(jsonPath("$.wish.amount").value(0))
				.andExpect(jsonPath("$.wish.completedAt").value(COMMAND_TIME.toString()))
				.andExpect(jsonPath("$.wish.actualDurationSeconds")
						.value(Duration.between(
								Instant.parse("2026-08-16T00:00:00Z"), COMMAND_TIME).toSeconds()))
				.andExpect(jsonPath("$.wish.version").value(1))
				.andExpect(jsonPath("$.eventId").isString())
				.andReturn();

		MvcResult replay = asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "complete-camp")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn();

		assertThat(replay.getResponse().getContentAsString())
				.isEqualTo(original.getResponse().getContentAsString());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE event_type = 'WISH_COMPLETION_RETURN'",
				Long.class)).isOne();
		assertThat(jdbc.queryForObject(
				"SELECT wish_delta FROM ledger_wish_effect WHERE wish_id = ?",
				Long.class, CAMP_WISH_ID)).isEqualTo(-500_000L);
	}

	@Test
	void rejectsExplicitCompletionFromInProgressWithoutChangingWishOrLedger() throws Exception {
		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/completion")
				.header("Idempotency-Key", "complete-too-early")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));

		assertThat(jdbc.queryForMap(
				"SELECT state, wish_amount, version FROM wish WHERE id = ?", LAPTOP_WISH_ID))
				.containsEntry("state", "IN_PROGRESS")
				.containsEntry("wish_amount", 250_000L)
				.containsEntry("version", 0L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_event", Long.class)).isZero();
	}

	@Test
	void abandonsAZeroFundedWishWithoutInventingALedgerEvent() throws Exception {
		String wishId = createWish("create-empty", "empty wish", 1000);
		clock.set(COMMAND_TIME.plusSeconds(5));

		asOwner(post(WISHES_PATH + "/" + wishId + "/abandonment")
				.header("Idempotency-Key", "abandon-empty")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("ABANDONED"))
				.andExpect(jsonPath("$.wish.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.wish.updatedAt")
						.value(COMMAND_TIME.plusSeconds(5).toString()))
				.andExpect(jsonPath("$.eventId").value((Object) null));

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_wish_effect WHERE wish_id = ?::uuid",
				Long.class, wishId)).isZero();
	}

	@Test
	void tombstonesOncePreservesHistoryAndHidesAllLaterReads() throws Exception {
		MvcResult original = asOwner(delete(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.header(HttpHeaders.IF_MATCH, "0")
				.header("Idempotency-Key", "delete-laptop"))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andExpect(jsonPath("$.wish.state").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.wish.amount").value(0))
				.andExpect(jsonPath("$.eventId").isString())
				.andReturn();

		asOwner(get(WISHES_PATH + "/" + LAPTOP_WISH_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("WISH_NOT_FOUND"));

		MvcResult replay = asOwner(delete(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.header(HttpHeaders.IF_MATCH, "0")
				.header("Idempotency-Key", "delete-laptop"))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn();

		assertThat(replay.getResponse().getContentAsString())
				.isEqualTo(original.getResponse().getContentAsString());
		assertThat(jdbc.queryForMap(
				"SELECT state, wish_amount, deleted_purpose_snapshot, version "
						+ "FROM wish WHERE id = ?", LAPTOP_WISH_ID))
				.containsEntry("state", "IN_PROGRESS")
				.containsEntry("wish_amount", 0L)
				.containsEntry("deleted_purpose_snapshot", "노트북")
				.containsEntry("version", 1L);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE event_type = 'WISH_DELETION_RETURN'",
				Long.class)).isOne();
	}
}
