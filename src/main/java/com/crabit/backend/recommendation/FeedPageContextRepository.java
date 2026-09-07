package com.crabit.backend.recommendation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Durable, serialized v2 input-state transitions; authorization remains a caller concern. */
@Repository
public class FeedPageContextRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public FeedPageContextRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public InitialState create(UUID viewer, UUID academy, Instant createdAt,
            UUID requestId, String modelVersion, List<UUID> rankedIds) {
        return create(UUID.randomUUID(), viewer, academy, createdAt, requestId, modelVersion, rankedIds);
    }

    @Transactional
    public InitialState create(UUID context, UUID viewer, UUID academy, Instant createdAt,
            UUID requestId, String modelVersion, List<UUID> rankedIds) {
        UUID state = UUID.randomUUID();
        String outcome = requestId == null ? "LATEST" : "RECOMMENDATION";
        try {
            jdbc.update("INSERT INTO feed_page_context(id,viewer_id,academy_id,created_at,expires_at,"
                            + "recommendation_request_id,model_version,ranked_card_ids,ranking_outcome)"
                            + " VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
                    context, viewer, academy, Timestamp.from(createdAt),
                    Timestamp.from(createdAt.plusSeconds(300)), requestId, modelVersion,
                    encode(rankedIds), outcome);
            jdbc.update("INSERT INTO feed_page_state VALUES (?,?,0,NULL,NULL,?::jsonb)",
                    state, context, "[]");
            return new InitialState(context, state, createdAt.plusSeconds(300));
        } catch (DataAccessResourceFailureException | TransientDataAccessException failure) {
            throw new ContextUnavailable(failure);
        }
    }

    /**
     * Locks one input state. Before returning, the callback must finish all failure-prone
     * authorization/projection/photo work and any POST result-position writes. A callback
     * failure rolls the transaction back, leaving the cursor unconsumed.
     */
    @Transactional
    public Transition transition(UUID stateId, UUID expectedContextId, Instant expectedExpiresAt,
            UUID viewer, UUID academy, Instant now,
            int limit, java.util.function.Function<State, Page> producer) {
        return transition(stateId, expectedContextId, expectedExpiresAt, viewer, academy, now,
                limit, producer, (state, replay) -> {});
    }

    @Transactional
    public Transition transition(UUID stateId, UUID expectedContextId, Instant expectedExpiresAt,
            UUID viewer, UUID academy, Instant now, int limit,
            java.util.function.Function<State, Page> producer,
            java.util.function.BiConsumer<State, Transition> replayValidator) {
        try {
            return doTransition(stateId, expectedContextId, expectedExpiresAt, viewer, academy,
                    now, limit, producer, replayValidator);
        } catch (DataAccessResourceFailureException | TransientDataAccessException failure) {
            throw new ContextUnavailable(failure);
        }
    }

    private Transition doTransition(UUID stateId, UUID expectedContextId, Instant expectedExpiresAt,
            UUID viewer, UUID academy, Instant now, int limit,
            java.util.function.Function<State, Page> producer,
            java.util.function.BiConsumer<State, Transition> replayValidator) {
        List<State> states = jdbc.query("""
                SELECT s.context_id,s.ranked_offset,s.latest_updated_at,s.latest_card_id,
                       s.returned_card_ids::text,c.expires_at,c.ranked_card_ids::text,
                       c.recommendation_request_id,c.model_version
                FROM feed_page_state s JOIN feed_page_context c ON c.id=s.context_id
                WHERE s.id=? AND s.context_id=? AND c.expires_at=?
                  AND c.viewer_id=? AND c.academy_id=? FOR UPDATE OF s
                """, (rs, ignored) -> new State(stateId, rs.getObject(1, UUID.class),
                rs.getInt(2), instant(rs.getTimestamp(3)), rs.getObject(4, UUID.class),
                decode(rs.getString(5)), rs.getTimestamp(6).toInstant(), decode(rs.getString(7)),
                rs.getObject(8, UUID.class), rs.getString(9), null), stateId, expectedContextId,
                Timestamp.from(expectedExpiresAt), viewer, academy);
        if (states.isEmpty() || !now.isBefore(states.getFirst().expiresAt())) throw new ContextExpired();
        List<Transition> replay = readTransition(stateId);
        if (!replay.isEmpty()) {
            Transition fixed = replay.getFirst();
            if (fixed.limit() != limit) throw new LimitReplayMismatch();
            replayValidator.accept(states.getFirst(), fixed);
            return fixed;
        }
        Page page = producer.apply(states.getFirst());
        UUID successor = page.hasMore() ? UUID.randomUUID() : null;
        if (successor != null) jdbc.update("INSERT INTO feed_page_state VALUES (?,?,?,?,?,?::jsonb)",
                successor, states.getFirst().contextId(), page.rankedOffset(),
                timestamp(page.latestUpdatedAt()), page.latestCardId(), encode(page.returnedIds()));
        jdbc.update("INSERT INTO feed_page_transition VALUES (?,?,?::jsonb,?,?,?)",
                stateId, limit, encode(page.itemIds()), successor, page.hasRankedItems(), Timestamp.from(now));
        return new Transition(limit, page.itemIds(), successor, page.hasRankedItems());
    }

    private List<Transition> readTransition(UUID state) {
        return jdbc.query("SELECT requested_limit,item_ids::text,successor_state_id,has_ranked_items"
                        + " FROM feed_page_transition WHERE input_state_id=?",
                (rs, ignored) -> new Transition(rs.getInt(1), decode(rs.getString(2)),
                        rs.getObject(3, UUID.class), rs.getBoolean(4)), state);
    }

    public UUID requestId(UUID state) {
        try {
            return jdbc.queryForObject("SELECT c.recommendation_request_id FROM feed_page_state s"
                    + " JOIN feed_page_context c ON c.id=s.context_id WHERE s.id=?", UUID.class, state);
        } catch (DataAccessResourceFailureException | TransientDataAccessException failure) {
            throw new ContextUnavailable(failure);
        }
    }

    public List<UUID> rankedIds(UUID state) {
        try {
            return decode(jdbc.queryForObject("SELECT c.ranked_card_ids::text FROM feed_page_state s"
                    + " JOIN feed_page_context c ON c.id=s.context_id WHERE s.id=?", String.class, state));
        } catch (DataAccessResourceFailureException | TransientDataAccessException failure) {
            throw new ContextUnavailable(failure);
        }
    }

    private String encode(List<UUID> ids) { return json.writeValueAsString(ids); }
    private List<UUID> decode(String value) {
        return json.readTree(value).valueStream().map(node -> UUID.fromString(node.stringValue())).toList();
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    public record InitialState(UUID contextId, UUID stateId, Instant expiresAt) {}
    public record State(UUID stateId, UUID contextId, int rankedOffset, Instant latestUpdatedAt,
            UUID latestCardId, List<UUID> returnedIds, Instant expiresAt, List<UUID> rankedIds,
            UUID recommendationRequestId, String modelVersion, List<UUID> replayIds) {}
    public record Page(List<UUID> itemIds, int rankedOffset, Instant latestUpdatedAt,
            UUID latestCardId, List<UUID> returnedIds, boolean hasRankedItems, boolean hasMore) {}
    public record Transition(int limit, List<UUID> itemIds, UUID successorStateId,
            boolean hasRankedItems) {}
    public static final class ContextExpired extends RuntimeException {}
    public static final class LimitReplayMismatch extends RuntimeException {}
    public static final class ContextUnavailable extends RuntimeException {
        ContextUnavailable(Throwable cause) { super(cause); }
    }
}
