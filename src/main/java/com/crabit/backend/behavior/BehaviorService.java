package com.crabit.backend.behavior;

import static com.crabit.backend.behavior.BehaviorModels.*;

import com.crabit.backend.relationship.RelationshipContextAuthorizationService;
import com.crabit.backend.wish.SharedCardQueryRepository;
import com.crabit.backend.wish.SharedCardQueryService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class BehaviorService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RelationshipContextAuthorizationService access;
    private final SharedCardQueryRepository cards;
    private final SharedCardQueryService pages;

    public BehaviorService(
            JdbcTemplate jdbc,
            Clock clock,
            RelationshipContextAuthorizationService access,
            SharedCardQueryRepository cards,
            SharedCardQueryService pages) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.access = access;
        this.cards = cards;
        this.pages = pages;
    }

    public static Instant micros(Instant time) {
        return time.truncatedTo(ChronoUnit.MICROS);
    }

    static Timestamp ts(Instant time) {
        return Timestamp.from(time);
    }

    public void requireAcademy(UUID actor, UUID academy) {
        if (!access.canAccessAcademy(actor, academy))
            throw new BehaviorException("ACADEMY_NOT_FOUND", 404);
    }

    public void requireProfile(UUID actor, UUID target, UUID academy) {
        if (!access.canViewAcademyCard(target, actor, academy))
            throw new BehaviorException("PROFILE_NOT_FOUND", 404);
    }

    private void actorLock(UUID actor) {
        // Serializes only this actor's namespace, including cross-kind IDs and impressions.
        // PostgreSQL unique constraints remain the durable safety net.
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 9146))", actor.toString());
    }

    @Transactional
    public FeedResult createResult(UUID actor, UUID academy, String cursor, Integer limit) {
        var page = pages.list(actor, academy, cursor, limit);
        Instant now = micros(clock.instant());
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO behavior_result_context VALUES (?,?,?,?)",
                id,
                actor,
                academy,
                ts(now));
        for (int i = 0; i < page.items().size(); i++)
            jdbc.update(
                    "INSERT INTO behavior_result_item VALUES (?,?,?)",
                    id,
                    i,
                    page.items().get(i).sharedCardId());
        return new FeedResult(
                id,
                now,
                now.plus(Duration.ofHours(24)),
                "LATEST",
                null,
                null,
                page.items(),
                page.nextCursor());
    }

    @Transactional
    public Outcome collect(UUID actor, UUID academy, Event input) {
        actorLock(actor);
        Instant now = micros(clock.instant());
        requireAcademy(actor, academy);
        Event event = authorize(actor, academy, input);
        var existing =
                jdbc.queryForList(
                        "SELECT * FROM behavior_event WHERE actor_id=? AND event_id=? AND"
                            + " received_at>?",
                        actor,
                        event.eventId(),
                        ts(now.minus(Duration.ofDays(90))));
        if (!existing.isEmpty()) {
            var original = existing.getFirst();
            Event saved = readEvent(original);
            requireAcademy(actor, (UUID) original.get("academy_id"));
            authorize(actor, (UUID) original.get("academy_id"), saved);
            if (!academy.equals(original.get("academy_id")) || !saved.equals(event))
                throw new BehaviorException("EVENT_ID_CONFLICT", 409);
            return new Outcome(
                    new Accepted(
                            saved.eventId(),
                            saved.eventType(),
                            saved.occurredAt(),
                            instant(original, "received_at")),
                    true);
        }
        if (event.occurredAt().isBefore(now.minus(Duration.ofHours(24)))
                || event.occurredAt().isAfter(now.plusSeconds(300)))
            throw new BehaviorException("EVENT_TIME_OUT_OF_RANGE", 400);
        if (event.contextId() != null) {
            Instant created =
                    jdbc.queryForObject(
                                    "SELECT created_at FROM behavior_result_context WHERE id=?",
                                    Timestamp.class,
                                    event.contextId())
                            .toInstant();
            if (!now.isBefore(created.plus(Duration.ofHours(24))))
                throw new BehaviorException("FEED_CONTEXT_EXPIRED", 410);
            if (event.occurredAt().isBefore(created.minusSeconds(300)))
                throw new BehaviorException("EVENT_TIME_OUT_OF_RANGE", 400);
            var impressions =
                    jdbc.queryForList(
                            "SELECT * FROM behavior_impression WHERE actor_id=? AND"
                                + " impression_id=?",
                            actor,
                            event.impressionId());
            if (!impressions.isEmpty()) {
                var i = impressions.getFirst();
                if (!event.contextId().equals(i.get("context_id"))
                        || !event.cardId().equals(i.get("card_id"))
                        || !event.position().equals(i.get("position")))
                    throw new BehaviorException("IMPRESSION_CONFLICT", 409);
                if (event.eventType().equals("FEED_EXPOSURE") && i.get("exposed_event_id") != null)
                    throw new BehaviorException("IMPRESSION_ALREADY_EXPOSED", 409);
            } else
                jdbc.update(
                        "INSERT INTO"
                            + " behavior_impression(actor_id,impression_id,context_id,academy_id,position,card_id)"
                            + " VALUES (?,?,?,?,?,?)",
                        actor,
                        event.impressionId(),
                        event.contextId(),
                        academy,
                        event.position(),
                        event.cardId());
            if (event.eventType().equals("FEED_EXPOSURE"))
                jdbc.update(
                        "UPDATE behavior_impression SET exposed_event_id=? WHERE actor_id=? AND"
                            + " impression_id=?",
                        event.eventId(),
                        actor,
                        event.impressionId());
        }
        jdbc.update(
                "DELETE FROM behavior_event WHERE actor_id=? AND event_id=? AND received_at<=?",
                actor,
                event.eventId(),
                ts(now.minus(Duration.ofDays(90))));
        jdbc.update(
                """
INSERT INTO behavior_event(actor_id,event_id,academy_id,event_type,target_id,occurred_at,received_at,context_id,card_id,position,impression_id,click_kind)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
""",
                actor,
                event.eventId(),
                academy,
                event.eventType(),
                event.targetId(),
                ts(event.occurredAt()),
                ts(now),
                event.contextId(),
                event.cardId(),
                event.position(),
                event.impressionId(),
                event.clickKind());
        return new Outcome(
                new Accepted(event.eventId(), event.eventType(), event.occurredAt(), now), false);
    }

    private Event authorize(UUID actor, UUID academy, Event event) {
        if (event.eventType().equals("PROFILE_VISIT")) {
            if (actor.equals(event.targetId()))
                throw new BehaviorException("SELF_PROFILE_VISIT", 400);
            requireProfile(actor, event.targetId(), academy);
            return event;
        }
        var context =
                jdbc.queryForList(
                        """
SELECT c.id FROM behavior_result_context c JOIN behavior_result_item i ON i.context_id=c.id
WHERE c.id=? AND c.actor_id=? AND c.academy_id=? AND i.card_id=? AND i.position=? FOR KEY SHARE OF c
""",
                        event.contextId(),
                        actor,
                        academy,
                        event.cardId(),
                        event.position());
        if (context.isEmpty()) throw new BehaviorException("FEED_CONTEXT_NOT_FOUND", 404);
        var card =
                cards.findVisibleDetail(actor, academy, event.cardId())
                        .filter(c -> !c.ownerId().equals(actor))
                        .orElseThrow(() -> new BehaviorException("SHARED_CARD_NOT_FOUND", 404));
        return new Event(
                event.eventId(),
                event.eventType(),
                event.occurredAt(),
                card.ownerId(),
                event.contextId(),
                event.cardId(),
                event.position(),
                event.impressionId(),
                event.clickKind());
    }

    static Instant instant(Map<String, Object> row, String key) {
        return ((Timestamp) row.get(key)).toInstant();
    }

    static Event readEvent(Map<String, Object> row) {
        return new Event(
                (UUID) row.get("event_id"),
                (String) row.get("event_type"),
                instant(row, "occurred_at"),
                (UUID) row.get("target_id"),
                (UUID) row.get("context_id"),
                (UUID) row.get("card_id"),
                (Integer) row.get("position"),
                (UUID) row.get("impression_id"),
                (String) row.get("click_kind"));
    }

    boolean eligible(Map<String, Object> row) {
        UUID actor = (UUID) row.get("actor_id"),
                academy = (UUID) row.get("academy_id"),
                target = (UUID) row.get("target_id");
        if (!access.canViewAcademyCard(target, actor, academy)) return false;
        if (row.get("context_id") == null) return true;
        return cards.findVisibleDetail(actor, academy, (UUID) row.get("card_id"))
                .filter(c -> c.ownerId().equals(target))
                .isPresent();
    }
}
