-- Release order: PR62 V17 must precede this migration on shared/deployed databases.
ALTER TABLE recap_generation
    ADD COLUMN stage VARCHAR(16) NOT NULL DEFAULT 'GENERATION',
    ADD COLUMN preparation_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN reservation_key VARCHAR(200),
    ALTER COLUMN input_digest DROP NOT NULL,
    ALTER COLUMN request_json DROP NOT NULL;
ALTER TABLE recap_generation ADD CONSTRAINT ck_recap_stage CHECK (
    (stage = 'PREPARATION' AND input_digest IS NULL AND request_json IS NULL
        AND state IN ('PENDING', 'RUNNING', 'FAILED') AND attempt_count = 0
        AND view_json IS NULL AND internal_metrics_json IS NULL AND generated_at IS NULL AND NOT current_version)
    OR (stage = 'GENERATION' AND input_digest IS NOT NULL AND request_json IS NOT NULL));
ALTER TABLE recap_generation ADD CONSTRAINT ck_recap_preparation_attempt CHECK (preparation_attempt_count BETWEEN 0 AND 3);
CREATE UNIQUE INDEX uk_recap_reservation_key ON recap_generation(reservation_key) WHERE reservation_key IS NOT NULL;
CREATE INDEX idx_recap_preparation_retry ON recap_generation(stage, state, next_attempt_at, started_at)
    WHERE state IN ('PENDING', 'FAILED', 'RUNNING');

CREATE FUNCTION protect_recap_storage() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.account_id, NEW.student_id, NEW.academy_id, NEW.kind, NEW.period_start,
           NEW.period_end_exclusive, NEW.schema_version, NEW.algorithm_version, NEW.generation_version, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.account_id, OLD.student_id, OLD.academy_id, OLD.kind, OLD.period_start,
           OLD.period_end_exclusive, OLD.schema_version, OLD.algorithm_version, OLD.generation_version, OLD.created_at) THEN
        RAISE EXCEPTION 'Recap identity is immutable';
    END IF;
    IF OLD.reservation_key IS NOT NULL AND NEW.reservation_key IS DISTINCT FROM OLD.reservation_key THEN
        RAISE EXCEPTION 'Recap reservation is immutable';
    END IF;
    IF OLD.request_json IS NOT NULL AND ROW(NEW.input_digest, NEW.request_json, NEW.stage)
       IS DISTINCT FROM ROW(OLD.input_digest, OLD.request_json, OLD.stage) THEN
        RAISE EXCEPTION 'Recap input is immutable';
    END IF;
    IF OLD.request_json IS NULL AND NEW.request_json IS NOT NULL
       AND NOT (OLD.stage = 'PREPARATION' AND OLD.state = 'RUNNING' AND NEW.stage = 'GENERATION'
           AND NEW.preparation_attempt_count = OLD.preparation_attempt_count) THEN
        RAISE EXCEPTION 'Recap input requires a preparation claim';
    END IF;
    IF OLD.generated_at IS NOT NULL AND ROW(NEW.view_json, NEW.internal_metrics_json, NEW.generated_at)
       IS DISTINCT FROM ROW(OLD.view_json, OLD.internal_metrics_json, OLD.generated_at) THEN
        RAISE EXCEPTION 'Recap result is immutable';
    END IF;
    IF OLD.generated_at IS NOT NULL AND NEW.state NOT IN (OLD.state, 'SUPERSEDED') THEN
        RAISE EXCEPTION 'Completed recap state cannot be reopened';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_recap_storage_immutable BEFORE UPDATE ON recap_generation
    FOR EACH ROW EXECUTE FUNCTION protect_recap_storage();
