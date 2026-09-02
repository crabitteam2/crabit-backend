package com.crabit.backend.behavior;

import com.crabit.backend.wish.SharedCardProjection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BehaviorModels {
    private BehaviorModels() {}

    public record Event(
            UUID eventId,
            String eventType,
            Instant occurredAt,
            UUID targetId,
            UUID contextId,
            UUID cardId,
            Integer position,
            UUID impressionId,
            String clickKind) {}

    public record Accepted(
            UUID eventId, String eventType, Instant occurredAt, Instant receivedAt) {}

    public record Outcome(Accepted body, boolean replayed) {}

    public record FeedResult(
            UUID resultContextId,
            Instant createdAt,
            Instant expiresAt,
            String sortSource,
            String recommendationResultId,
            String modelVersion,
            List<SharedCardProjection> items,
            String nextCursor) {}
}
