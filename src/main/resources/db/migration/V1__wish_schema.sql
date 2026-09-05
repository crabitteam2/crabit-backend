CREATE TABLE academy (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL
);

CREATE TABLE student (
    id UUID PRIMARY KEY,
    nickname VARCHAR(80) NOT NULL
);

CREATE TABLE academy_membership (
    id UUID PRIMARY KEY,
    student_id UUID NOT NULL,
    academy_id UUID NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    left_at TIMESTAMPTZ,
    CONSTRAINT uk_membership_student_academy UNIQUE (student_id, academy_id),
    CONSTRAINT ck_membership_period CHECK (left_at IS NULL OR left_at >= joined_at),
    CONSTRAINT fk_membership_student FOREIGN KEY (student_id) REFERENCES student (id) DEFERRABLE,
    CONSTRAINT fk_membership_academy FOREIGN KEY (academy_id) REFERENCES academy (id) DEFERRABLE
);

CREATE TABLE friendship (
    id UUID PRIMARY KEY,
    academy_id UUID NOT NULL,
    student_low_id UUID NOT NULL,
    student_high_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    CONSTRAINT uk_friendship_academy_pair UNIQUE (academy_id, student_low_id, student_high_id),
    CONSTRAINT ck_friendship_canonical_pair CHECK (student_low_id < student_high_id),
    CONSTRAINT ck_friendship_period CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT fk_friendship_academy FOREIGN KEY (academy_id) REFERENCES academy (id) DEFERRABLE,
    CONSTRAINT fk_friendship_low_student FOREIGN KEY (student_low_id) REFERENCES student (id) DEFERRABLE,
    CONSTRAINT fk_friendship_high_student FOREIGN KEY (student_high_id) REFERENCES student (id) DEFERRABLE,
    CONSTRAINT fk_friendship_low_membership FOREIGN KEY (student_low_id, academy_id)
        REFERENCES academy_membership (student_id, academy_id) DEFERRABLE,
    CONSTRAINT fk_friendship_high_membership FOREIGN KEY (student_high_id, academy_id)
        REFERENCES academy_membership (student_id, academy_id) DEFERRABLE
);

CREATE TABLE student_block (
    id UUID PRIMARY KEY,
    blocker_id UUID NOT NULL,
    blocked_id UUID NOT NULL,
    blocked_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    CONSTRAINT uk_block_direction UNIQUE (blocker_id, blocked_id),
    CONSTRAINT ck_block_distinct_students CHECK (blocker_id <> blocked_id),
    CONSTRAINT ck_block_period CHECK (released_at IS NULL OR released_at >= blocked_at),
    CONSTRAINT fk_block_blocker FOREIGN KEY (blocker_id) REFERENCES student (id) DEFERRABLE,
    CONSTRAINT fk_block_blocked FOREIGN KEY (blocked_id) REFERENCES student (id) DEFERRABLE
);

CREATE TABLE card_balance_account (
    id UUID PRIMARY KEY,
    student_id UUID NOT NULL,
    academy_id UUID NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    balance_lookup_version BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_card_account_id_academy UNIQUE (id, academy_id),
    CONSTRAINT ck_card_account_period CHECK (closed_at IS NULL OR closed_at >= opened_at),
    CONSTRAINT ck_card_account_lookup_version CHECK (balance_lookup_version >= 0),
    CONSTRAINT ck_card_account_version CHECK (version >= 0),
    CONSTRAINT fk_card_account_student FOREIGN KEY (student_id) REFERENCES student (id) DEFERRABLE,
    CONSTRAINT fk_card_account_academy FOREIGN KEY (academy_id) REFERENCES academy (id) DEFERRABLE
);

CREATE INDEX idx_card_account_student_academy
    ON card_balance_account (student_id, academy_id);
CREATE UNIQUE INDEX uk_card_account_active
    ON card_balance_account (student_id, academy_id) WHERE closed_at IS NULL;

