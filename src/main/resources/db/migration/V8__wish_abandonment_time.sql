ALTER TABLE wish ADD COLUMN abandoned_at TIMESTAMPTZ;

CREATE TEMP TABLE wish_abandonment_provenance ON COMMIT DROP AS
WITH return_sources AS (
    SELECT effect.wish_id,
           count(*) AS source_count,
           min(event.occurred_at) AS occurred_at
    FROM ledger_event event
    JOIN ledger_wish_effect effect
      ON effect.event_id = event.id
     AND effect.account_id = event.account_id
    WHERE event.event_type = 'WISH_ABANDONMENT_RETURN'
    GROUP BY effect.wish_id
),
idempotency_sources AS (
    SELECT (record.value ->> 'targetId')::UUID AS wish_id,
           count(*) AS source_count,
           min((record.value ->> 'recordedAt')::TIMESTAMPTZ) AS recorded_at
    FROM student
    CROSS JOIN LATERAL jsonb_each(wish_idempotency_records) record
    WHERE record.value ->> 'operation' = 'ABANDON'
      AND (record.value ->> 'httpStatus')::INTEGER BETWEEN 200 AND 299
    GROUP BY (record.value ->> 'targetId')::UUID
)
SELECT wish.id AS wish_id,
       COALESCE(return_sources.source_count, 0) AS return_source_count,
       return_sources.occurred_at,
       COALESCE(idempotency_sources.source_count, 0) AS idempotency_source_count,
       idempotency_sources.recorded_at
FROM wish
LEFT JOIN return_sources ON return_sources.wish_id = wish.id
LEFT JOIN idempotency_sources ON idempotency_sources.wish_id = wish.id
WHERE wish.state = 'ABANDONED';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM wish_abandonment_provenance provenance
        JOIN wish ON wish.id = provenance.wish_id
        WHERE provenance.return_source_count > 1
           OR provenance.idempotency_source_count <> 1
           OR (provenance.return_source_count = 1
               AND provenance.occurred_at IS DISTINCT FROM provenance.recorded_at)
           OR COALESCE(provenance.occurred_at, provenance.recorded_at) IS NULL
           OR COALESCE(provenance.occurred_at, provenance.recorded_at) < wish.created_at
    ) THEN
        RAISE EXCEPTION 'Ambiguous or invalid Wish abandonment provenance';
    END IF;
END;
$$;

UPDATE wish
SET abandoned_at = COALESCE(provenance.occurred_at, provenance.recorded_at)
FROM wish_abandonment_provenance provenance
WHERE wish.id = provenance.wish_id;

WITH transformed_records AS (
    SELECT student.id AS student_id,
           record.key,
           CASE
               WHEN record.value ? 'snapshot' AND record.value -> 'snapshot' <> 'null'::jsonb
               THEN jsonb_set(
                   record.value,
                   '{snapshot,closedAt}',
                   CASE record.value #>> '{snapshot,state}'
                       WHEN 'COMPLETED' THEN record.value #> '{snapshot,completedAt}'
                       WHEN 'ABANDONED' THEN COALESCE(
                           to_jsonb(to_char(
                               snapshot_wish.abandoned_at AT TIME ZONE 'UTC',
                               'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')),
                           'null'::jsonb)
                       ELSE 'null'::jsonb
                   END,
                   true)
               ELSE record.value
           END AS snapshot_enriched,
           destination_wish.abandoned_at AS destination_abandoned_at
    FROM student
    CROSS JOIN LATERAL jsonb_each(student.wish_idempotency_records) record
    LEFT JOIN wish snapshot_wish
      ON snapshot_wish.id = NULLIF(record.value #>> '{snapshot,id}', '')::UUID
    LEFT JOIN wish destination_wish
      ON destination_wish.id = NULLIF(record.value #>> '{destinationSnapshot,id}', '')::UUID
),
fully_transformed_records AS (
    SELECT student_id,
           key,
           CASE
               WHEN snapshot_enriched ? 'destinationSnapshot'
                    AND snapshot_enriched -> 'destinationSnapshot' <> 'null'::jsonb
               THEN jsonb_set(
                   snapshot_enriched,
                   '{destinationSnapshot,closedAt}',
                   CASE snapshot_enriched #>> '{destinationSnapshot,state}'
                       WHEN 'COMPLETED' THEN snapshot_enriched #> '{destinationSnapshot,completedAt}'
                       WHEN 'ABANDONED' THEN COALESCE(
                           to_jsonb(to_char(
                               destination_abandoned_at AT TIME ZONE 'UTC',
                               'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')),
                           'null'::jsonb)
                       ELSE 'null'::jsonb
                   END,
                   true)
               ELSE snapshot_enriched
           END AS value
    FROM transformed_records
),
rebuilt_namespaces AS (
    SELECT student_id, jsonb_object_agg(key, value) AS records
    FROM fully_transformed_records
    GROUP BY student_id
)
UPDATE student
SET wish_idempotency_records = rebuilt_namespaces.records
FROM rebuilt_namespaces
WHERE student.id = rebuilt_namespaces.student_id;

SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE wish
    DROP CONSTRAINT ck_wish_completion_time,
    ADD CONSTRAINT ck_wish_terminal_time CHECK (
        (state IN ('IN_PROGRESS', 'AMOUNT_REACHED')
            AND completed_at IS NULL AND abandoned_at IS NULL)
        OR (state = 'COMPLETED'
            AND completed_at IS NOT NULL AND completed_at >= created_at
            AND abandoned_at IS NULL)
        OR (state = 'ABANDONED'
            AND abandoned_at IS NOT NULL AND abandoned_at >= created_at
            AND completed_at IS NULL)
    );
