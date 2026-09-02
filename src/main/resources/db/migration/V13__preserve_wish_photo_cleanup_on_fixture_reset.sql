-- Fixture reset removes database access immediately, but must not lose the only
-- durable references to private objects. External deletion remains asynchronous.
CREATE OR REPLACE FUNCTION reset_wish_photo_state_before_wish_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Acquire locks before inspecting identities or removing photo state. Receipt
    -- precedes photo and photo precedes cleanup, matching the application order.
    LOCK TABLE wish_photo_upload_receipt, wish_photo,
        wish_photo_processing_attempt, wish_photo_cleanup_work
        IN ACCESS EXCLUSIVE MODE;

    IF EXISTS (
        SELECT 1
        FROM wish_photo photo
        JOIN wish_photo_cleanup_work work ON work.photo_id = photo.id
        WHERE work.object_prefix <> photo.object_prefix
    ) THEN
        RAISE EXCEPTION 'Wish photo cleanup identity mismatch';
    END IF;

    INSERT INTO wish_photo_cleanup_work (
        photo_id, object_prefix, requested_at, next_attempt_at
    )
    SELECT id, object_prefix,
        COALESCE(delete_requested_at, transaction_timestamp()),
        transaction_timestamp()
    FROM wish_photo
    ON CONFLICT (photo_id) DO UPDATE
    SET requested_at = LEAST(wish_photo_cleanup_work.requested_at, EXCLUDED.requested_at);

    -- Existing orphan work, identity, attempt count, retry deadline and sanitized
    -- last error remain intact. Repeated resets never restart or discard retries.
    TRUNCATE TABLE wish_photo, wish_photo_upload_receipt,
        wish_photo_processing_attempt RESTART IDENTITY;
    RETURN NULL;
END
$$;
