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
	void completesAnAmountReachedWishAtTheInjectedTimeAndReplaysOneReturnEvent()
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
					.andExpect(jsonPath("$.wish.closedAt").value(COMMAND_TIME.toString()))
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
	void completesUnderTargetReturnsExactAllocationAndReconcilesRepresentative() throws Exception {
		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/completion")
				.header("Idempotency-Key", "complete-under-target")
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.wish.state").value("COMPLETED"))
				.andExpect(jsonPath("$.wish.amount").value(0)).andExpect(jsonPath("$.wish.version").value(1));
		assertThat(jdbc.queryForMap("SELECT state, wish_amount, version FROM wish WHERE id = ?", LAPTOP_WISH_ID))
				.containsEntry("state", "COMPLETED").containsEntry("wish_amount", 0L).containsEntry("version", 1L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_event", Long.class)).isOne();
		assertThat(jdbc.queryForObject("SELECT wish_delta FROM ledger_wish_effect WHERE wish_id = ?",
				Long.class, LAPTOP_WISH_ID)).isEqualTo(-250_000L);
		assertThat(jdbc.queryForObject("SELECT wish_id::text FROM representative_wish_selection WHERE account_id = ?",
				String.class, com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID)).isEqualTo(CAMP_WISH_ID.toString());
		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/completion")
				.header("Idempotency-Key", "fresh-terminal").contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":1}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
	}

	@Test
	void zeroCompletionReplaysWithoutEventsEffectsOrLedgerSequenceChange() throws Exception {
		String wishId = createWish("create-zero-complete", "empty wish", 1000);
		var sequenceBefore = jdbc.queryForMap("SELECT last_value, is_called FROM ledger_event_application_order_seq");
		String path = WISHES_PATH + "/" + wishId + "/completion";
		String first = asOwner(post(path).header("Idempotency-Key", "zero-complete")
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.wish.state").value("COMPLETED"))
				.andExpect(jsonPath("$.wish.version").value(1)).andExpect(jsonPath("$.eventId").value((Object) null))
				.andReturn().getResponse().getContentAsString();
		asOwner(post(path).header("Idempotency-Key", "zero-complete")
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk()).andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(first));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_event", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_wish_effect", Long.class)).isZero();
		assertThat(jdbc.queryForMap("SELECT last_value, is_called FROM ledger_event_application_order_seq")).isEqualTo(sequenceBefore);
		asOwner(post(path).header("Idempotency-Key", "zero-complete")
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void staleUnderTargetCompletionDoesNotMutateWishOrLedger() throws Exception {
		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/completion")
				.header("Idempotency-Key", "stale-complete").contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":1}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
		assertThat(jdbc.queryForMap("SELECT state, wish_amount, version FROM wish WHERE id = ?", LAPTOP_WISH_ID))
				.containsEntry("state", "IN_PROGRESS").containsEntry("wish_amount", 250_000L).containsEntry("version", 0L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_event", Long.class)).isZero();
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
	void freshCompletionRejectsAbandonedAndDeletedWishesWithoutEffects(boolean deleted) throws Exception {
		String wishId = createWish("terminal-create", "Terminal", 1000);
		String path = WISHES_PATH + "/" + wishId;
		if (deleted) {
			asOwner(delete(path).header(HttpHeaders.IF_MATCH, "0").header("Idempotency-Key", "terminal-delete"))
					.andExpect(status().isOk());
		} else {
			asOwner(post(path + "/abandonment").header("Idempotency-Key", "terminal-abandon")
					.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
					.andExpect(status().isOk());
		}
		var before = jdbc.queryForMap("SELECT * FROM wish WHERE id = ?::uuid", wishId);
		asOwner(post(path + "/completion").header("Idempotency-Key", "terminal-complete")
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
				.andExpect(deleted ? status().isNotFound() : status().isConflict())
				.andExpect(jsonPath("$.error.code").value(deleted ? "WISH_NOT_FOUND" : "INVALID_STATE_TRANSITION"));
		assertThat(jdbc.queryForMap("SELECT * FROM wish WHERE id = ?::uuid", wishId)).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_event", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_wish_effect", Long.class)).isZero();
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
					.andExpect(jsonPath("$.wish.completedAt").value((Object) null))
					.andExpect(jsonPath("$.wish.closedAt")
							.value(COMMAND_TIME.plusSeconds(5).toString()))
					.andExpect(jsonPath("$.eventId").value((Object) null));

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_wish_effect WHERE wish_id = ?::uuid",
				Long.class, wishId)).isZero();
		assertThat(jdbc.queryForMap(
				"SELECT abandoned_at, updated_at FROM wish WHERE id = ?::uuid", wishId))
				.containsEntry("abandoned_at", java.sql.Timestamp.from(COMMAND_TIME.plusSeconds(5)))
				.containsEntry("updated_at", java.sql.Timestamp.from(COMMAND_TIME.plusSeconds(5)));
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
