package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Aggregates current-relationship visibility and privacy-safe card projections. */
class WishSharingE2EIT extends RelationshipVisibilityMatrixIT {

	@Test
	void progressAndCompletionCardsExposeOnlyTheirClosedPrivacySafeShapes()
			throws Exception {
		String progressId = cardIdForWish(LAPTOP_WISH_ID);
		Map<String, Object> progress = json(getAs(FRIEND_TOKEN, progressId)
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
		assertThat(progress.keySet()).isEqualTo(Set.of(
				"sharedCardId", "kind", "ownerNickname", "purpose", "targetAmount",
				"progressPercent", "balanceAdjustmentInProgress", "contentUpdatedAt"));

		String completionId = cardIdForWish(CAMP_WISH_ID);
		completeCamp("normative-completion-card").andExpect(status().isOk());
		Map<String, Object> completion = json(getAs(NONFRIEND_TOKEN, completionId)
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
		assertThat(completion.keySet()).isEqualTo(Set.of(
				"sharedCardId", "kind", "ownerNickname", "purpose", "targetAmount",
				"progressPercent", "targetDate", "createdAt", "completedAt",
				"actualDurationSeconds", "contentUpdatedAt"));

		for (Map<String, Object> card : java.util.List.of(progress, completion)) {
			assertThat(card).doesNotContainKeys(
					"wishId", "wishAmount", "amount", "accountId", "cardBalanceAccountId",
					"physicalCardNumber", "studentId", "ownerId", "realName");
		}
	}
}
