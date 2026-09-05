package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_STUDENT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class RelationshipVisibilityMatrixIT extends SharedCardApiIntegrationSupport {

	@Test
	void currentStudentFollowMembershipAndReverseBlockApplyImmediately() throws Exception {
		String friendsCard = cardIdForWish(LAPTOP_WISH_ID);

		jdbc.update("UPDATE student_follow SET ended_at = ? WHERE academy_id = ?",
				Timestamp.from(COMMAND_TIME), PRIMARY_ACADEMY_ID);
		listAs(FRIEND_TOKEN)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].purpose").value("여름 캠프"));
		getAs(FRIEND_TOKEN, friendsCard)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHARED_CARD_NOT_FOUND"));

		jdbc.update("UPDATE student_follow SET ended_at = NULL WHERE academy_id = ?", PRIMARY_ACADEMY_ID);
		jdbc.update("""
				INSERT INTO student_block (id, blocker_id, blocked_id, blocked_at, released_at)
				VALUES (?, ?, ?, ?, NULL)
				""", REVERSE_BLOCK_ID, FRIEND_ID, OWNER_ID, Timestamp.from(COMMAND_TIME));
		listAs(FRIEND_TOKEN)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());

		jdbc.update("DELETE FROM student_block WHERE id = ?", REVERSE_BLOCK_ID);
		jdbc.update("UPDATE academy_membership SET left_at = ? WHERE student_id = ? AND academy_id = ?",
				Timestamp.from(COMMAND_TIME), OWNER_ID, PRIMARY_ACADEMY_ID);
		listAs(FRIEND_TOKEN)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
		getAs(OWNER_TOKEN, friendsCard)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));
	}

	@Test
	void hiddenRowsAreFilteredBeforeLimitAndKeysetCursorSelection() throws Exception {
		Instant newest = Instant.parse("2026-08-18T03:00:00Z");
		Instant hidden = Instant.parse("2026-08-18T02:00:00Z");
		Instant oldest = Instant.parse("2026-08-18T01:00:00Z");
		jdbc.update("UPDATE shared_card SET updated_at = ? WHERE wish_id = ?",
				Timestamp.from(newest), CAMP_WISH_ID);
		jdbc.update("UPDATE shared_card SET updated_at = ? WHERE wish_id = ?",
				Timestamp.from(oldest), LAPTOP_WISH_ID);
		insertHiddenFriendsCard(hidden);

		String first = asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].sharedCardId").value(cardIdForWish(CAMP_WISH_ID)))
				.andExpect(jsonPath("$.nextCursor").isString())
				.andReturn().getResponse().getContentAsString();
		String cursor = json(first, "$.nextCursor");

		asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH)
				.param("limit", "1").param("cursor", cursor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].sharedCardId").value(cardIdForWish(LAPTOP_WISH_ID)))
				.andExpect(jsonPath("$.nextCursor").value((Object) null));
	}

	@Test
	void newStudentFollowAndAcademyMembershipGrantCurrentAccessImmediately() throws Exception {
		String friendsCard = cardIdForWish(LAPTOP_WISH_ID);
		String academyCard = cardIdForWish(CAMP_WISH_ID);

		getAs(NONFRIEND_TOKEN, friendsCard)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHARED_CARD_NOT_FOUND"));
		jdbc.update("""
				INSERT INTO student_follow
				    (id, academy_id, source_id, target_id, started_at, ended_at)
				VALUES (?, ?, ?, ?, ?, NULL)
				""", GRANTED_FOLLOW_ID, PRIMARY_ACADEMY_ID, NONFRIEND_ID, OWNER_ID,
				Timestamp.from(COMMAND_TIME));
		getAs(NONFRIEND_TOKEN, friendsCard)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(friendsCard))
				.andExpect(jsonPath("$.kind").value("PROGRESS"));

		listAs(OTHER_ACADEMY_TOKEN)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));
		jdbc.update("""
				INSERT INTO academy_membership (id, student_id, academy_id, joined_at, left_at)
				VALUES (?, ?, ?, ?, NULL)
				""", GRANTED_PRIMARY_MEMBERSHIP_ID, OTHER_ACADEMY_STUDENT_ID, PRIMARY_ACADEMY_ID,
				Timestamp.from(COMMAND_TIME));
		getAs(OTHER_ACADEMY_TOKEN, academyCard)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(academyCard))
				.andExpect(jsonPath("$.kind").value("PROGRESS"));
	}

	@Test
	void currentStudentFollowRevocationHidesHistoricalFriendsCompletion() throws Exception {
		String cardId = cardIdForWish(CAMP_WISH_ID);
		asOwner(patch(WISHES_PATH + "/" + CAMP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":0,\"visibility\":\"FOLLOWERS\"}"))
				.andExpect(status().isOk());
		asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "complete-friends-history")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":1}"))
				.andExpect(status().isOk());

		assertThat(cardIdForWish(CAMP_WISH_ID)).isEqualTo(cardId);
		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("COMPLETION"));

		jdbc.update("UPDATE student_follow SET ended_at = ? WHERE academy_id = ?",
				Timestamp.from(COMMAND_TIME.plusSeconds(1)), PRIMARY_ACADEMY_ID);
		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHARED_CARD_NOT_FOUND"));
	}

	@Test
	void ownerAcademyDepartureHidesHistoricalAcademyCompletionFromCurrentMembers()
			throws Exception {
		String cardId = cardIdForWish(CAMP_WISH_ID);
		completeCamp("complete-academy-history").andExpect(status().isOk());
		getAs(NONFRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("COMPLETION"));

		jdbc.update("UPDATE academy_membership SET left_at = ? WHERE student_id = ? AND academy_id = ?",
				Timestamp.from(COMMAND_TIME.plusSeconds(1)), OWNER_ID, PRIMARY_ACADEMY_ID);
		getAs(NONFRIEND_TOKEN, cardId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHARED_CARD_NOT_FOUND"));
	}

	@Test
	void otherStudentFollowContinuationRechecksOwnerAccessWithoutHidingBlockedThirdPartyRows()
			throws Exception {
		jdbc.update("""
				INSERT INTO student_follow (id, academy_id, source_id, target_id, started_at)
				VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)
				""",
				java.util.UUID.randomUUID(), PRIMARY_ACADEMY_ID, OWNER_ID, FRIEND_ID,
				Timestamp.from(COMMAND_TIME.plusSeconds(2)),
				java.util.UUID.randomUUID(), PRIMARY_ACADEMY_ID, OWNER_ID, NONFRIEND_ID,
				Timestamp.from(COMMAND_TIME.plusSeconds(1)));
		String path = "/v1/academies/" + PRIMARY_ACADEMY_ID + "/students/" + OWNER_ID + "/following";
		String first = asToken(FRIEND_TOKEN, get(path).param("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].studentId").value(FRIEND_ID.toString()))
				.andReturn().getResponse().getContentAsString();
		String cursor = json(first, "$.nextCursor");

		asToken(FRIEND_TOKEN, post("/v1/me/student-blocks")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + NONFRIEND_ID + "\"}"))
				.andExpect(status().isCreated());
		asToken(FRIEND_TOKEN, get(path).param("cursor", cursor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].studentId").value(NONFRIEND_ID.toString()));

		asToken(FRIEND_TOKEN, post("/v1/me/student-blocks")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + OWNER_ID + "\"}"))
				.andExpect(status().isCreated());
		asToken(FRIEND_TOKEN, get(path).param("cursor", cursor))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
	}

	@Test
	void abandonmentCardsKeepTheExistingFollowAndBlockVisibilityBoundary() throws Exception {
		String cardId = cardIdForWish(LAPTOP_WISH_ID);
		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/abandonment")
				.header("Idempotency-Key", "relationship-abandonment")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk());

		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("ABANDONMENT"));
		getAs(NONFRIEND_TOKEN, cardId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHARED_CARD_NOT_FOUND"));

		jdbc.update("""
				INSERT INTO student_block (id, blocker_id, blocked_id, blocked_at, released_at)
				VALUES (?, ?, ?, ?, NULL)
				""", REVERSE_BLOCK_ID, FRIEND_ID, OWNER_ID, Timestamp.from(COMMAND_TIME));
		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHARED_CARD_NOT_FOUND"));
	}
}
