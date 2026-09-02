ALTER TABLE wish ADD COLUMN start_date DATE;

ALTER TABLE wish
    ADD CONSTRAINT ck_wish_plan_date_range CHECK (
        start_date IS NULL OR target_date IS NULL OR start_date <= target_date
    );
