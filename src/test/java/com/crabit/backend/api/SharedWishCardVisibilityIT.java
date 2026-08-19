package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.BLOCKED_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.STAFF_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SharedWishCardVisibilityIT extends SharedCardApiIntegrationSupport {

	@Test
	void listAppliesFriendsAcademyOwnerBlockAndPrincipalBoundaries() throws Exception {
		listAs(FRIEND_TOKEN)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].purpose",
						containsInAnyOrder("노트북", "여름 캠프")));
		listAs(NONFRIEND_TOKEN)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].purpose").value("여름 캠프"));
		listAs(BLOCKED_TOKEN)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
		listAs(OWNER_TOKEN)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());

		listAs(OTHER_ACADEMY_TOKEN)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));
		listAs(STAFF_TOKEN)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		mockMvc.perform(get(SHARED_CARDS_PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
	}

	@Test
	void detailAllowsOwnerHidesNonfriendAndSerializesOnlyThePrivacySafeProgressShape()
			throws Exception {
		String cardId = cardIdForWish(LAPTOP_WISH_ID);
		String response = getAs(OWNER_TOKEN, cardId)
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		Map<String, Object> card = json(response, "$");

		assertThat(card.keySet()).isEqualTo(Set.of(
				"sharedCardId", "kind", "ownerNickname", "purpose", "targetAmount",
				"progressPercent", "balanceAdjustmentInProgress", "contentUpdatedAt"));
		assertThat(card).doesNotContainKeys(
				"wishId", "wishAmount", "amount", "accountId", "cardBalanceAccountId",
				"studentId", "ownerId", "realName", "physicalCardNumber");

		getAs(NONFRIEND_TOKEN, cardId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHARED_CARD_NOT_FOUND"));
	}
}
