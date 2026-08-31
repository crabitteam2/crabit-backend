CREATE TABLE wish_photo (
    id uuid PRIMARY KEY,
    owner_student_id uuid NOT NULL,
    attached_wish_id uuid,
    state varchar(32) NOT NULL,
    content_digest varchar(64) NOT NULL,
    object_prefix varchar(300) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    delete_requested_at timestamp with time zone,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_wish_photo_attached_wish UNIQUE (attached_wish_id),
    CONSTRAINT ck_wish_photo_state CHECK (state IN ('PENDING', 'ATTACHED', 'DELETE_PENDING')),
    CONSTRAINT ck_wish_photo_attachment CHECK (
        (state = 'PENDING' AND attached_wish_id IS NULL AND delete_requested_at IS NULL)
        OR (state = 'ATTACHED' AND attached_wish_id IS NOT NULL AND delete_requested_at IS NULL)
        OR (state = 'DELETE_PENDING' AND delete_requested_at IS NOT NULL)
    ),
    CONSTRAINT ck_wish_photo_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_wish_photo_owner_pending
    ON wish_photo(owner_student_id, created_at)
    WHERE state = 'PENDING';

CREATE TABLE wish_photo_upload_receipt (
    owner_student_id uuid NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    content_digest varchar(64) NOT NULL,
    photo_id uuid,
    error_code varchar(64),
    error_message varchar(300),
    created_at timestamp with time zone NOT NULL,
    PRIMARY KEY (owner_student_id, idempotency_key),
    CONSTRAINT uk_wish_photo_receipt_photo UNIQUE (photo_id),
    CONSTRAINT ck_wish_photo_receipt_outcome CHECK (
        (photo_id IS NOT NULL AND error_code IS NULL AND error_message IS NULL)
        OR (photo_id IS NULL AND error_code IS NOT NULL AND error_message IS NOT NULL)
    )
);

CREATE TABLE wish_photo_processing_attempt (
    id uuid PRIMARY KEY,
    owner_student_id uuid NOT NULL,
    attempted_at timestamp with time zone NOT NULL
);

CREATE INDEX idx_wish_photo_attempt_owner_time
    ON wish_photo_processing_attempt(owner_student_id, attempted_at);

CREATE TABLE wish_photo_cleanup_work (
    photo_id uuid PRIMARY KEY,
    object_prefix varchar(300) NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp with time zone NOT NULL,
    last_error varchar(300),
    CONSTRAINT ck_wish_photo_cleanup_attempts CHECK (attempt_count >= 0)
);

CREATE FUNCTION reset_wish_photo_state_before_wish_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    TRUNCATE TABLE
        wish_photo,
        wish_photo_cleanup_work,
        wish_photo_upload_receipt,
        wish_photo_processing_attempt
    RESTART IDENTITY;
    RETURN NULL;
END
$$;

CREATE TRIGGER reset_wish_photo_state_before_wish_truncate
BEFORE TRUNCATE ON wish
FOR EACH STATEMENT
EXECUTE FUNCTION reset_wish_photo_state_before_wish_truncate();
