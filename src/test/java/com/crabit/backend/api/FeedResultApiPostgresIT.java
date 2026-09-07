package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.recommendation.FeedRankingClient;
import com.crabit.backend.recommendation.FeedRankingDeadline;
import com.crabit.backend.recommendation.FeedRankingModels;
import com.crabit.backend.wish.SharedCardCursor;
import com.crabit.backend.wish.SharedCardQueryService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "crabit.feed.ranking.enabled=true",
        "crabit.feed.ranking.url=http://127.0.0.1:1/internal/v1/feed-rankings",
        "crabit.feed.ranking.credential=test-only",
        "crabit.feed.ranking.classifier-version=wish-category-v1@sha256:de23b80260907e3d818892c0ea6ba2d9d28251e49d75a812fb925e1a47733f61"
})
class FeedResultApiPostgresIT extends WishApiIntegrationSupport {
    private static final String PATH = "/v1/academies/" + PRIMARY_ACADEMY_ID + "/feed-results";

    @MockitoBean FeedRankingClient ranking;
    @Autowired SharedCardCursor cursors;
    @Autowired SharedCardQueryService pages;

    @Test
    void firstPagePublishesRankingSuccessAndFailClosedLatestFallback() throws Exception {
        ensureViewerAccount();
        rankAllCandidates();
        asToken(FRIEND_TOKEN, post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortSource").value("RECOMMENDATION"))
                .andExpect(jsonPath("$.recommendationResultId").isString())
                .andExpect(jsonPath("$.modelVersion").value("feed-rules-v1"));

        when(ranking.rank(any(FeedRankingModels.Request.class), any(FeedRankingDeadline.class)))
                .thenReturn(Optional.empty());
        asToken(FRIEND_TOKEN, post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortSource").value("LATEST"))
                .andExpect(jsonPath("$.recommendationResultId").value((Object) null))
                .andExpect(jsonPath("$.modelVersion").value((Object) null));
    }

    @Test
    void v2SuccessorAllowsANewLimitButReplayFixesTheConsumedLimitAndExpiry() throws Exception {
        ensureViewerAccount();
        rankAllCandidates();
        String first = body(null, 1);
        String input = JsonPath.read(first, "$.nextCursor");
        assertThat(input).isNotBlank();
        String second = body(input, 2);
        asToken(FRIEND_TOKEN, post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody(input, 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));

        clock.set(clock.instant().plus(Duration.ofSeconds(301)));
        asToken(FRIEND_TOKEN, post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody(input, 2))).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("RECOMMENDATION_CURSOR_EXPIRED"));
    }

    @Test
    void storageFailureRollsBackAndDoesNotConsumeTheInputCursor() {
        ensureViewerAccount();
        rankAllCandidates();
        var first = pages.feed(com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID,
                PRIMARY_ACADEMY_ID, null, 1, ignored -> {});
        assertThat(first.nextCursor()).isNotBlank();

        assertThatThrownBy(() -> pages.feed(com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID,
                PRIMARY_ACADEMY_ID, first.nextCursor(), 1,
                ignored -> { throw new DataAccessResourceFailureException("simulated outage"); }))
                .isInstanceOf(com.crabit.backend.recommendation.FeedPageContextRepository.ContextUnavailable.class);

        assertThat(pages.feed(com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID,
                PRIMARY_ACADEMY_ID, first.nextCursor(), 1, ignored -> {}).items()).isNotEmpty();
    }

    @Test
    void shortRankingCrossesIntoLatestAndPersistsFinalBehaviorPositions() throws Exception {
        ensureViewerAccount();
        when(ranking.rank(any(FeedRankingModels.Request.class), any(FeedRankingDeadline.class)))
                .thenAnswer(invocation -> {
                    FeedRankingModels.Request request = invocation.getArgument(0);
                    UUID ranked = UUID.fromString(request.candidates().getFirst().get("card_id").toString());
                    return Optional.of(new FeedRankingModels.Result(
                            request.request_id(), request.context_id(), "test", List.of(ranked)));
                });

        String response = body(null, 2);
        List<String> returned = JsonPath.read(response, "$.items[*].sharedCardId");
        assertThat(returned).hasSize(2).doesNotHaveDuplicates();
        assertThat(JsonPath.<String>read(response, "$.sortSource")).isEqualTo("RECOMMENDATION");

        UUID resultContext = UUID.fromString(JsonPath.read(response, "$.resultContextId"));
        List<String> persisted = jdbc.query(
                "SELECT card_id::text FROM behavior_result_item WHERE context_id=? ORDER BY position",
                (rs, ignored) -> rs.getString(1), resultContext);
        assertThat(persisted).containsExactlyElementsOf(returned);
    }