CREATE TABLE wish (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    academy_id UUID NOT NULL,
    purpose VARCHAR(200) NOT NULL,
    target_amount BIGINT NOT NULL,
    wish_amount BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    target_date DATE,
    completed_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    deleted_purpose_snapshot VARCHAR(200),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_wish_id_account UNIQUE (id, account_id),
    CONSTRAINT ck_wish_state CHECK (state IN ('IN_PROGRESS', 'AMOUNT_REACHED', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_wish_visibility CHECK (visibility IN ('PRIVATE', 'FRIENDS', 'ACADEMY')),
    CONSTRAINT ck_wish_target_positive CHECK (target_amount > 0),
    CONSTRAINT ck_wish_amount_bounds CHECK (wish_amount >= 0 AND wish_amount <= target_amount),
    CONSTRAINT ck_wish_state_amount CHECK (
        deleted_at IS NOT NULL OR
        (state = 'IN_PROGRESS' AND wish_amount < target_amount) OR
        (state = 'AMOUNT_REACHED' AND wish_amount = target_amount) OR
        (state IN ('COMPLETED', 'ABANDONED') AND wish_amount = 0)
    ),
    CONSTRAINT ck_wish_tombstone_pair CHECK (
        (deleted_at IS NULL AND deleted_purpose_snapshot IS NULL) OR
        (deleted_at IS NOT NULL AND deleted_purpose_snapshot IS NOT NULL)
    ),
    CONSTRAINT ck_wish_deleted_amount CHECK (deleted_at IS NULL OR wish_amount = 0),
    CONSTRAINT ck_wish_completion_time CHECK (
        (state = 'COMPLETED' AND completed_at IS NOT NULL AND completed_at >= created_at) OR
        (state <> 'COMPLETED' AND completed_at IS NULL)
    ),
    CONSTRAINT ck_wish_version CHECK (version >= 0),
    CONSTRAINT fk_wish_account_academy FOREIGN KEY (account_id, academy_id)
        REFERENCES card_balance_account (id, academy_id) DEFERRABLE,
    CONSTRAINT fk_wish_academy FOREIGN KEY (academy_id) REFERENCES academy (id) DEFERRABLE
);

CREATE INDEX idx_wish_account_state ON wish (account_id, state);
CREATE INDEX idx_wish_active_lookup ON wish (account_id, deleted_at, state);

CREATE TABLE ledger_event (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    account_delta BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    deposit_balance_observation_id UUID,
    deposit_observation_status VARCHAR(16),
    deposit_observation_lookup_method VARCHAR(24),
    correction_of_event_id UUID,
    CONSTRAINT uk_ledger_event_id_account UNIQUE (id, account_id),
    CONSTRAINT uk_ledger_event_observation_proof UNIQUE
        (id, account_id, event_type, account_delta, occurred_at),
    CONSTRAINT uk_ledger_event_deposit_observation UNIQUE (deposit_balance_observation_id),
    CONSTRAINT ck_ledger_event_type CHECK (event_type IN (
        'CARD_BALANCE_CHANGE', 'WISH_DEPOSIT', 'WISH_WITHDRAWAL', 'WISH_TRANSFER',
        'WISH_COMPLETION_RETURN', 'WISH_ABANDONMENT_RETURN', 'WISH_DELETION_RETURN'
    )),
    CONSTRAINT ck_ledger_event_deposit_observation CHECK (
        (event_type = 'WISH_DEPOSIT'
            AND deposit_balance_observation_id IS NOT NULL
            AND deposit_observation_status = 'SUCCEEDED'
            AND deposit_observation_lookup_method = 'PRE_DEPOSIT')
        OR
        (event_type <> 'WISH_DEPOSIT'
            AND deposit_balance_observation_id IS NULL
            AND deposit_observation_status IS NULL
            AND deposit_observation_lookup_method IS NULL)
    ),
    CONSTRAINT fk_ledger_event_account FOREIGN KEY (account_id)
        REFERENCES card_balance_account (id) DEFERRABLE
);

CREATE INDEX idx_ledger_event_account_occurred
    ON ledger_event (account_id, occurred_at);

CREATE TABLE balance_observation (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    lookup_method VARCHAR(24) NOT NULL,
    actual_card_balance BIGINT,
    failure_code VARCHAR(80),
    account_lookup_version BIGINT,
    first_successful BOOLEAN,
    previous_successful_observation_id UUID,
    previous_successful_balance BIGINT,
    balance_change_event_id UUID,
    balance_change_event_type VARCHAR(48),
    balance_change_event_delta BIGINT,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_observation_id_account UNIQUE (id, account_id),
    CONSTRAINT uk_observation_deposit_proof UNIQUE (id, account_id, status, lookup_method),
    CONSTRAINT uk_observation_previous_proof UNIQUE (id, account_id, actual_card_balance),
    CONSTRAINT uk_observation_first_success UNIQUE (account_id, first_successful),
    CONSTRAINT uk_observation_previous_successor UNIQUE (previous_successful_observation_id),
    CONSTRAINT uk_observation_change_event UNIQUE (balance_change_event_id),
    CONSTRAINT uk_observation_account_lookup_version UNIQUE (account_id, account_lookup_version),
    CONSTRAINT ck_observation_status CHECK (status IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_observation_lookup_method CHECK (lookup_method IN ('USER_REQUESTED', 'PRE_DEPOSIT', 'AUTO_DAILY')),
    CONSTRAINT ck_observation_result CHECK (
        (status = 'SUCCEEDED' AND actual_card_balance IS NOT NULL AND failure_code IS NULL) OR
        (status = 'FAILED' AND actual_card_balance IS NULL AND failure_code IS NOT NULL)
    ),
    CONSTRAINT ck_observation_balance_non_negative CHECK (
        actual_card_balance IS NULL OR actual_card_balance >= 0
    ),
    CONSTRAINT ck_observation_account_lookup_version CHECK (
        account_lookup_version IS NULL OR account_lookup_version > 0
    ),
    CONSTRAINT ck_observation_success_chain CHECK (
        (status = 'FAILED' AND first_successful IS NULL
            AND previous_successful_observation_id IS NULL AND previous_successful_balance IS NULL) OR
        (status = 'SUCCEEDED' AND (
            (first_successful = TRUE AND previous_successful_observation_id IS NULL
                AND previous_successful_balance = 0) OR
            (first_successful IS NULL AND previous_successful_observation_id IS NOT NULL
                AND previous_successful_balance IS NOT NULL)
        ))
    ),
    CONSTRAINT ck_observation_change_provenance CHECK (
        (status = 'FAILED' AND balance_change_event_id IS NULL
            AND balance_change_event_type IS NULL AND balance_change_event_delta IS NULL) OR
        (status = 'SUCCEEDED' AND (
            (balance_change_event_id IS NULL AND balance_change_event_type IS NULL
                AND balance_change_event_delta IS NULL
                AND actual_card_balance = previous_successful_balance) OR
            (balance_change_event_id IS NOT NULL
                AND balance_change_event_type = 'CARD_BALANCE_CHANGE'
                AND balance_change_event_delta IS NOT NULL
                AND balance_change_event_delta <> 0
                AND actual_card_balance - previous_successful_balance = balance_change_event_delta)
        ))
    ),
    CONSTRAINT fk_observation_account FOREIGN KEY (account_id)
        REFERENCES card_balance_account (id) DEFERRABLE
);

CREATE INDEX idx_balance_observation_account_time
    ON balance_observation (account_id, observed_at);

ALTER TABLE ledger_event
    ADD CONSTRAINT fk_ledger_event_deposit_observation_proof
        FOREIGN KEY (deposit_balance_observation_id, account_id,
                     deposit_observation_status, deposit_observation_lookup_method)
        REFERENCES balance_observation (id, account_id, status, lookup_method) DEFERRABLE,
    ADD CONSTRAINT fk_ledger_event_correction_account
        FOREIGN KEY (correction_of_event_id, account_id)
        REFERENCES ledger_event (id, account_id) DEFERRABLE;

ALTER TABLE balance_observation
    ADD CONSTRAINT fk_observation_previous_success_proof
        FOREIGN KEY (previous_successful_observation_id, account_id, previous_successful_balance)
        REFERENCES balance_observation (id, account_id, actual_card_balance) DEFERRABLE,
    ADD CONSTRAINT fk_observation_change_event_proof
        FOREIGN KEY (balance_change_event_id, account_id, balance_change_event_type,
                     balance_change_event_delta, observed_at)
        REFERENCES ledger_event (id, account_id, event_type, account_delta, occurred_at) DEFERRABLE;

CREATE TABLE ledger_wish_effect (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    account_id UUID NOT NULL,
    wish_id UUID NOT NULL,
    wish_purpose_snapshot VARCHAR(200) NOT NULL,
    wish_delta BIGINT NOT NULL,
    CONSTRAINT uk_ledger_effect_event_wish UNIQUE (event_id, wish_id),
    CONSTRAINT fk_ledger_effect_event_account FOREIGN KEY (event_id, account_id)
        REFERENCES ledger_event (id, account_id) DEFERRABLE,
    CONSTRAINT fk_ledger_effect_wish_account FOREIGN KEY (wish_id, account_id)
        REFERENCES wish (id, account_id) DEFERRABLE
);

CREATE TABLE balance_adjustment_case (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    opening_event_id UUID NOT NULL,
    opening_event_type VARCHAR(48) NOT NULL,
    opening_event_delta BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    opened_shortage BIGINT NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    resolution_event_id UUID,
    CONSTRAINT uk_adjustment_case_id_account UNIQUE (id, account_id),
    CONSTRAINT uk_adjustment_opening_event UNIQUE (opening_event_id),
    CONSTRAINT uk_adjustment_resolution_event UNIQUE (resolution_event_id),
    CONSTRAINT ck_adjustment_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_adjustment_shortage_positive CHECK (opened_shortage > 0),
    CONSTRAINT ck_adjustment_opening_provenance CHECK (
        opening_event_type = 'CARD_BALANCE_CHANGE' AND opening_event_delta < 0
    ),
    CONSTRAINT ck_adjustment_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL AND resolution_event_id IS NULL) OR
        (status = 'RESOLVED' AND resolved_at IS NOT NULL
            AND resolution_event_id IS NOT NULL AND resolved_at >= opened_at)
    ),
    CONSTRAINT fk_adjustment_account FOREIGN KEY (account_id)
        REFERENCES card_balance_account (id) DEFERRABLE,
    CONSTRAINT fk_adjustment_opening_event_proof
        FOREIGN KEY (opening_event_id, account_id, opening_event_type, opening_event_delta, opened_at)
        REFERENCES ledger_event (id, account_id, event_type, account_delta, occurred_at) DEFERRABLE,
    CONSTRAINT fk_adjustment_resolution_event_account
        FOREIGN KEY (resolution_event_id, account_id)
        REFERENCES ledger_event (id, account_id) DEFERRABLE
);

CREATE INDEX idx_adjustment_account_status
    ON balance_adjustment_case (account_id, status);
CREATE UNIQUE INDEX uk_adjustment_case_open
    ON balance_adjustment_case (account_id) WHERE status = 'OPEN';

CREATE TABLE balance_adjustment_case_event (
    id UUID PRIMARY KEY,
    adjustment_case_id UUID NOT NULL,
    event_id UUID NOT NULL,
    account_id UUID NOT NULL,
    sequence_number INTEGER NOT NULL,
    event_role VARCHAR(16) NOT NULL,
    CONSTRAINT uk_adjustment_case_event UNIQUE (adjustment_case_id, event_id),
    CONSTRAINT uk_adjustment_case_sequence UNIQUE (adjustment_case_id, sequence_number),
    CONSTRAINT ck_adjustment_case_sequence_non_negative CHECK (sequence_number >= 0),
    CONSTRAINT ck_adjustment_event_role CHECK (event_role IN ('OPENING', 'INTERMEDIATE', 'RESOLUTION')),
    CONSTRAINT fk_adjustment_case_event_case_account
        FOREIGN KEY (adjustment_case_id, account_id)
        REFERENCES balance_adjustment_case (id, account_id) DEFERRABLE,
    CONSTRAINT fk_adjustment_case_event_ledger_account
        FOREIGN KEY (event_id, account_id)
        REFERENCES ledger_event (id, account_id) DEFERRABLE
);

CREATE TABLE mismatch_notification_outbox (
    id UUID PRIMARY KEY,
    adjustment_case_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT uk_mismatch_notification_case UNIQUE (adjustment_case_id),
    CONSTRAINT ck_mismatch_notification_period CHECK (published_at IS NULL OR published_at >= created_at),
    CONSTRAINT fk_mismatch_notification_case FOREIGN KEY (adjustment_case_id)
        REFERENCES balance_adjustment_case (id) DEFERRABLE
);

CREATE TABLE shared_card (
    id UUID PRIMARY KEY,
    wish_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_shared_card_current_wish UNIQUE (wish_id),
    CONSTRAINT ck_shared_card_kind CHECK (kind IN ('PROGRESS', 'COMPLETION')),
    CONSTRAINT ck_shared_card_visibility CHECK (visibility IN ('FRIENDS', 'ACADEMY')),
    CONSTRAINT ck_shared_card_not_private CHECK (visibility <> 'PRIVATE'),
    CONSTRAINT fk_shared_card_wish FOREIGN KEY (wish_id) REFERENCES wish (id) DEFERRABLE
);
