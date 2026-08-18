ALTER TABLE balance_adjustment_case
    ADD COLUMN opening_balance_observation_id UUID,
    ADD COLUMN opening_balance_observation_first_successful BOOLEAN;

UPDATE balance_adjustment_case adjustment
SET opening_balance_observation_id = observation.id
FROM balance_observation observation
WHERE observation.account_id = adjustment.account_id
  AND observation.status = 'SUCCEEDED'
  AND observation.balance_change_event_id = adjustment.opening_event_id;

ALTER TABLE balance_adjustment_case
    ALTER COLUMN opening_balance_observation_id SET NOT NULL,
    DROP CONSTRAINT ck_adjustment_opening_provenance,
    DROP CONSTRAINT fk_adjustment_opening_event_proof;

ALTER TABLE balance_adjustment_case_event
    DROP CONSTRAINT ck_adjustment_event_role;

UPDATE balance_adjustment_case_event
SET event_role = 'OPENING_DECREASE'
WHERE event_role = 'OPENING';

ALTER TABLE balance_adjustment_case_event
    ADD CONSTRAINT ck_adjustment_event_role CHECK (
        event_role IN ('OPENING_DECREASE', 'INTERMEDIATE', 'RESOLUTION')
    );

ALTER TABLE balance_adjustment_case
    ALTER COLUMN opening_event_id DROP NOT NULL,
    ALTER COLUMN opening_event_type DROP NOT NULL,
    ALTER COLUMN opening_event_delta DROP NOT NULL,
    ADD CONSTRAINT uk_adjustment_opening_observation
        UNIQUE (opening_balance_observation_id),
    ADD CONSTRAINT ck_adjustment_opening_provenance CHECK (
        (opening_event_id IS NULL
            AND opening_event_type IS NULL
            AND opening_event_delta IS NULL
            AND opening_balance_observation_first_successful = TRUE)
        OR
        (opening_event_id IS NOT NULL
            AND opening_event_type = 'CARD_BALANCE_CHANGE'
            AND opening_event_delta < 0
            AND opening_balance_observation_first_successful IS NULL)
    );

ALTER TABLE balance_observation
    ADD CONSTRAINT uk_observation_adjustment_origin
        UNIQUE (id, account_id, observed_at),
    ADD CONSTRAINT uk_observation_adjustment_first_proof
        UNIQUE (id, account_id, first_successful),
    ADD CONSTRAINT uk_observation_adjustment_event_proof
        UNIQUE (
            id,
            account_id,
            balance_change_event_id,
            balance_change_event_type,
            balance_change_event_delta,
            observed_at
        );

ALTER TABLE balance_adjustment_case
    ADD CONSTRAINT fk_adjustment_opening_observation_origin
        FOREIGN KEY (opening_balance_observation_id, account_id, opened_at)
        REFERENCES balance_observation (id, account_id, observed_at) DEFERRABLE,
    ADD CONSTRAINT fk_adjustment_eventless_first_success
        FOREIGN KEY (
            opening_balance_observation_id,
            account_id,
            opening_balance_observation_first_successful
        )
        REFERENCES balance_observation (id, account_id, first_successful) DEFERRABLE,
    ADD CONSTRAINT fk_adjustment_opening_event_observation_proof
        FOREIGN KEY (
            opening_balance_observation_id,
            account_id,
            opening_event_id,
            opening_event_type,
            opening_event_delta,
            opened_at
        )
        REFERENCES balance_observation (
            id,
            account_id,
            balance_change_event_id,
            balance_change_event_type,
            balance_change_event_delta,
            observed_at
        ) DEFERRABLE,
    ADD CONSTRAINT fk_adjustment_opening_event_proof
        FOREIGN KEY (
            opening_event_id,
            account_id,
            opening_event_type,
            opening_event_delta,
            opened_at
        )
        REFERENCES ledger_event (id, account_id, event_type, account_delta, occurred_at)
        DEFERRABLE;
