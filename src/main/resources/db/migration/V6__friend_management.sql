CREATE TABLE friend_request (
    id UUID PRIMARY KEY,
    academy_id UUID NOT NULL,
    sender_id UUID NOT NULL,
    receiver_id UUID NOT NULL,
    student_low_id UUID NOT NULL,
    student_high_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT ck_friend_request_distinct_students CHECK (sender_id <> receiver_id),
    CONSTRAINT ck_friend_request_canonical_pair CHECK (
        student_low_id < student_high_id
        AND student_low_id IN (sender_id, receiver_id)
        AND student_high_id IN (sender_id, receiver_id)
    ),
    CONSTRAINT ck_friend_request_status CHECK (
        status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELED')
    ),
    CONSTRAINT ck_friend_request_processed CHECK (
        (status = 'PENDING' AND processed_at IS NULL)
        OR (status <> 'PENDING' AND processed_at IS NOT NULL AND processed_at >= created_at)
    ),
    CONSTRAINT fk_friend_request_academy FOREIGN KEY (academy_id)
        REFERENCES academy (id) DEFERRABLE,
    CONSTRAINT fk_friend_request_sender FOREIGN KEY (sender_id)
        REFERENCES student (id) DEFERRABLE,
    CONSTRAINT fk_friend_request_receiver FOREIGN KEY (receiver_id)
        REFERENCES student (id) DEFERRABLE,
    CONSTRAINT fk_friend_request_sender_membership FOREIGN KEY (sender_id, academy_id)
        REFERENCES academy_membership (student_id, academy_id) DEFERRABLE,
    CONSTRAINT fk_friend_request_receiver_membership FOREIGN KEY (receiver_id, academy_id)
        REFERENCES academy_membership (student_id, academy_id) DEFERRABLE
);

CREATE UNIQUE INDEX uk_friend_request_active_academy_pair
    ON friend_request (academy_id, student_low_id, student_high_id)
    WHERE status = 'PENDING';
CREATE INDEX idx_friend_request_sender_pending
    ON friend_request (sender_id, academy_id, created_at DESC, id DESC)
    WHERE status = 'PENDING';
CREATE INDEX idx_friend_request_receiver_pending
    ON friend_request (receiver_id, academy_id, created_at DESC, id DESC)
    WHERE status = 'PENDING';
CREATE INDEX idx_friend_request_pair_pending
    ON friend_request (student_low_id, student_high_id, academy_id)
    WHERE status = 'PENDING';
CREATE INDEX idx_friendship_pair_current
    ON friendship (student_low_id, student_high_id, academy_id)
    WHERE ended_at IS NULL;
CREATE INDEX idx_student_block_blocker_current
    ON student_block (blocker_id, blocked_at DESC, blocked_id DESC)
    WHERE released_at IS NULL;