    @Test
    void replayRemovesNewlyPrivateCardWithoutBackfillOrChangingSuccessor() throws Exception {
        ensureViewerAccount();
        rankAllCandidates();
        String first = body(null, 1);
        String input = JsonPath.read(first, "$.nextCursor");
        String original = body(input, 1);
        String hidden = JsonPath.read(original, "$.items[0].sharedCardId");
        String successor = JsonPath.read(original, "$.nextCursor");

        UUID hiddenId = UUID.fromString(hidden);
        UUID owner = jdbc.queryForObject("SELECT account.student_id FROM shared_card card"
                        + " JOIN wish ON wish.id=card.wish_id"
                        + " JOIN card_balance_account account ON account.id=wish.account_id"
                        + " WHERE card.id=?", UUID.class, hiddenId);
        jdbc.update("INSERT INTO student_block(id,blocker_id,blocked_id,blocked_at)"
                        + " VALUES (?,?,?,clock_timestamp())", UUID.randomUUID(), owner,
                com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID);
        String replay = body(input, 1);

        assertThat(JsonPath.<List<String>>read(replay, "$.items[*].sharedCardId")).isEmpty();
        assertThat(JsonPath.<String>read(replay, "$.nextCursor")).isEqualTo(successor);
        UUID resultContext = UUID.fromString(JsonPath.read(replay, "$.resultContextId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM behavior_result_item WHERE context_id=?",
                Integer.class, resultContext)).isZero();
    }

    @Test
    void tamperBindingContextLossAndExactExhaustionArePubliclyObservable() throws Exception {
        ensureViewerAccount();
        rankAllCandidates();
        String cursor = JsonPath.read(body(null, 1), "$.nextCursor");
        asToken(FRIEND_TOKEN, post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody(cursor + "x", 1))).andExpect(status().isBadRequest());

        SharedCardCursor.V2 decoded = cursors.decodeV2(cursor,
                com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID, PRIMARY_ACADEMY_ID);
        jdbc.update("DELETE FROM feed_page_context WHERE id=?", decoded.contextId());
        asToken(FRIEND_TOKEN, post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody(cursor, 1))).andExpect(status().isGone());

        rankAllCandidates();
        var seen = new HashSet<String>();
        String next = null;
        boolean exhausted = false;
        for (int pages = 0; pages < 101; pages++) {
            String response = body(next, 1);
            List<String> ids = JsonPath.read(response, "$.items[*].sharedCardId");
            assertThat(ids).allMatch(seen::add);
            next = JsonPath.read(response, "$.nextCursor");
            if (next == null) { exhausted = true; break; }
        }
        assertThat(exhausted).isTrue();
    }

    private void rankAllCandidates() {
        when(ranking.rank(any(FeedRankingModels.Request.class), any(FeedRankingDeadline.class)))
                .thenAnswer(invocation -> {
                    FeedRankingModels.Request request = invocation.getArgument(0);
                    List<UUID> ids = new ArrayList<>();
                    request.candidates().forEach(candidate -> ids.add(UUID.fromString(candidate.get("card_id").toString())));
                    java.util.Collections.reverse(ids);
                    List<UUID> ordered = ids.subList(0, Math.min(20, ids.size()));
                    return Optional.of(new FeedRankingModels.Result(request.request_id(), request.context_id(), "test", ordered));
                });
    }

    private String body(String cursor, int limit) throws Exception {
        return asToken(FRIEND_TOKEN, post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody(cursor, limit))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static String jsonBody(String cursor, int limit) {
        return cursor == null ? "{\"limit\":" + limit + "}"
                : "{\"cursor\":\"" + cursor + "\",\"limit\":" + limit + "}";
    }

    private void ensureViewerAccount() {
        jdbc.update("INSERT INTO card_balance_account"
                        + " (id,student_id,academy_id,opened_at,closed_at,balance_lookup_version,version)"
                        + " VALUES (?,?,?,?::timestamptz,NULL,0,0)",
                UUID.fromString("00000000-0000-0000-0000-000000009999"),
                com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID, PRIMARY_ACADEMY_ID,
                "2026-08-01T00:00:00Z");
    }
}
