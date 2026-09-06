-- Application timestamps start here. Legacy occurred_at/observed_at values are
-- business provenance and are deliberately never backfilled as application time.
ALTER TABLE ledger_event ADD CONSTRAINT uk_ledger_event_historical_application
    UNIQUE (id, account_id, application_order);

CREATE TABLE historical_ledger_application (
    event_id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    application_order BIGINT NOT NULL CHECK (application_order > 0),
    applied_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_historical_ledger_order UNIQUE (application_order),
    CONSTRAINT fk_historical_ledger_event FOREIGN KEY (event_id, account_id, application_order)
        REFERENCES ledger_event(id, account_id, application_order) DEFERRABLE,
    CONSTRAINT fk_historical_ledger_account FOREIGN KEY (account_id)
        REFERENCES card_balance_account(id) DEFERRABLE
);

CREATE TABLE historical_balance_checkpoint (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    applied_at TIMESTAMPTZ NOT NULL,
    is_baseline BOOLEAN NOT NULL,
    ledger_application_order BIGINT NOT NULL CHECK (ledger_application_order >= 0),
    latest_observation_id UUID,
    last_successful_observation_id UUID,
    observation_lookup_version BIGINT CHECK (observation_lookup_version > 0),
    active_wish_allocation BIGINT NOT NULL CHECK (active_wish_allocation BETWEEN 0 AND 9007199254740991),
    representative_wish_id UUID,
    representative_state VARCHAR(32),
    representative_target_amount BIGINT,
    representative_amount BIGINT,
    active_wishes JSONB NOT NULL CHECK (jsonb_typeof(active_wishes) = 'array'),
    CONSTRAINT uk_historical_checkpoint_account_revision UNIQUE (account_id, revision),
    CONSTRAINT uk_historical_checkpoint_id_account UNIQUE (id, account_id),
    CONSTRAINT fk_historical_checkpoint_account FOREIGN KEY (account_id)
        REFERENCES card_balance_account(id) DEFERRABLE,
    CONSTRAINT fk_historical_checkpoint_latest_observation FOREIGN KEY (latest_observation_id, account_id)
        REFERENCES balance_observation(id, account_id) DEFERRABLE,
    CONSTRAINT fk_historical_checkpoint_success_observation FOREIGN KEY (last_successful_observation_id, account_id)
        REFERENCES balance_observation(id, account_id) DEFERRABLE,
    CONSTRAINT fk_historical_checkpoint_representative FOREIGN KEY (representative_wish_id, account_id)
        REFERENCES wish(id, account_id) DEFERRABLE,
    CONSTRAINT ck_historical_checkpoint_baseline CHECK (is_baseline = (revision = 1)),
    CONSTRAINT ck_historical_checkpoint_representative CHECK (
        (representative_wish_id IS NULL AND representative_state IS NULL
            AND representative_target_amount IS NULL AND representative_amount IS NULL)
        OR (representative_wish_id IS NOT NULL
            AND representative_state IS NOT NULL
            AND representative_target_amount IS NOT NULL AND representative_amount IS NOT NULL
            AND representative_state IN ('IN_PROGRESS', 'AMOUNT_REACHED')
            AND representative_target_amount BETWEEN 1 AND 9007199254740991
            AND representative_amount BETWEEN 0 AND representative_target_amount
            AND (representative_state <> 'AMOUNT_REACHED'
                OR representative_amount = representative_target_amount))
    )
);

CREATE UNIQUE INDEX uk_historical_checkpoint_baseline
    ON historical_balance_checkpoint(account_id) WHERE is_baseline;
CREATE INDEX idx_historical_checkpoint_account_time
    ON historical_balance_checkpoint(account_id, applied_at, revision);
CREATE INDEX idx_historical_ledger_account_order
    ON historical_ledger_application(account_id, application_order);

CREATE FUNCTION validate_historical_checkpoint_snapshot()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    fact JSONB;
    fact_id UUID;
    fact_amount NUMERIC;
    fact_target NUMERIC;
    total NUMERIC := 0;
    seen UUID[] := ARRAY[]::UUID[];
    representative_found BOOLEAN := FALSE;
    actual_lookup_version BIGINT;
