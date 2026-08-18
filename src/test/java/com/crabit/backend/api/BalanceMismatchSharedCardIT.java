package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BalanceMismatchSharedCardIT extends SharedCardApiIntegrationSupport {

	@Test
	void openAdjustmentChangesOnlyTheProgressReadProjectionAndNeverCompletionShapeOrOrder()
			throws Exception {
		String progressCard = cardIdForWish(LAPTOP_WISH_ID);
		String completionCard = cardIdForWish(CAMP_WISH_ID);
		UUID progressCardId = UUID.fromString(progressCard);
		Instant progressOrder = jdbc.queryForObject(
				"SELECT updated_at FROM shared_card WHERE id = ?",
				(rs, row) -> rs.getTimestamp(1).toInstant(), progressCardId);

		completeCamp("complete-before-adjustment").andExpect(status().isOk());
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":100000}]");
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.account.balanceAdjustmentInProgress").value(true));

		getAs(FRIEND_TOKEN, progressCard)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("PROGRESS"))
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").value(true));
		Instant retainedOrder = jdbc.queryForObject(
				"SELECT updated_at FROM shared_card WHERE id = ?",
				(rs, row) -> rs.getTimestamp(1).toInstant(), progressCardId);
		assertThat(retainedOrder).isEqualTo(progressOrder);

		String completion = getAs(NONFRIEND_TOKEN, completionCard)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kind").value("COMPLETION"))
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		Map<String, Object> card = json(completion, "$");
		assertThat(card).doesNotContainKeys(
				"balanceAdjustmentInProgress", "adjustmentCaseId", "openedShortage",
				"observationId", "accountId", "wishId", "wishAmount");
	}
}
