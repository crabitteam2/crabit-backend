package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
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
}
