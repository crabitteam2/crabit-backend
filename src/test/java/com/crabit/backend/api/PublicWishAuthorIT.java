package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PublicWishAuthorIT extends SharedCardApiIntegrationSupport {
    private String students() { return "/v1/academies/" + PRIMARY_ACADEMY_ID + "/students/"; }

    @Test void identitySelfEmptyAndHiddenStudents() throws Exception {
        asToken(FRIEND_TOKEN, get(students() + OWNER_ID)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.studentId").value(OWNER_ID.toString()))
                .andExpect(jsonPath("$.isFollowing").value(true));
        asToken(OWNER_TOKEN, get(students() + OWNER_ID)).andExpect(status().isOk())
                .andExpect(jsonPath("$.isFollowing").value(false)).andExpect(jsonPath("$.isFollowedBy").value(false));
        for (UUID id : List.of(BLOCKED_ID, UUID.randomUUID())) {
            asToken(OWNER_TOKEN, get(students() + id)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
        }
        asToken(FRIEND_TOKEN, get(students() + NONFRIEND_ID)).andExpect(status().isOk());
        asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", NONFRIEND_ID.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.nextCursor").isEmpty());
        asToken(OWNER_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        for (String invalid : List.of("", "null", "no", "1-1-1-1-1")) {
            asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
        }
    }

    @Test void ownerFilterPrecedesLimitAndNicknameIsNeverIdentity() throws Exception {
        insertHiddenFriendsCard(COMMAND_TIME.plusSeconds(100));
        jdbc.update("UPDATE shared_card SET visibility = 'ACADEMY' WHERE id = ?", HIDDEN_CARD_ID);
        jdbc.update("UPDATE student SET nickname = (SELECT nickname FROM student WHERE id = ?) WHERE id = ?", OWNER_ID, NONFRIEND_ID);
        String first = asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].ownerId").value(OWNER_ID.toString()))
                .andReturn().getResponse().getContentAsString();
        String cursor = json(first, "$.nextCursor");
        String firstId = json(first, "$.items[0].sharedCardId");
        jdbc.update("UPDATE student SET nickname = 'renamed-owner' WHERE id = ?", OWNER_ID);
        asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()).param("cursor", cursor).param("limit", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].ownerNickname").value("renamed-owner"))
                .andExpect(jsonPath("$.items[0].sharedCardId").value(org.hamcrest.Matchers.not(firstId)))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        for (String wrong : List.of(NONFRIEND_ID.toString(), FRIEND_ID.toString())) {
            asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", wrong).param("cursor", cursor)).andExpect(status().isBadRequest());
        }
        asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("cursor", cursor)).andExpect(status().isBadRequest());
        asToken(OWNER_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()).param("cursor", cursor)).andExpect(status().isBadRequest());
        for (String invalid : List.of(cursor + "x", "", Base64.getUrlEncoder().encodeToString((COMMAND_TIME + "|" + OWNER_ID).getBytes()))) {
            asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()).param("cursor", invalid)).andExpect(status().isBadRequest());
        }
        for (String limit : List.of("0", "101")) asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("limit", limit)).andExpect(status().isBadRequest());
    }

    @Test void continuationRechecksCurrentVisibilityAndMembership() throws Exception {
        String first = asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()).param("limit", "1"))
                .andReturn().getResponse().getContentAsString();
        String cursor = json(first, "$.nextCursor");
        jdbc.update("UPDATE shared_card SET visibility = 'FOLLOWERS'");
        jdbc.update("UPDATE student_follow SET ended_at = now() WHERE source_id = ? AND target_id = ?", FRIEND_ID, OWNER_ID);
        asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()).param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        jdbc.update("UPDATE academy_membership SET left_at = now() WHERE student_id = ?", OWNER_ID);
        asToken(FRIEND_TOKEN, get(students() + OWNER_ID)).andExpect(status().isNotFound());
    }
    @Test void ownerPagesHideEveryRevokedStateOnFirstAndContinuationReads() throws Exception {
		for (String mutation : List.of("private", "deleted", "closed", "left", "outgoing-block", "incoming-block")) {
            resetFixture();
            String first = asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()).param("limit", "1"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String cursor = json(first, "$.nextCursor");
            switch (mutation) {
			case "private", "deleted" -> {
                    for (UUID wish : List.of(LAPTOP_WISH_ID, CAMP_WISH_ID)) {
                        if (mutation.equals("private")) {
                            asOwner(patch(WISHES_PATH + "/" + wish).contentType("application/merge-patch+json")
                                    .content("{\"expectedVersion\":0,\"visibility\":\"PRIVATE\"}"))
                                    .andExpect(status().isOk());
                        } else if (mutation.equals("deleted")) {
                            asOwner(delete(WISHES_PATH + "/" + wish).header("If-Match", "0")
                                    .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
					}
                    }
                }
                case "closed" -> jdbc.update("UPDATE card_balance_account SET closed_at = now() WHERE student_id = ?", OWNER_ID);
                case "left" -> jdbc.update("UPDATE academy_membership SET left_at = now() WHERE student_id = ?", OWNER_ID);
                default -> jdbc.update("INSERT INTO student_block(id, blocker_id, blocked_id, blocked_at) VALUES (?, ?, ?, now())",
                        REVERSE_BLOCK_ID, mutation.equals("outgoing-block") ? FRIEND_ID : OWNER_ID,
                        mutation.equals("outgoing-block") ? OWNER_ID : FRIEND_ID);
            }
            for (boolean next : List.of(false, true)) {
                var request = get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString());
                if (next) request.param("cursor", cursor);
                asToken(FRIEND_TOKEN, request).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
            }
            if (mutation.endsWith("block") || mutation.equals("left")) {
                asToken(FRIEND_TOKEN, get(students() + OWNER_ID)).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
            }
        }
    }

    @Test void completionUpdatesTheSameAuthorCardAndBothPaginationRequestsRemainValid() throws Exception {
        String first = asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()).param("limit", "1"))
                .andReturn().getResponse().getContentAsString();
        String cursor = json(first, "$.nextCursor");
        String before = cardIdForWish(CAMP_WISH_ID);
        completeCamp("public-author-completion").andExpect(status().isOk());
        assertThat(cardIdForWish(CAMP_WISH_ID)).isEqualTo(before);
        asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].kind").value("COMPLETION"))
                .andExpect(jsonPath("$.items[0].ownerId").value(OWNER_ID.toString()));
        asToken(FRIEND_TOKEN, get(SHARED_CARDS_PATH).param("ownerId", OWNER_ID.toString()).param("cursor", cursor))
                .andExpect(status().isOk());
    }

}