BEGIN
    FOR fact IN SELECT value FROM jsonb_array_elements(NEW.active_wishes) LOOP
        IF jsonb_typeof(fact) <> 'object'
            OR NOT fact ?& ARRAY['wishId', 'state', 'targetAmount', 'amount']
            OR (fact - ARRAY['wishId', 'state', 'targetAmount', 'amount']) <> '{}'::jsonb
            OR jsonb_typeof(fact->'wishId') <> 'string'
            OR jsonb_typeof(fact->'state') <> 'string'
            OR jsonb_typeof(fact->'amount') <> 'number'
            OR jsonb_typeof(fact->'targetAmount') <> 'number' THEN
            RAISE EXCEPTION 'Historical active Wish facts must have the exact financial shape' USING ERRCODE = '23514';
        END IF;
        fact_id := (fact->>'wishId')::uuid;
        fact_amount := (fact->>'amount')::numeric;
        fact_target := (fact->>'targetAmount')::numeric;
        IF fact_id = ANY(seen) OR NOT EXISTS (SELECT 1 FROM wish WHERE id = fact_id AND account_id = NEW.account_id)
            OR fact_amount <> trunc(fact_amount) OR fact_target <> trunc(fact_target)
            OR fact_target NOT BETWEEN 1 AND 9007199254740991 OR fact_amount NOT BETWEEN 0 AND fact_target
            OR fact->>'state' NOT IN ('IN_PROGRESS', 'AMOUNT_REACHED')
            OR ((fact->>'state' = 'AMOUNT_REACHED') <> (fact_amount = fact_target)) THEN
            RAISE EXCEPTION 'Historical active Wish facts are inconsistent' USING ERRCODE = '23514';
        END IF;
        seen := array_append(seen, fact_id);
        total := total + fact_amount;
        IF fact_id = NEW.representative_wish_id THEN
            representative_found := TRUE;
            IF fact->>'state' IS DISTINCT FROM NEW.representative_state
                OR fact_amount IS DISTINCT FROM NEW.representative_amount
                OR fact_target IS DISTINCT FROM NEW.representative_target_amount THEN
                RAISE EXCEPTION 'Historical representative does not match its financial fact' USING ERRCODE = '23514';
            END IF;
        END IF;
    END LOOP;
    IF total <> NEW.active_wish_allocation OR (NEW.representative_wish_id IS NOT NULL AND NOT representative_found) THEN
        RAISE EXCEPTION 'Historical allocation or representative snapshot is inconsistent' USING ERRCODE = '23514';
    END IF;
    IF NEW.latest_observation_id IS NULL THEN
        IF NEW.observation_lookup_version IS NOT NULL OR NEW.last_successful_observation_id IS NOT NULL THEN
            RAISE EXCEPTION 'Historical lookup provenance is incomplete' USING ERRCODE = '23514';
        END IF;
    ELSE
        SELECT account_lookup_version INTO actual_lookup_version FROM balance_observation
        WHERE id = NEW.latest_observation_id AND account_id = NEW.account_id;
        IF NOT FOUND OR actual_lookup_version IS DISTINCT FROM NEW.observation_lookup_version THEN
            RAISE EXCEPTION 'Historical lookup provenance does not match its account observation' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.last_successful_observation_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM balance_observation WHERE id = NEW.last_successful_observation_id
            AND account_id = NEW.account_id AND status = 'SUCCEEDED'
    ) THEN
        RAISE EXCEPTION 'Historical successful observation provenance is invalid' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
EXCEPTION WHEN invalid_text_representation THEN
    RAISE EXCEPTION 'Historical active Wish identity is invalid' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER validate_historical_checkpoint_before_insert
BEFORE INSERT ON historical_balance_checkpoint FOR EACH ROW EXECUTE FUNCTION validate_historical_checkpoint_snapshot();

CREATE FUNCTION reject_historical_fact_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Historical financial facts are append-only' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER immutable_historical_checkpoint
BEFORE UPDATE OR DELETE ON historical_balance_checkpoint
FOR EACH ROW EXECUTE FUNCTION reject_historical_fact_mutation();
CREATE TRIGGER immutable_historical_ledger_application
BEFORE UPDATE OR DELETE ON historical_ledger_application
FOR EACH ROW EXECUTE FUNCTION reject_historical_fact_mutation();
CREATE TRIGGER immutable_historical_ledger_event
BEFORE UPDATE OR DELETE ON ledger_event
FOR EACH ROW EXECUTE FUNCTION reject_historical_fact_mutation();
CREATE TRIGGER immutable_historical_ledger_wish_effect
BEFORE UPDATE OR DELETE ON ledger_wish_effect
FOR EACH ROW EXECUTE FUNCTION reject_historical_fact_mutation();
CREATE TRIGGER immutable_historical_observation
BEFORE UPDATE OR DELETE ON balance_observation
FOR EACH ROW EXECUTE FUNCTION reject_historical_fact_mutation();

