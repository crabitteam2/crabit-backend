CREATE SEQUENCE student_follow_activation_seq;
ALTER TABLE student_follow ADD COLUMN activation BIGINT NOT NULL DEFAULT nextval('student_follow_activation_seq');
CREATE INDEX idx_follow_outgoing ON student_follow (academy_id, source_id, started_at DESC, target_id DESC) WHERE ended_at IS NULL;
CREATE INDEX idx_follow_incoming ON student_follow (academy_id, target_id, started_at DESC, source_id DESC) WHERE ended_at IS NULL;
CREATE INDEX idx_student_block_blocker_current ON student_block (blocker_id, blocked_at DESC, blocked_id DESC) WHERE released_at IS NULL;
CREATE TABLE relationship_cursor_key (id INTEGER PRIMARY KEY CHECK (id = 1), secret VARCHAR(72) NOT NULL);
INSERT INTO relationship_cursor_key(id, secret) VALUES (1, gen_random_uuid()::text || gen_random_uuid()::text);
