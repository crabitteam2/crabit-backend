ALTER TABLE student ADD COLUMN age_provenance VARCHAR(16) NOT NULL DEFAULT 'LEGACY_UUID';
ALTER TABLE student ADD CONSTRAINT ck_student_age_provenance
    CHECK (age_provenance IN ('LEGACY_UUID', 'PROVIDED'));

CREATE TABLE recap_generation (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    student_id UUID NOT NULL,
    academy_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL,
    period_start DATE NOT NULL,
    period_end_exclusive DATE NOT NULL,
    schema_version INTEGER NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    generation_version BIGINT NOT NULL,
    input_digest VARCHAR(71) NOT NULL,
    request_json TEXT NOT NULL,
    view_json TEXT,
    internal_metrics_json TEXT,
    state VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    generated_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    error_retryable BOOLEAN,
    current_version BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_recap_generation_account FOREIGN KEY (account_id)
        REFERENCES card_balance_account(id) DEFERRABLE,
    CONSTRAINT fk_recap_generation_student FOREIGN KEY (student_id)
        REFERENCES student(id) DEFERRABLE,
    CONSTRAINT fk_recap_generation_academy FOREIGN KEY (academy_id)
        REFERENCES academy(id) DEFERRABLE,
    CONSTRAINT ck_recap_kind CHECK (kind IN ('WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_recap_period CHECK (period_end_exclusive > period_start),
    CONSTRAINT ck_recap_schema CHECK (schema_version = 1),
    CONSTRAINT ck_recap_algorithm CHECK (algorithm_version = 'recap-1'),
    CONSTRAINT ck_recap_generation_version CHECK (generation_version > 0),
    CONSTRAINT ck_recap_input_digest CHECK (input_digest LIKE 'sha256:%'),
    CONSTRAINT ck_recap_state CHECK (state IN (
        'PENDING', 'RUNNING', 'NOT_ELIGIBLE', 'FAILED', 'SUCCEEDED', 'SUPERSEDED')),
    CONSTRAINT ck_recap_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_recap_result_state CHECK (
        (state = 'SUCCEEDED' AND view_json IS NOT NULL AND generated_at IS NOT NULL)
        OR (state = 'NOT_ELIGIBLE' AND view_json IS NULL AND generated_at IS NOT NULL)
        OR (state IN ('PENDING', 'RUNNING', 'FAILED', 'SUPERSEDED'))),
    CONSTRAINT uk_recap_generation_logical_version UNIQUE
        (account_id, kind, period_start, period_end_exclusive, algorithm_version, generation_version),
    CONSTRAINT uk_recap_generation_id_input UNIQUE (id, input_digest)
);

CREATE UNIQUE INDEX uk_recap_generation_current
    ON recap_generation(account_id, kind, period_start, period_end_exclusive, algorithm_version)
    WHERE current_version = TRUE;
CREATE INDEX idx_recap_generation_lookup
    ON recap_generation(account_id, kind, period_start, period_end_exclusive, generation_version DESC);
CREATE INDEX idx_recap_generation_retry
    ON recap_generation(state, next_attempt_at) WHERE state IN ('PENDING', 'FAILED');
