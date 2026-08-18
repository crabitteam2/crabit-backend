package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RelationshipVisibilityMatrixIT extends SharedCardApiIntegrationSupport {

	@Test
	void currentFriendshipMembershipAndReverseBlockApplyImmediately() throws Exception {
		String friendsCard = cardIdForWish(LAPTOP_WISH_ID);

		jdbc.update("UPDATE friendship SET ended_at = ? WHERE academy_id = ?",
				Timestamp.from(COMMAND_TIME), PRIMARY_ACADEMY_ID);
		listAs(FRIEND_TOKEN)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].purpose").value("여름 캠프"));
		getAs(FRIEND_TOKEN, friendsCard)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHARED_CARD_NOT_FOUND"));

		jdbc.update("UPDATE friendship SET ended_at = NULL WHERE academy_id = ?", PRIMARY_ACADEMY_ID);
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
}
