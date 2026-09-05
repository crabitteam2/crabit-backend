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

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class SharedWishCardLifecycleIT extends SharedCardApiIntegrationSupport {

	@Test
	void publicContentChangesPreserveIdentityAndPrivateVisibilityRemovesTheCard() throws Exception {
		String cardId = cardIdForWish(LAPTOP_WISH_ID);

		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.kind").value("PROGRESS"))
				.andExpect(jsonPath("$.progressPercent").value(16));

		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":0,\"purpose\":\"새 노트북과 모니터\"}"))
				.andExpect(status().isOk());

		assertThat(cardIdForWish(LAPTOP_WISH_ID)).isEqualTo(cardId);
		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.purpose").value("새 노트북과 모니터"));

		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":1,\"visibility\":\"PRIVATE\"}"))
				.andExpect(status().isOk());

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM shared_card WHERE wish_id = ?", Long.class, LAPTOP_WISH_ID))
				.isZero();
		getAs(FRIEND_TOKEN, cardId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHARED_CARD_NOT_FOUND"));
	}

	@Test
	void completionConvertsTheSameCardAndPublishesCompletionTimingWithoutAdjustmentState()
			throws Exception {
		String cardId = cardIdForWish(CAMP_WISH_ID);

		completeCamp("shared-card-complete")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("COMPLETED"));

		assertThat(cardIdForWish(CAMP_WISH_ID)).isEqualTo(cardId);
		getAs(NONFRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.kind").value("COMPLETION"))
				.andExpect(jsonPath("$.progressPercent").value(100))
				.andExpect(jsonPath("$.targetDate").value("2026-09-01"))
				.andExpect(jsonPath("$.createdAt").isString())
				.andExpect(jsonPath("$.completedAt").value(COMMAND_TIME.toString()))
				.andExpect(jsonPath("$.actualDurationSeconds").isNumber())
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").doesNotExist());
	}

	@Test
	void zeroAmountPublicWishCreatesProgressCardAndPreservesItsIdentity() throws Exception {
		String wishId = createWish("create-zero-shared", "무지출 목표", 100_000);

		asOwner(patch(WISHES_PATH + "/" + wishId)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":0,\"visibility\":\"ACADEMY\"}"))
				.andExpect(status().isOk());

		String cardId = cardIdForWish(java.util.UUID.fromString(wishId));
		getAs(NONFRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.kind").value("PROGRESS"))
				.andExpect(jsonPath("$.progressPercent").value(0));

		asOwner(patch(WISHES_PATH + "/" + wishId)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":1,\"purpose\":\"무지출 목표 갱신\"}"))
				.andExpect(status().isOk());

		assertThat(cardIdForWish(java.util.UUID.fromString(wishId))).isEqualTo(cardId);
		getAs(NONFRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.purpose").value("무지출 목표 갱신"))
				.andExpect(jsonPath("$.progressPercent").value(0));
	}

	@Test
	void withdrawalFromAmountReachedKeepsTheProgressCardAndLowersItsPercentage()
			throws Exception {
		String cardId = cardIdForWish(CAMP_WISH_ID);
		getAs(NONFRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("PROGRESS"))
				.andExpect(jsonPath("$.progressPercent").value(100));

		asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/withdrawals")
				.header("Idempotency-Key", "shared-card-withdraw-reached")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":1,\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("IN_PROGRESS"));

		assertThat(cardIdForWish(CAMP_WISH_ID)).isEqualTo(cardId);
		getAs(NONFRIEND_TOKEN, cardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.kind").value("PROGRESS"))
				.andExpect(jsonPath("$.progressPercent").value(99));
	}

	@Test
	void abandonmentPreservesThePublicCardIdentityAndDeletionStillRemovesIt()
			throws Exception {
		String abandonedCardId = cardIdForWish(LAPTOP_WISH_ID);
		String deletedCardId = cardIdForWish(CAMP_WISH_ID);

		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/abandonment")
				.header("Idempotency-Key", "shared-card-abandon")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("ABANDONED"))
				.andExpect(jsonPath("$.wish.visibility").value("FOLLOWERS"));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM shared_card WHERE wish_id = ?", Long.class, LAPTOP_WISH_ID))
				.isOne();
		getAs(FRIEND_TOKEN, abandonedCardId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(abandonedCardId))
				.andExpect(jsonPath("$.kind").value("ABANDONMENT"))
				.andExpect(jsonPath("$.state").value("ABANDONED"))
				.andExpect(jsonPath("$.progressPercent").value(16))
				.andExpect(jsonPath("$.abandonmentAmount").doesNotExist())
				.andExpect(jsonPath("$.amount").doesNotExist());

		asOwner(delete(WISHES_PATH + "/" + CAMP_WISH_ID)
				.header(HttpHeaders.IF_MATCH, "0")
				.header("Idempotency-Key", "shared-card-delete"))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM shared_card WHERE wish_id = ?", Long.class, CAMP_WISH_ID))
				.isZero();
		getAs(NONFRIEND_TOKEN, deletedCardId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHARED_CARD_NOT_FOUND"));
	}
}
