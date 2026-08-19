CREATE SEQUENCE ledger_event_application_order_seq AS BIGINT;

ALTER TABLE ledger_event
    ADD COLUMN application_order BIGINT;

WITH persisted_order AS (
    SELECT id,
           row_number() OVER (ORDER BY (xmin::text)::BIGINT, ctid) AS application_order
    FROM ledger_event
)
UPDATE ledger_event event
SET application_order = persisted_order.application_order
FROM persisted_order
WHERE persisted_order.id = event.id;

SELECT setval(
    'ledger_event_application_order_seq',
    COALESCE((SELECT max(application_order) + 1 FROM ledger_event), 1),
    FALSE
);

ALTER SEQUENCE ledger_event_application_order_seq
    OWNED BY ledger_event.application_order;

ALTER TABLE ledger_event
    ALTER COLUMN application_order
        SET DEFAULT nextval('ledger_event_application_order_seq'),
    ALTER COLUMN application_order SET NOT NULL,
    ADD CONSTRAINT uk_ledger_event_application_order UNIQUE (application_order),
    ADD CONSTRAINT ck_ledger_event_application_order_positive
        CHECK (application_order > 0);

CREATE INDEX idx_ledger_event_account_history
    ON ledger_event (account_id, occurred_at DESC, id DESC);

CREATE INDEX idx_ledger_event_account_application_order
    ON ledger_event (account_id, application_order DESC);

CREATE INDEX idx_ledger_effect_wish_history
    ON ledger_wish_effect (account_id, wish_id, event_id);

CREATE INDEX idx_adjustment_case_event_history
    ON balance_adjustment_case_event (account_id, event_id);
