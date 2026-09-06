package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.crabit.backend.behavior.*;
import com.crabit.backend.behavior.BehaviorModels.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class BehaviorApiPostgresIT extends WishApiIntegrationSupport {
    static final String BASE = "/v1/academies/" + PRIMARY_ACADEMY_ID;
    @Autowired BehaviorService service;
    @Autowired BehaviorMetricsService metrics;
    @Autowired BehaviorRetention retention;
    @Autowired ObjectMapper mapper;

    Event visit(UUID id, UUID target, Instant time) {
        return new Event(id, "PROFILE_VISIT", time, target, null, null, null, null, null);
    }

    Outcome visit(UUID id, UUID target) {
        return service.collect(OWNER_ID, PRIMARY_ACADEMY_ID, visit(id, target, clock.instant()));
    }

    String json(UUID id, String time) {
        return "{\"eventId\":\""
                + id
                + "\",\"targetStudentId\":\""
                + FRIEND_ID
                + "\",\"occurredAt\":\""
                + time
                + "\"}";
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void cleanupAtContextExpiryCannotDeleteAnInFlightAcceptedEvent() throws Exception {
        var page = service.createResult(FRIEND_ID, PRIMARY_ACADEMY_ID, null, 20);
        clock.set(COMMAND_TIME.plusSeconds(86399));
        var event = feedEvent(page, UUID.randomUUID(), UUID.randomUUID(), "FEED_CLICK");
        var collected = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future =
                    executor.submit(
                            () ->
                                    new org.springframework.transaction.support.TransactionTemplate(
                                                    transactionManager)
                                            .execute(
                                                    status -> {
                                                        var outcome =
                                                                service.collect(
                                                                        FRIEND_ID,
                                                                        PRIMARY_ACADEMY_ID,
                                                                        event);
                                                        collected.countDown();
                                                        try {
                                                            if (!commit.await(10, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "Commit release timed out");
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(e);
                                                        }
                                                        return outcome;
                                                    }));
            assertThat(collected.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                clock.set(COMMAND_TIME.plusSeconds(86401));
                retention.cleanup();
            } finally {
                commit.countDown();
            }
            assertThat(future.get(10, TimeUnit.SECONDS).replayed()).isFalse();
        }
        assertThat(service.collect(FRIEND_ID, PRIMARY_ACADEMY_ID, event).replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM behavior_event", Long.class)).isOne();
    }

    @Test
    void currentFollowAndBlockChangesRemoveAndRestoreRetainedFeedContribution() {
        var page = service.createResult(FRIEND_ID, PRIMARY_ACADEMY_ID, null, 20);
        var event = feedEvent(page, UUID.randomUUID(), UUID.randomUUID(), "FEED_CLICK");
        service.collect(FRIEND_ID, PRIMARY_ACADEMY_ID, event);
        jdbc.update("UPDATE shared_card SET visibility='FOLLOWERS' WHERE id=?", event.cardId());
        jdbc.update(
                "UPDATE student_follow SET ended_at=? WHERE source_id=? AND target_id=?",
                Timestamp.from(clock.instant()),
                FRIEND_ID,
                OWNER_ID);
        assertThat(feedRows()).isEmpty();
        assertThatThrownBy(() -> service.collect(FRIEND_ID, PRIMARY_ACADEMY_ID, event))
                .isInstanceOf(BehaviorException.class);
        jdbc.update(
                "UPDATE student_follow SET ended_at=NULL WHERE source_id=? AND target_id=?",
                FRIEND_ID,
                OWNER_ID);
        assertThat(feedRows().getFirst()).containsEntry("clickCount", 1L);
        UUID block = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO student_block(id,blocker_id,blocked_id,blocked_at) VALUES (?,?,?,?)",
                block,
                OWNER_ID,
                FRIEND_ID,
                Timestamp.from(clock.instant()));
        assertThat(feedRows()).isEmpty();
        jdbc.update(
                "UPDATE student_block SET released_at=? WHERE id=?",
                Timestamp.from(clock.instant()),
                block);
        assertThat(feedRows().getFirst()).containsEntry("clickCount", 1L);
    }

    @Test
    void studentFeedHttpRoutesKeepReadsSeparateAndEnforceClosedVariants() throws Exception {
        asToken(FRIEND_TOKEN, get(BASE + "/shared-cards")).andExpect(status().isOk());
        String body =
                asToken(
                                FRIEND_TOKEN,
                                post(BASE + "/feed-results")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"limit\":1}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.sortSource").value("LATEST"))
                        .andExpect(
                                jsonPath("$.recommendationResultId")
                                        .value(org.hamcrest.Matchers.nullValue()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var page = mapper.readTree(body);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM behavior_event", Long.class)).isZero();
        String event =
                """
                {"eventId":"%s","eventType":"FEED_EXPOSURE","occurredAt":"%s",
                 "resultContextId":"%s","cardId":"%s","position":0,"impressionId":"%s"}
                """
                        .formatted(
                                UUID.randomUUID(),
                                clock.instant(),
                                page.get("resultContextId").stringValue(),
                                page.get("items").get(0).get("sharedCardId").stringValue(),
                                UUID.randomUUID());
        asToken(
                        FRIEND_TOKEN,
                        post(BASE + "/feed-events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(event))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("FEED_EXPOSURE"));
        asToken(
                        FRIEND_TOKEN,
                        post(BASE + "/feed-events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(event.replace("}", ",\"clickKind\":\"AUTHOR_PROFILE\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
        asOwner(post(BASE + "/feed-events").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FEED_CONTEXT_NOT_FOUND"));
    }

    @Test
    void disabledMachineIntegrationExposesNeitherMetricsNorDocumentation() throws Exception {
        asOwner(
                        get("/internal/v1/academies/"
                                        + PRIMARY_ACADEMY_ID
                                        + "/behavior-metrics/feed")
                                .param("fromDate", "2026-08-18")
                                .param("toDate", "2026-08-19"))
                .andExpect(status().isNotFound());
        String document =
                mockMvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Map<String, Object> paths = com.jayway.jsonpath.JsonPath.read(document, "$.paths");
        assertThat(paths.keySet()).noneMatch(path -> path.contains("behavior-metrics"));
    }

    @Test
    void exactReplayUsesOriginalNormalizedTimeAndRemainsAvailableAfter24Hours() throws Exception {
        UUID id = UUID.randomUUID();
        String body = json(id, "2026-08-18T00:00:00.123456789123Z");
        String original =
                asOwner(
                                post(BASE + "/profile-visits")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.occurredAt").value("2026-08-18T00:00:00.123456Z"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        clock.set(COMMAND_TIME.plus(Duration.ofDays(2)));
        asOwner(
                        post(BASE + "/profile-visits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(content().json(original));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM behavior_event", Long.class))
                .isEqualTo(1);
    }

    @Test
    void strictRequestsRejectUnknownDuplicateNullTypesAndQueryKeys() throws Exception {
        String valid = json(UUID.randomUUID(), clock.instant().toString());
        for (String invalid :
                List.of(
                        valid.replace(
                                "\"eventId\":",
                                "\"eventId\":\"" + UUID.randomUUID() + "\",\"eventId\":"),
                        valid.replace("\"targetStudentId\"", "\"actorId\""),
                        valid.replace(
                                "\"occurredAt\":\"" + clock.instant() + "\"",
                                "\"occurredAt\":null"),
                        valid + " {}",
                        valid.replace("Z\"", "+00:00\""))) {
            asOwner(
                            post(BASE + "/profile-visits")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
        }
        asOwner(
                        post(BASE + "/profile-visits")
                                .queryParam("actorId", "x")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(valid))
                .andExpect(status().isBadRequest());
        asOwner(post(BASE + "/profile-visits").contentType(MediaType.TEXT_PLAIN).content(valid))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(
                        post(BASE + "/profile-visits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(valid))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void concurrentIdenticalAndConflictingRequestsConvergeWithCrossActorIsolation()
            throws Exception {
        UUID id = UUID.randomUUID();
        var event = visit(id, FRIEND_ID, clock.instant());
        var outcomes =
                race(
                        () -> service.collect(OWNER_ID, PRIMARY_ACADEMY_ID, event),
                        () -> service.collect(OWNER_ID, PRIMARY_ACADEMY_ID, event));
        assertThat(outcomes.stream().map(x -> ((Outcome) x).replayed()))
                .containsExactlyInAnyOrder(false, true);
        UUID conflicting = UUID.randomUUID();
        var conflicts =
                race(() -> visit(conflicting, FRIEND_ID), () -> visit(conflicting, NONFRIEND_ID));
        assertThat(
                        conflicts.stream()
                                .filter(x -> x instanceof BehaviorException)
                                .map(x -> ((BehaviorException) x).code()))
                .containsExactly("EVENT_ID_CONFLICT");
        service.collect(NONFRIEND_ID, PRIMARY_ACADEMY_ID, visit(id, FRIEND_ID, clock.instant()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM behavior_event", Long.class))
                .isEqualTo(3);
    }

    @Test
    void selfHiddenTimeBoundaryAndCurrentAccessApplyToReplayAndMetrics() {
        assertThatThrownBy(() -> visit(UUID.randomUUID(), OWNER_ID))
                .isInstanceOfSatisfying(
                        BehaviorException.class,
                        e -> assertThat(e.code()).isEqualTo("SELF_PROFILE_VISIT"));
        assertThatThrownBy(() -> visit(UUID.randomUUID(), BLOCKED_ID))
                .isInstanceOfSatisfying(
                        BehaviorException.class,
                        e -> assertThat(e.code()).isEqualTo("PROFILE_NOT_FOUND"));
        for (Instant time :
                List.of(clock.instant().minusSeconds(86400), clock.instant().plusSeconds(300)))
            service.collect(
                    OWNER_ID, PRIMARY_ACADEMY_ID, visit(UUID.randomUUID(), FRIEND_ID, time));
        for (Instant time :
                List.of(
                        clock.instant().minusSeconds(86400).minusNanos(1000),
                        clock.instant().plusSeconds(300).plusNanos(1000)))
            assertThatThrownBy(
                            () ->
                                    service.collect(
                                            OWNER_ID,
                                            PRIMARY_ACADEMY_ID,
                                            visit(UUID.randomUUID(), FRIEND_ID, time)))
                    .isInstanceOfSatisfying(
                            BehaviorException.class,
                            e -> assertThat(e.code()).isEqualTo("EVENT_TIME_OUT_OF_RANGE"));
        UUID id = UUID.randomUUID();
        visit(id, FRIEND_ID);
        jdbc.update(
                "UPDATE academy_membership SET left_at=? WHERE student_id=?",
                Timestamp.from(clock.instant()),
                FRIEND_ID);
        assertThatThrownBy(() -> visit(id, FRIEND_ID))
                .isInstanceOfSatisfying(
                        BehaviorException.class,
                        e -> assertThat(e.code()).isEqualTo("PROFILE_NOT_FOUND"));
        assertThatThrownBy(
                        () ->
                                metrics.profile(
                                        PRIMARY_ACADEMY_ID,
                                        FRIEND_ID,
                                        null,
                                        LocalDate.of(2026, 8, 18),
                                        LocalDate.of(2026, 8, 19)))
                .isInstanceOfSatisfying(
                        BehaviorException.class,
                        e -> assertThat(e.code()).isEqualTo("PROFILE_NOT_FOUND"));
    }

    @Test
    void feedContextAndClicksRemainDistinctAndCtrUsesExactImpressions() throws Exception {
        var page = service.createResult(FRIEND_ID, PRIMARY_ACADEMY_ID, null, 20);
        assertThat(page.items()).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM behavior_event", Long.class)).isZero();
        UUID impression = UUID.randomUUID();
        Event click = feedEvent(page, UUID.randomUUID(), impression, "FEED_CLICK");
        service.collect(FRIEND_ID, PRIMARY_ACADEMY_ID, click);
        var before = feedRows();
        assertThat(before.getFirst())
                .containsEntry("clickCount", 1L)
                .containsEntry("exposureCount", 0L)
                .containsEntry("ctr", null)
                .containsEntry("unmatchedClickCount", 1L);
        service.collect(
                FRIEND_ID,
                PRIMARY_ACADEMY_ID,
                feedEvent(page, UUID.randomUUID(), impression, "FEED_EXPOSURE"));
        service.collect(
                FRIEND_ID,
                PRIMARY_ACADEMY_ID,
                feedEvent(page, UUID.randomUUID(), impression, "FEED_CLICK"));
        service.collect(
                FRIEND_ID,
                PRIMARY_ACADEMY_ID,
                feedEvent(page, UUID.randomUUID(), UUID.randomUUID(), "FEED_CLICK"));
        assertThat(feedRows().getFirst())
                .containsEntry("clickCount", 3L)
                .containsEntry("exposureCount", 1L)
                .containsEntry("clickedExposedImpressionCount", 1L)
                .containsEntry("unmatchedClickCount", 1L)
                .containsEntry("ctr", 1.0);
        assertThatThrownBy(
                        () ->
                                service.collect(
                                        FRIEND_ID,
                                        PRIMARY_ACADEMY_ID,
                                        feedEvent(
                                                page,
                                                UUID.randomUUID(),
                                                impression,
                                                "FEED_EXPOSURE")))
                .isInstanceOfSatisfying(
                        BehaviorException.class,
                        e -> assertThat(e.code()).isEqualTo("IMPRESSION_ALREADY_EXPOSED"));
        var next = service.createResult(FRIEND_ID, PRIMARY_ACADEMY_ID, null, 20);
        assertThatThrownBy(
                        () ->
                                service.collect(
                                        FRIEND_ID,
                                        PRIMARY_ACADEMY_ID,
                                        feedEvent(
                                                next, UUID.randomUUID(), impression, "FEED_CLICK")))
                .isInstanceOfSatisfying(
                        BehaviorException.class,
                        e -> assertThat(e.code()).isEqualTo("IMPRESSION_CONFLICT"));
        jdbc.update("DELETE FROM shared_card WHERE id=?", page.items().getFirst().sharedCardId());
        assertThat(feedRows()).isEmpty();
        assertThatThrownBy(() -> service.collect(FRIEND_ID, PRIMARY_ACADEMY_ID, click))
                .isInstanceOfSatisfying(
                        BehaviorException.class,
                        e -> assertThat(e.code()).isEqualTo("SHARED_CARD_NOT_FOUND"));
    }

    @Test
    void feedReplayContextExpiryCrossKindAndDuplicateExposureAreTransactional() throws Exception {
        var page = service.createResult(FRIEND_ID, PRIMARY_ACADEMY_ID, null, 20);
        UUID impression = UUID.randomUUID();
        var a = feedEvent(page, UUID.randomUUID(), impression, "FEED_EXPOSURE");
        var b = feedEvent(page, UUID.randomUUID(), impression, "FEED_EXPOSURE");
        var outcomes =
                race(
                        () -> service.collect(FRIEND_ID, PRIMARY_ACADEMY_ID, a),
                        () -> service.collect(FRIEND_ID, PRIMARY_ACADEMY_ID, b));
        assertThat(
                        outcomes.stream()
                                .filter(x -> x instanceof BehaviorException)
                                .map(x -> ((BehaviorException) x).code()))
                .containsExactly("IMPRESSION_ALREADY_EXPOSED");
        Event accepted = outcomes.get(0) instanceof Outcome ? a : b;
        assertThatThrownBy(
                        () ->
                                service.collect(
                                        FRIEND_ID,
                                        PRIMARY_ACADEMY_ID,
                                        visit(accepted.eventId(), OWNER_ID, clock.instant())))
                .isInstanceOfSatisfying(
                        BehaviorException.class,
                        e -> assertThat(e.code()).isEqualTo("EVENT_ID_CONFLICT"));
        clock.set(COMMAND_TIME.plusSeconds(86400));
        assertThat(service.collect(FRIEND_ID, PRIMARY_ACADEMY_ID, accepted).replayed()).isTrue();
        assertThatThrownBy(
                        () ->
                                service.collect(
                                        FRIEND_ID,
                                        PRIMARY_ACADEMY_ID,
                                        feedEvent(
                                                page,
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                "FEED_CLICK")))
                .isInstanceOfSatisfying(
                        BehaviorException.class,
                        e -> assertThat(e.code()).isEqualTo("FEED_CONTEXT_EXPIRED"));
    }

    @Test
    void seoulDaysDistinctVisitorsCoverageAndRetentionAreExplicit() {
        clock.set(Instant.parse("2026-08-18T15:00:00Z"));
        service.collect(
                OWNER_ID,
                PRIMARY_ACADEMY_ID,
                visit(UUID.randomUUID(), FRIEND_ID, clock.instant().minusNanos(1000)));
        visit(UUID.randomUUID(), FRIEND_ID);
        visit(UUID.randomUUID(), FRIEND_ID);
        service.collect(
                NONFRIEND_ID,
                PRIMARY_ACADEMY_ID,
                visit(UUID.randomUUID(), FRIEND_ID, clock.instant()));
        var result =
                metrics.profile(
                        PRIMARY_ACADEMY_ID,
                        FRIEND_ID,
                        null,
                        LocalDate.of(2026, 8, 18),
                        LocalDate.of(2026, 8, 20));
        assertThat(result)
                .containsEntry("visitCount", 4L)
                .containsEntry("distinctVisitorCount", 2L);
        var daily = (List<Map<String, Object>>) result.get("daily");
        assertThat(daily.get(0)).containsEntry("visitCount", 1L);
        assertThat(daily.get(1)).containsEntry("visitCount", 3L);
        var historical =
                metrics.profile(
                        PRIMARY_ACADEMY_ID,
                        FRIEND_ID,
                        null,
                        LocalDate.of(2025, 1, 1),
                        LocalDate.of(2025, 1, 2));
        assertThat(historical).containsEntry("visitCount", null);
        assertThat((Map<String, Object>) historical.get("coverage"))
                .containsEntry("status", "NONE");
        clock.set(clock.instant().plus(Duration.ofDays(90)));
        assertThat(
                        metrics.profile(
                                PRIMARY_ACADEMY_ID,
                                FRIEND_ID,
                                null,
                                LocalDate.of(2026, 8, 18),
                                LocalDate.of(2026, 8, 19)))
                .containsEntry("visitCount", null);
        assertThat(
                        metrics.profile(
                                PRIMARY_ACADEMY_ID,
                                FRIEND_ID,
                                null,
                                LocalDate.of(2026, 8, 18),
                                LocalDate.of(2026, 8, 20)))
                .containsEntry("visitCount", 0L);
        retention.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM behavior_event", Long.class)).isZero();
    }

    @Test
    void contextAndImpressionCleanupPreservesRetainedReplayDependencies() {
        var page = service.createResult(FRIEND_ID, PRIMARY_ACADEMY_ID, null, 20);
        var event = feedEvent(page, UUID.randomUUID(), UUID.randomUUID(), "FEED_CLICK");
        service.collect(FRIEND_ID, PRIMARY_ACADEMY_ID, event);
        clock.set(COMMAND_TIME.plus(Duration.ofDays(2)));
        retention.cleanup();
        assertThat(service.collect(FRIEND_ID, PRIMARY_ACADEMY_ID, event).replayed()).isTrue();
        clock.set(COMMAND_TIME.plus(Duration.ofDays(90)));
        retention.cleanup();
        for (String table :
                List.of(
                        "behavior_event",
                        "behavior_impression",
                        "behavior_result_item",
                        "behavior_result_context"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
    }

    Event feedEvent(FeedResult page, UUID id, UUID impression, String kind) {
        return new Event(
                id,
                kind,
                clock.instant(),
                null,
                page.resultContextId(),
                page.items().getFirst().sharedCardId(),
                0,
                impression,
                kind.equals("FEED_CLICK") ? "AUTHOR_PROFILE" : null);
    }

    List<Map<String, Object>> feedRows() {
        return (List<Map<String, Object>>)
                metrics.feed(
                                PRIMARY_ACADEMY_ID,
                                LocalDate.of(2026, 8, 18),
                                LocalDate.of(2026, 8, 19))
                        .get("items");
    }

    List<Object> race(Callable<Object> a, Callable<Object> b) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    return a.call();
                                } catch (BehaviorException e) {
                                    return e;
                                }
                            });
            var two =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    return b.call();
                                } catch (BehaviorException e) {
                                    return e;
                                }
                            });
            start.countDown();
            return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        }
    }
}
