-- Activation is durable, independent of traffic and application restarts.
CREATE TABLE behavior_collection (id INTEGER PRIMARY KEY CHECK (id = 1), started_at TIMESTAMPTZ NOT NULL);
INSERT INTO behavior_collection VALUES (1, CURRENT_TIMESTAMP);
CREATE TABLE behavior_result_context (
 id UUID PRIMARY KEY, actor_id UUID NOT NULL REFERENCES student(id),
 academy_id UUID NOT NULL REFERENCES academy(id), created_at TIMESTAMPTZ NOT NULL,
 UNIQUE(id, actor_id, academy_id)
);
CREATE INDEX idx_behavior_context_expiry ON behavior_result_context(created_at);
-- Card identifiers are immutable historical provenance, not a live-card foreign key:
-- deleting a live card must not delete or invalidate retained replay identities.
CREATE TABLE behavior_result_item (
 context_id UUID NOT NULL REFERENCES behavior_result_context(id) ON DELETE CASCADE,
 position INTEGER NOT NULL CHECK(position BETWEEN 0 AND 99), card_id UUID NOT NULL,
 PRIMARY KEY(context_id, position), UNIQUE(context_id, position, card_id)
);
CREATE TABLE behavior_impression (
 actor_id UUID NOT NULL REFERENCES student(id), impression_id UUID NOT NULL,
 context_id UUID NOT NULL, academy_id UUID NOT NULL, position INTEGER NOT NULL, card_id UUID NOT NULL,
 exposed_event_id UUID,
 PRIMARY KEY(actor_id, impression_id),
 UNIQUE(actor_id, impression_id, context_id, academy_id, position, card_id),
 FOREIGN KEY(context_id, actor_id, academy_id) REFERENCES behavior_result_context(id, actor_id, academy_id),
 FOREIGN KEY(context_id, position, card_id) REFERENCES behavior_result_item(context_id, position, card_id)
);
CREATE TABLE behavior_event (
 actor_id UUID NOT NULL REFERENCES student(id), event_id UUID NOT NULL,
 academy_id UUID NOT NULL REFERENCES academy(id), event_type VARCHAR(20) NOT NULL,
 target_id UUID NOT NULL REFERENCES student(id), occurred_at TIMESTAMPTZ NOT NULL, received_at TIMESTAMPTZ NOT NULL,
 context_id UUID, card_id UUID, position INTEGER, impression_id UUID, click_kind VARCHAR(20),
 PRIMARY KEY(actor_id, event_id),
 CHECK(event_type IN ('PROFILE_VISIT','FEED_EXPOSURE','FEED_CLICK')),
 CHECK(actor_id <> target_id),
 CHECK(occurred_at >= received_at - INTERVAL '24 hours' AND occurred_at <= received_at + INTERVAL '5 minutes'),
 CHECK((event_type='PROFILE_VISIT' AND context_id IS NULL AND card_id IS NULL AND position IS NULL AND impression_id IS NULL AND click_kind IS NULL)
    OR (event_type IN ('FEED_EXPOSURE','FEED_CLICK') AND context_id IS NOT NULL AND card_id IS NOT NULL AND position IS NOT NULL AND impression_id IS NOT NULL
        AND ((event_type='FEED_EXPOSURE' AND click_kind IS NULL) OR (event_type='FEED_CLICK' AND click_kind IS NOT NULL AND click_kind='AUTHOR_PROFILE')))),
 FOREIGN KEY(actor_id, impression_id, context_id, academy_id, position, card_id)
   REFERENCES behavior_impression(actor_id, impression_id, context_id, academy_id, position, card_id)
);
CREATE UNIQUE INDEX uk_behavior_exposure ON behavior_event(actor_id, impression_id) WHERE event_type='FEED_EXPOSURE';
CREATE INDEX idx_behavior_incoming ON behavior_event(academy_id, target_id, occurred_at);
CREATE INDEX idx_behavior_outgoing ON behavior_event(academy_id, actor_id, target_id, occurred_at);
CREATE INDEX idx_behavior_period ON behavior_event(academy_id, occurred_at);
CREATE INDEX idx_behavior_retention ON behavior_event(received_at);
CREATE INDEX idx_behavior_event_context ON behavior_event(context_id);
CREATE INDEX idx_behavior_impression_context ON behavior_impression(context_id);
CREATE INDEX idx_behavior_event_impression ON behavior_event(actor_id, impression_id);
