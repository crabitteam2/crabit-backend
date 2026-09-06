-- V1 and V6 are already deployed Flyway history and must remain byte-for-byte immutable.
-- Convert each legacy symmetric friendship into two directional follows so existing
-- FRIENDS visibility keeps the same audience after becoming FOLLOWERS visibility.
CREATE SEQUENCE student_follow_activation_seq;

CREATE TABLE student_follow (
    id UUID PRIMARY KEY,
    academy_id UUID NOT NULL,
    source_id UUID NOT NULL,
    target_id UUID NOT NULL,
    activation BIGINT NOT NULL DEFAULT nextval('student_follow_activation_seq'),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    CONSTRAINT uk_student_follow_academy_pair UNIQUE (academy_id, source_id, target_id),
    CONSTRAINT ck_student_follow_distinct_students CHECK (source_id <> target_id),
    CONSTRAINT ck_student_follow_period CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT fk_student_follow_academy FOREIGN KEY (academy_id)
        REFERENCES academy (id) DEFERRABLE,
    CONSTRAINT fk_student_follow_source_student FOREIGN KEY (source_id)
        REFERENCES student (id) DEFERRABLE,
    CONSTRAINT fk_student_follow_target_student FOREIGN KEY (target_id)
        REFERENCES student (id) DEFERRABLE,
    CONSTRAINT fk_student_follow_source_membership FOREIGN KEY (source_id, academy_id)
        REFERENCES academy_membership (student_id, academy_id) DEFERRABLE,
    CONSTRAINT fk_student_follow_target_membership FOREIGN KEY (target_id, academy_id)
        REFERENCES academy_membership (student_id, academy_id) DEFERRABLE
);

INSERT INTO student_follow (id, academy_id, source_id, target_id, started_at, ended_at)
SELECT friendship.id, friendship.academy_id, friendship.student_low_id,
       friendship.student_high_id, friendship.started_at, friendship.ended_at
FROM friendship
ORDER BY friendship.started_at, friendship.id;

INSERT INTO student_follow (id, academy_id, source_id, target_id, started_at, ended_at)
SELECT gen_random_uuid(), friendship.academy_id, friendship.student_high_id,
       friendship.student_low_id, friendship.started_at, friendship.ended_at
FROM friendship
ORDER BY friendship.started_at, friendship.id;

CREATE INDEX idx_follow_outgoing
    ON student_follow (academy_id, source_id, started_at DESC, target_id DESC)
    WHERE ended_at IS NULL;
CREATE INDEX idx_follow_incoming
    ON student_follow (academy_id, target_id, started_at DESC, source_id DESC)
    WHERE ended_at IS NULL;
CREATE TABLE relationship_cursor_key (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    secret VARCHAR(72) NOT NULL
);
INSERT INTO relationship_cursor_key(id, secret)
VALUES (1, gen_random_uuid()::text || gen_random_uuid()::text);

SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE wish DROP CONSTRAINT ck_wish_visibility;
UPDATE wish SET visibility = 'FOLLOWERS' WHERE visibility = 'FRIENDS';
ALTER TABLE wish ADD CONSTRAINT ck_wish_visibility
    CHECK (visibility IN ('PRIVATE', 'FOLLOWERS', 'ACADEMY'));

ALTER TABLE shared_card DROP CONSTRAINT ck_shared_card_visibility;
UPDATE shared_card SET visibility = 'FOLLOWERS' WHERE visibility = 'FRIENDS';
ALTER TABLE shared_card ADD CONSTRAINT ck_shared_card_visibility
    CHECK (visibility IN ('FOLLOWERS', 'ACADEMY'));

-- Pending/rejected/canceled requests have no equivalent in the consent-free Follow model.
DROP TABLE friend_request;
DROP TABLE friendship;
