ALTER TABLE wish ADD COLUMN updated_at TIMESTAMPTZ;
UPDATE wish SET updated_at = created_at;
ALTER TABLE wish ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE wish ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE student
    ADD COLUMN wish_idempotency_records JSONB NOT NULL DEFAULT '{}'::jsonb;
