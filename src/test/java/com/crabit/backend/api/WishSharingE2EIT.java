package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Aggregates current-relationship visibility and privacy-safe card projections. */
class WishSharingE2EIT extends RelationshipVisibilityMatrixIT {

	@Override
	@Test
	void currentStudentFollowMembershipAndReverseBlockApplyImmediately() throws Exception {
		super.currentStudentFollowMembershipAndReverseBlockApplyImmediately();
	}

	@Override
	@Test
	void hiddenRowsAreFilteredBeforeLimitAndKeysetCursorSelection() throws Exception {
		super.hiddenRowsAreFilteredBeforeLimitAndKeysetCursorSelection();
	}

	@Override
	@Test
	void newStudentFollowAndAcademyMembershipGrantCurrentAccessImmediately() throws Exception {
		super.newStudentFollowAndAcademyMembershipGrantCurrentAccessImmediately();
	}

	@Override
	@Test
	void currentStudentFollowRevocationHidesHistoricalFriendsCompletion() throws Exception {
		super.currentStudentFollowRevocationHidesHistoricalFriendsCompletion();
	}

	@Override
	@Test
	void ownerAcademyDepartureHidesHistoricalAcademyCompletionFromCurrentMembers()
			throws Exception {
		super.ownerAcademyDepartureHidesHistoricalAcademyCompletionFromCurrentMembers();
	}

	@Test
	void completedVisibilityChangesAtInjectedTimeAndCardNeverAutoExpires()
			throws Exception {
		String cardId = cardIdForWish(CAMP_WISH_ID);
		completeCamp("normative-visibility-completion")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("COMPLETED"))
				.andExpect(jsonPath("$.wish.visibility").value("ACADEMY"))
				.andExpect(jsonPath("$.wish.version").value(1));

