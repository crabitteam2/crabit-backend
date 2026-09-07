-- History begins at deployment, never at backdated business timestamps.
-- Source-table locks also serialize baseline creation with live mutations.
LOCK TABLE academy_membership, card_balance_account, shared_card, student_block,
    student_follow, wish IN SHARE ROW EXCLUSIVE MODE;
CREATE TABLE feed_history_collection (
    id INTEGER PRIMARY KEY CHECK (id = 1), started_at TIMESTAMPTZ NOT NULL
);
INSERT INTO feed_history_collection VALUES (1, clock_timestamp());
CREATE TABLE feed_source_history (
    version BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_kind TEXT NOT NULL,
    source_id UUID NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    payload JSONB NOT NULL,
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE UNIQUE INDEX uk_feed_source_current ON feed_source_history(source_kind, source_id)
    WHERE valid_to IS NULL;
CREATE INDEX idx_feed_source_interval ON feed_source_history(source_kind, valid_from, valid_to);

CREATE FUNCTION feed_record_source_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE boundary TIMESTAMPTZ := clock_timestamp(); row_id UUID;
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        DELETE FROM feed_source_history WHERE source_kind = TG_TABLE_NAME;
        UPDATE feed_history_collection SET started_at = boundary WHERE id = 1;
        RETURN NULL;
    END IF;
    IF TG_OP = 'INSERT' THEN row_id := NEW.id; ELSE row_id := OLD.id; END IF;
    -- PostgreSQL row locks serialize all versions of this source identity.
    UPDATE feed_source_history SET valid_to = boundary
      WHERE source_kind = TG_TABLE_NAME AND source_id = row_id AND valid_to IS NULL;
    IF TG_OP <> 'DELETE' THEN
        INSERT INTO feed_source_history(source_kind,source_id,valid_from,payload)
          VALUES (TG_TABLE_NAME,NEW.id,boundary,to_jsonb(NEW));
    END IF;
    RETURN NULL;
END $$;
DO $$
DECLARE source_name TEXT;
BEGIN
    FOREACH source_name IN ARRAY ARRAY['academy_membership','card_balance_account',
      'shared_card','student_block','student_follow','wish'] LOOP
        EXECUTE format('INSERT INTO feed_source_history(source_kind,source_id,valid_from,payload)
          SELECT %L,id,(SELECT started_at FROM feed_history_collection WHERE id=1),to_jsonb(s)
          FROM %I s',source_name,source_name);
        EXECUTE format('CREATE TRIGGER feed_history_row AFTER INSERT OR UPDATE OR DELETE ON %I
          FOR EACH ROW EXECUTE FUNCTION feed_record_source_history()',source_name);
        EXECUTE format('CREATE TRIGGER feed_history_reset AFTER TRUNCATE ON %I
          FOR EACH STATEMENT EXECUTE FUNCTION feed_record_source_history()',source_name);
    END LOOP;
END $$;

-- Deliberately no live wish/card foreign keys: unsharing/deleting cannot erase interest.
CREATE TABLE feed_visit_evidence (
    actor_id UUID NOT NULL, event_id UUID NOT NULL, target_author_id UUID NOT NULL,
    academy_id UUID NOT NULL, occurred_at TIMESTAMPTZ NOT NULL, received_at TIMESTAMPTZ NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL, evidence_status TEXT NOT NULL,
    category_ids JSONB, evidence_version TEXT NOT NULL DEFAULT 'feed-visit-evidence-v1',
    classifier_version TEXT NOT NULL, history_coverage_start TIMESTAMPTZ NOT NULL,
    source_versions JSONB NOT NULL, unknown_reason TEXT,
    PRIMARY KEY(actor_id,event_id),
    CHECK (evidence_version = 'feed-visit-evidence-v1'),
    CHECK (jsonb_typeof(source_versions) = 'array' AND jsonb_array_length(source_versions) > 0),
    CHECK ((evidence_status='COMPLETE' AND jsonb_typeof(category_ids)='array' AND unknown_reason IS NULL)
      OR (evidence_status='UNKNOWN' AND category_ids IS NULL AND unknown_reason IN
        ('LEGACY','BEFORE_BASELINE','FUTURE_OCCURRED_AT','HISTORY_GAP','CLASSIFIER_UNAVAILABLE')))
);
CREATE INDEX idx_feed_visit_signal ON feed_visit_evidence(actor_id,academy_id,occurred_at);
CREATE INDEX idx_feed_visit_retention ON feed_visit_evidence((greatest(received_at,occurred_at)));
-- Existing accepted events have no provable visit-time category snapshot. Their first
-- evidence is permanently unknown, even when their business timestamp postdates baseline.
INSERT INTO feed_visit_evidence(actor_id,event_id,target_author_id,academy_id,occurred_at,received_at,
    captured_at,evidence_status,category_ids,classifier_version,history_coverage_start,source_versions,unknown_reason)
SELECT e.actor_id,e.event_id,e.target_id,e.academy_id,e.occurred_at,e.received_at,
    c.started_at,'UNKNOWN',NULL,
    'wish-category-v1@sha256:de23b80260907e3d818892c0ea6ba2d9d28251e49d75a812fb925e1a47733f61',
    c.started_at,jsonb_build_array('baseline:' || c.started_at::text),'LEGACY'
FROM behavior_event e CROSS JOIN feed_history_collection c WHERE e.event_type='PROFILE_VISIT';
CREATE FUNCTION feed_preserve_visit_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Feed visit evidence is immutable'; END $$;
CREATE TRIGGER feed_visit_immutable BEFORE UPDATE ON feed_visit_evidence
    FOR EACH ROW EXECUTE FUNCTION feed_preserve_visit_evidence();
CREATE FUNCTION feed_reset_visit_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN DELETE FROM feed_visit_evidence; RETURN NULL; END $$;
CREATE TRIGGER feed_visit_fixture_reset AFTER TRUNCATE ON behavior_event
    FOR EACH STATEMENT EXECUTE FUNCTION feed_reset_visit_evidence();

-- Five-minute recommendation traversal state. State/transition identities are random and
-- are additionally bound by the authenticated cursor; these rows never authorize a card.
CREATE TABLE feed_page_context (
    id UUID PRIMARY KEY, viewer_id UUID NOT NULL, academy_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
    recommendation_request_id UUID, model_version TEXT,
    ranked_card_ids JSONB NOT NULL, ranking_outcome TEXT NOT NULL,
    CHECK (expires_at = created_at + interval '5 minutes'),
    CHECK (jsonb_typeof(ranked_card_ids) = 'array'),
    CHECK ((ranking_outcome='RECOMMENDATION' AND recommendation_request_id IS NOT NULL
      AND model_version='feed-rules-v1') OR (ranking_outcome='LATEST'
      AND recommendation_request_id IS NULL AND model_version IS NULL))
);
CREATE INDEX idx_feed_page_context_expiry ON feed_page_context(expires_at);
CREATE TABLE feed_page_state (
    id UUID PRIMARY KEY, context_id UUID NOT NULL REFERENCES feed_page_context(id) ON DELETE CASCADE,
    ranked_offset INTEGER NOT NULL CHECK (ranked_offset >= 0),
    latest_updated_at TIMESTAMPTZ, latest_card_id UUID,
    returned_card_ids JSONB NOT NULL,
    CHECK (jsonb_typeof(returned_card_ids) = 'array'),
    CHECK ((latest_updated_at IS NULL) = (latest_card_id IS NULL))
);
CREATE TABLE feed_page_transition (
    input_state_id UUID PRIMARY KEY REFERENCES feed_page_state(id) ON DELETE CASCADE,
    requested_limit INTEGER NOT NULL CHECK (requested_limit BETWEEN 1 AND 100),
    item_ids JSONB NOT NULL, successor_state_id UUID REFERENCES feed_page_state(id),
    has_ranked_items BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL,
    CHECK (jsonb_typeof(item_ids) = 'array')
);
