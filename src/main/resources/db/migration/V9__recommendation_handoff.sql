ALTER TABLE student ADD COLUMN age INTEGER;

UPDATE student
SET age = 8 + (get_byte(decode(replace(id::text, '-', ''), 'hex'), 0) % 12);

ALTER TABLE student
    ALTER COLUMN age SET NOT NULL,
    ADD CONSTRAINT ck_student_age CHECK (age BETWEEN 0 AND 120);

CREATE INDEX idx_ledger_wish_effect_wish_event
    ON ledger_wish_effect (wish_id, event_id);