		getAs(NONFRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.kind").value("COMPLETION"));

		Instant oneYearLater = COMMAND_TIME.plus(Duration.ofDays(365));
		clock.set(oneYearLater);
		getAs(NONFRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.kind").value("COMPLETION"));

		asOwner(patch(WISHES_PATH + "/" + CAMP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":1,\"visibility\":\"FOLLOWERS\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("COMPLETED"))
				.andExpect(jsonPath("$.wish.visibility").value("FOLLOWERS"))
				.andExpect(jsonPath("$.wish.version").value(2));

		getAs(NONFRIEND_TOKEN, cardId).andExpect(status().isNotFound());
		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.kind").value("COMPLETION"));

		clock.set(oneYearLater.plus(Duration.ofDays(365)));
		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.kind").value("COMPLETION"));
		getAs(NONFRIEND_TOKEN, cardId).andExpect(status().isNotFound());

		assertThat(jdbc.queryForMap("""
				SELECT id::text AS id, kind, visibility, updated_at
				FROM shared_card WHERE wish_id = ?
				""", CAMP_WISH_ID))
				.containsEntry("id", cardId)
				.containsEntry("kind", "COMPLETION")
				.containsEntry("visibility", "FOLLOWERS")
				.containsEntry("updated_at", Timestamp.from(oneYearLater));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM shared_card WHERE wish_id = ?", Long.class, CAMP_WISH_ID))
				.isOne();
	}

	@Test
	void oneProgressCardTracksZeroReachedWithdrawnAndEditedPublicStates()
			throws Exception {
		String wishId = createWish("sharing-progress-create", "공개 목표", 100_000);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM shared_card WHERE wish_id = ?::uuid", Long.class, wishId))
				.isZero();

		asOwner(patch(WISHES_PATH + "/" + wishId)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":0,\"visibility\":\"FOLLOWERS\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.version").value(1));
		String cardId = cardIdForWish(java.util.UUID.fromString(wishId));
		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("PROGRESS"))
				.andExpect(jsonPath("$.progressPercent").value(0));

		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		asOwner(post(WISHES_PATH + "/" + wishId + "/deposits")
				.header("Idempotency-Key", "sharing-progress-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":100000,\"expectedVersion\":1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("AMOUNT_REACHED"))
				.andExpect(jsonPath("$.wish.version").value(2));
		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.kind").value("PROGRESS"))
				.andExpect(jsonPath("$.progressPercent").value(100));

		asOwner(post(WISHES_PATH + "/" + wishId + "/withdrawals")
				.header("Idempotency-Key", "sharing-progress-withdrawal")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":25000,\"expectedVersion\":2}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.wish.version").value(3));
		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.progressPercent").value(75));

		asOwner(patch(WISHES_PATH + "/" + wishId)
				.contentType("application/merge-patch+json")
				.content("""
						{"expectedVersion":3,"purpose":"공개 변경","targetAmount":150000,
						 "targetDate":"2027-03-01"}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.version").value(4));
		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.purpose").value("공개 변경"))
				.andExpect(jsonPath("$.targetAmount").value(150_000))
				.andExpect(jsonPath("$.progressPercent").value(50));

		asOwner(patch(WISHES_PATH + "/" + wishId)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":4,\"visibility\":\"PRIVATE\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.wish.version").value(5));
		getAs(FRIEND_TOKEN, cardId).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM shared_card WHERE wish_id = ?::uuid", Long.class, wishId))
				.isZero();
	}

	@Test
	void abandonmentReplacesTheProgressCardAndDeletionRemovesItsOwnCard()
			throws Exception {
		String abandonedCardId = cardIdForWish(LAPTOP_WISH_ID);
		String deletedWishId = createWish("sharing-delete-create", "삭제 공개", 100_000);
		asOwner(patch(WISHES_PATH + "/" + deletedWishId)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":0,\"visibility\":\"FOLLOWERS\"}"))
				.andExpect(status().isOk());
		String deletedCardId = cardIdForWish(java.util.UUID.fromString(deletedWishId));

		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/abandonment")
				.header("Idempotency-Key", "sharing-abandon")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("ABANDONED"))
				.andExpect(jsonPath("$.wish.visibility").value("FOLLOWERS"));
		asOwner(delete(WISHES_PATH + "/" + deletedWishId)
				.header(HttpHeaders.IF_MATCH, "1")
				.header("Idempotency-Key", "sharing-delete"))
				.andExpect(status().isOk());

		getAs(FRIEND_TOKEN, abandonedCardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(abandonedCardId))
				.andExpect(jsonPath("$.kind").value("ABANDONMENT"))
				.andExpect(jsonPath("$.state").value("ABANDONED"))
				.andExpect(jsonPath("$.progressPercent").value(16))
				.andExpect(jsonPath("$.abandonmentAmount").doesNotExist())
				.andExpect(jsonPath("$.amount").doesNotExist());
		getAs(FRIEND_TOKEN, deletedCardId).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM shared_card WHERE wish_id IN (?, ?::uuid)
				""", Long.class, LAPTOP_WISH_ID, deletedWishId)).isOne();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM shared_card WHERE wish_id = ? AND kind = 'ABANDONMENT'
				""", Long.class, LAPTOP_WISH_ID)).isOne();
	}

	@Test
	void progressCompletionAndAbandonmentCardsExposeOnlyTheirClosedPrivacySafeShapes()
			throws Exception {
		String progressId = cardIdForWish(LAPTOP_WISH_ID);
		Map<String, Object> progress = json(getAs(FRIEND_TOKEN, progressId)
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
		assertThat(progress.keySet()).isEqualTo(Set.of(
				"sharedCardId", "kind", "ownerId", "startDate", "ownerNickname", "purpose", "targetAmount",
				"progressPercent", "targetDate", "balanceAdjustmentInProgress", "photo", "contentUpdatedAt"));
		assertThat(progress.get("photo")).isNull();

		String completionId = cardIdForWish(CAMP_WISH_ID);
		completeCamp("normative-completion-card").andExpect(status().isOk());
		Map<String, Object> completion = json(getAs(NONFRIEND_TOKEN, completionId)
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
		assertThat(completion.keySet()).isEqualTo(Set.of(
				"sharedCardId", "kind", "ownerId", "startDate", "ownerNickname", "purpose", "targetAmount",
				"progressPercent", "targetDate", "createdAt", "completedAt",
				"actualDurationSeconds", "photo", "contentUpdatedAt"));
		assertThat(completion.get("photo")).isNull();

		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/abandonment")
				.header("Idempotency-Key", "normative-abandonment-card")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk());
		Map<String, Object> abandonment = json(getAs(FRIEND_TOKEN, progressId)
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
		assertThat(abandonment.keySet()).isEqualTo(Set.of(
				"sharedCardId", "kind", "state", "ownerId", "ownerNickname", "purpose", "targetAmount",
				"progressPercent", "photo", "startDate", "targetDate", "contentUpdatedAt"));
		assertThat(abandonment.get("photo")).isNull();

		for (Map<String, Object> card : java.util.List.of(progress, completion, abandonment)) {
			assertThat(card).doesNotContainKeys(
					"wishId", "wishAmount", "amount", "accountId", "cardBalanceAccountId",
					"physicalCardNumber", "studentId", "realName", "abandonmentAmount", "abandonedAt");
		}
	}
}
