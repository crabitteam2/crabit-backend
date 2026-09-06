ALTER TABLE shared_card
    DROP CONSTRAINT ck_shared_card_kind;

ALTER TABLE shared_card
    ADD CONSTRAINT ck_shared_card_kind
        CHECK (kind IN ('PROGRESS', 'COMPLETION', 'ABANDONMENT'));