CREATE FUNCTION preserve_historical_account_identity()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.student_id, NEW.academy_id, NEW.opened_at)
       IS DISTINCT FROM ROW(OLD.id, OLD.student_id, OLD.academy_id, OLD.opened_at) THEN
        RAISE EXCEPTION 'Historical account identity and opening time are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER immutable_historical_account_identity
BEFORE UPDATE ON card_balance_account FOR EACH ROW EXECUTE FUNCTION preserve_historical_account_identity();

-- Called only by write-side deferred triggers and the activation migration.
-- Re-reading the complete account after every queued trigger deliberately
-- coalesces one transaction into its final state, including both transfer sides
-- and automatic representative replacement. It cannot expose a half-operation.
CREATE FUNCTION record_historical_balance_checkpoint(target_account UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    previous historical_balance_checkpoint%ROWTYPE;
    latest_observation balance_observation%ROWTYPE;
    latest_success UUID;
    selected wish%ROWTYPE;
    allocation NUMERIC;
    wishes JSONB;
    ledger_order BIGINT;
    application_time TIMESTAMPTZ;
BEGIN
    PERFORM 1 FROM card_balance_account WHERE id = target_account FOR UPDATE;
    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT * INTO previous FROM historical_balance_checkpoint
    WHERE account_id = target_account ORDER BY revision DESC LIMIT 1;
    SELECT COALESCE(sum(wish_amount), 0), COALESCE(jsonb_agg(jsonb_build_object(
        'wishId', id, 'state', state, 'targetAmount', target_amount, 'amount', wish_amount
    ) ORDER BY id), '[]'::jsonb)
    INTO allocation, wishes
    FROM wish WHERE account_id = target_account
        AND deleted_at IS NULL AND state IN ('IN_PROGRESS', 'AMOUNT_REACHED');
    IF allocation > 9007199254740991 THEN
        RAISE EXCEPTION 'Historical allocation exceeds the supported money range' USING ERRCODE = '23514';
    END IF;
    SELECT w.* INTO selected FROM representative_wish_selection s
    JOIN wish w ON w.id = s.wish_id AND w.account_id = s.account_id
    WHERE s.account_id = target_account;
    IF selected.id IS NOT NULL AND (selected.deleted_at IS NOT NULL
        OR selected.state NOT IN ('IN_PROGRESS', 'AMOUNT_REACHED')) THEN
        RAISE EXCEPTION 'Historical representative must be active' USING ERRCODE = '23514';
    END IF;
    SELECT observation.* INTO latest_observation FROM balance_observation observation
    WHERE observation.account_id = target_account
      AND (observation.status <> 'SUCCEEDED' OR NOT EXISTS (
          SELECT 1 FROM balance_observation successor
          WHERE successor.previous_successful_observation_id = observation.id))
    ORDER BY observation.account_lookup_version DESC NULLS LAST, observation.observed_at DESC, observation.id DESC LIMIT 1;
    SELECT observation.id INTO latest_success FROM balance_observation observation
    WHERE observation.account_id = target_account AND observation.status = 'SUCCEEDED'
      AND NOT EXISTS (SELECT 1 FROM balance_observation successor
          WHERE successor.previous_successful_observation_id = observation.id)
    ORDER BY observation.account_lookup_version DESC NULLS LAST, observation.observed_at DESC, observation.id DESC LIMIT 1;
    SELECT COALESCE(max(application_order), 0) INTO ledger_order
    FROM ledger_event WHERE account_id = target_account;

    IF previous.id IS NOT NULL
       AND previous.ledger_application_order = ledger_order
       AND previous.latest_observation_id IS NOT DISTINCT FROM latest_observation.id
       AND previous.last_successful_observation_id IS NOT DISTINCT FROM latest_success
       AND previous.observation_lookup_version IS NOT DISTINCT FROM latest_observation.account_lookup_version
       AND previous.active_wish_allocation = allocation
       AND previous.representative_wish_id IS NOT DISTINCT FROM selected.id
       AND previous.representative_state IS NOT DISTINCT FROM selected.state
       AND previous.representative_target_amount IS NOT DISTINCT FROM selected.target_amount
       AND previous.representative_amount IS NOT DISTINCT FROM selected.wish_amount
       AND previous.active_wishes = wishes THEN
        RETURN;
    END IF;
    -- clock_timestamp is deliberately acquired after the account lock; a caller's
    -- pre-lock now/occurredAt/observedAt must never determine collection coverage.
    application_time := GREATEST(clock_timestamp(), previous.applied_at,
        (SELECT max(applied_at) FROM historical_ledger_application
         WHERE account_id = target_account AND application_order <= ledger_order));
    INSERT INTO historical_balance_checkpoint (
        id, account_id, revision, applied_at, is_baseline, ledger_application_order,
        latest_observation_id, last_successful_observation_id, observation_lookup_version,
        active_wish_allocation, representative_wish_id, representative_state,
        representative_target_amount, representative_amount, active_wishes
    ) VALUES (
        gen_random_uuid(), target_account, COALESCE(previous.revision, 0) + 1,
        application_time, previous.id IS NULL, ledger_order,
        latest_observation.id, latest_success, latest_observation.account_lookup_version,
        allocation::bigint, selected.id, selected.state, selected.target_amount,
        selected.wish_amount, wishes
    );
END;
$$;

-- Initial state is observed at activation, never backdated to account opening.
-- Existing ledger rows are admitted only by the baseline watermark and snapshot;
-- no historical_ledger_application rows are fabricated for them.
DO $$
DECLARE target_account UUID;
BEGIN
    FOR target_account IN SELECT id FROM card_balance_account ORDER BY id LOOP
        PERFORM record_historical_balance_checkpoint(target_account);
    END LOOP;
END;
$$;

CREATE FUNCTION queue_historical_balance_checkpoint()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'card_balance_account' THEN
        PERFORM record_historical_balance_checkpoint(NEW.id);
    ELSE
        PERFORM record_historical_balance_checkpoint(COALESCE(NEW.account_id, OLD.account_id));
        IF TG_OP = 'UPDATE' AND OLD.account_id IS DISTINCT FROM NEW.account_id THEN
            PERFORM record_historical_balance_checkpoint(OLD.account_id);
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER historical_account_creation_checkpoint
AFTER INSERT OR UPDATE ON card_balance_account DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION queue_historical_balance_checkpoint();
CREATE CONSTRAINT TRIGGER historical_wish_checkpoint
AFTER INSERT OR UPDATE OR DELETE ON wish DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION queue_historical_balance_checkpoint();
CREATE CONSTRAINT TRIGGER historical_representative_checkpoint
AFTER INSERT OR UPDATE OR DELETE ON representative_wish_selection DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION queue_historical_balance_checkpoint();
CREATE CONSTRAINT TRIGGER historical_observation_checkpoint
AFTER INSERT ON balance_observation DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION queue_historical_balance_checkpoint();
CREATE CONSTRAINT TRIGGER historical_ledger_checkpoint
AFTER INSERT ON ledger_event DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION queue_historical_balance_checkpoint();
CREATE CONSTRAINT TRIGGER historical_ledger_effect_checkpoint
AFTER INSERT ON ledger_wish_effect DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION queue_historical_balance_checkpoint();

CREATE FUNCTION record_historical_ledger_application()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM card_balance_account WHERE id = NEW.account_id FOR UPDATE;
    INSERT INTO historical_ledger_application(event_id, account_id, application_order, applied_at)
    VALUES (NEW.id, NEW.account_id, NEW.application_order,
        GREATEST(clock_timestamp(),
            (SELECT max(applied_at) FROM historical_balance_checkpoint WHERE account_id = NEW.account_id),
            (SELECT max(applied_at) FROM historical_ledger_application WHERE account_id = NEW.account_id)));
    RETURN NULL;
END;
$$;

-- Allocate after locking the account so even direct SQL writers cannot invert
-- per-account application order while waiting behind a concurrent commit.
-- The trigger is the sole allocator; rolled-back sequence gaps have no meaning.
CREATE FUNCTION order_historical_ledger_application()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM card_balance_account WHERE id = NEW.account_id FOR UPDATE;
    NEW.application_order := nextval('ledger_event_application_order_seq');
    RETURN NEW;
END;
$$;

CREATE TRIGGER historical_ledger_order_before_insert
BEFORE INSERT ON ledger_event FOR EACH ROW EXECUTE FUNCTION order_historical_ledger_application();

ALTER TABLE ledger_event ALTER COLUMN application_order DROP DEFAULT;

CREATE TRIGGER historical_ledger_application_after_insert
AFTER INSERT ON ledger_event FOR EACH ROW EXECUTE FUNCTION record_historical_ledger_application();

CREATE FUNCTION reject_late_historical_ledger_effect()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM ledger_event e JOIN historical_balance_checkpoint c
          ON c.account_id = e.account_id AND c.ledger_application_order >= e.application_order
        WHERE e.id = NEW.event_id
    ) THEN
        RAISE EXCEPTION 'Cannot append an effect to an already checkpointed ledger event' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER historical_ledger_effect_before_insert
BEFORE INSERT ON ledger_wish_effect FOR EACH ROW EXECUTE FUNCTION reject_late_historical_ledger_effect();
