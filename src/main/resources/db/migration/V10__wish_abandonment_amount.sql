ALTER TABLE wish ADD COLUMN abandonment_amount BIGINT;

CREATE TEMP TABLE wish_abandonment_amount_provenance ON COMMIT DROP AS
WITH return_sources AS (
    SELECT effect.wish_id,
           count(*) AS source_count,
	           min(effect.account_id::TEXT)::UUID AS account_id,
           min(event.occurred_at) AS occurred_at,
           min(effect.wish_delta) AS wish_delta
    FROM ledger_event event
    JOIN ledger_wish_effect effect
      ON effect.event_id = event.id
     AND effect.account_id = event.account_id
    WHERE event.event_type = 'WISH_ABANDONMENT_RETURN'
    GROUP BY effect.wish_id
),
idempotency_sources AS (
    SELECT NULLIF(record.value ->> 'targetId', '')::UUID AS wish_id,
           count(*) AS source_count,
           min(source_student.id::TEXT)::UUID AS student_id,
           min(NULLIF(record.value ->> 'recordedAt', '')::TIMESTAMPTZ) AS recorded_at,
           bool_and(record.value #>> '{snapshot,id}' = record.value ->> 'targetId')
               AS snapshot_matches_target,
           bool_and(record.value #>> '{snapshot,state}' = 'ABANDONED')
               AS snapshot_is_abandoned,
           bool_and((record.value #>> '{snapshot,amount}')::BIGINT = 0)
               AS snapshot_amount_is_zero
    FROM student source_student
    CROSS JOIN LATERAL jsonb_each(wish_idempotency_records) record
    WHERE record.value ->> 'operation' = 'ABANDON'
      AND (record.value ->> 'httpStatus')::INTEGER BETWEEN 200 AND 299
    GROUP BY NULLIF(record.value ->> 'targetId', '')::UUID
)
SELECT wish.id AS wish_id,
       wish.account_id,
       account.student_id AS owner_student_id,
       wish.target_amount,
       wish.created_at,
       wish.abandoned_at,
       COALESCE(return_sources.source_count, 0) AS return_source_count,
       return_sources.account_id AS return_account_id,
       return_sources.occurred_at,
       return_sources.wish_delta,
       COALESCE(idempotency_sources.source_count, 0) AS idempotency_source_count,
       idempotency_sources.student_id AS idempotency_student_id,
       idempotency_sources.recorded_at,
       COALESCE(idempotency_sources.snapshot_matches_target, FALSE)
           AS snapshot_matches_target,
       COALESCE(idempotency_sources.snapshot_is_abandoned, FALSE)
           AS snapshot_is_abandoned,
       COALESCE(idempotency_sources.snapshot_amount_is_zero, FALSE)
           AS snapshot_amount_is_zero
FROM wish
JOIN card_balance_account account ON account.id = wish.account_id
LEFT JOIN return_sources ON return_sources.wish_id = wish.id
LEFT JOIN idempotency_sources ON idempotency_sources.wish_id = wish.id
WHERE wish.state = 'ABANDONED';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM wish_abandonment_amount_provenance provenance
        WHERE provenance.return_source_count > 1
           OR provenance.idempotency_source_count <> 1
           OR provenance.idempotency_student_id IS DISTINCT FROM provenance.owner_student_id
           OR provenance.recorded_at IS DISTINCT FROM provenance.abandoned_at
           OR NOT provenance.snapshot_matches_target
           OR NOT provenance.snapshot_is_abandoned
           OR NOT provenance.snapshot_amount_is_zero
           OR (
               provenance.return_source_count = 1
               AND (
                   provenance.return_account_id IS DISTINCT FROM provenance.account_id
                   OR provenance.occurred_at IS DISTINCT FROM provenance.abandoned_at
                   OR provenance.wish_delta IS NULL
                   OR provenance.wish_delta >= 0
                   OR provenance.wish_delta = -9223372036854775808
                   OR -provenance.wish_delta > provenance.target_amount
               )
           )
    ) THEN
        RAISE EXCEPTION 'Ambiguous or invalid Wish abandonment amount provenance';
    END IF;
END;
$$;

UPDATE wish
SET abandonment_amount = CASE provenance.return_source_count
        WHEN 0 THEN 0
        ELSE -provenance.wish_delta
    END
FROM wish_abandonment_amount_provenance provenance
WHERE wish.id = provenance.wish_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM student
        CROSS JOIN LATERAL jsonb_each(wish_idempotency_records) record
        CROSS JOIN LATERAL (VALUES
            ('snapshot', record.value -> 'snapshot'),
            ('destinationSnapshot', record.value -> 'destinationSnapshot')
        ) snapshots(name, value)
        LEFT JOIN wish snapshot_wish
          ON snapshot_wish.id = CASE
              WHEN snapshots.value IS NULL OR snapshots.value = 'null'::jsonb THEN NULL
              WHEN jsonb_typeof(snapshots.value) = 'object'
                   AND (snapshots.value ->> 'id') ~*
                       '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
              THEN (snapshots.value ->> 'id')::UUID
              ELSE NULL
          END
        WHERE snapshots.value IS NOT NULL
          AND snapshots.value <> 'null'::jsonb
          AND (
              jsonb_typeof(snapshots.value) <> 'object'
              OR snapshot_wish.id IS NULL
              OR snapshots.value ->> 'state' NOT IN
                  ('IN_PROGRESS', 'AMOUNT_REACHED', 'COMPLETED', 'ABANDONED')
              OR (snapshots.value ->> 'state' = 'ABANDONED'
                  AND snapshot_wish.state <> 'ABANDONED')
          )
    ) THEN
        RAISE EXCEPTION 'Ambiguous or invalid Wish idempotency snapshot provenance';
    END IF;
END;
$$;

WITH snapshot_records AS (
    SELECT student.id AS student_id,
           record.key,
           CASE
               WHEN record.value ? 'snapshot'
                    AND record.value -> 'snapshot' <> 'null'::jsonb
               THEN jsonb_set(
                   record.value,
                   '{snapshot,abandonmentAmount}',
                   CASE record.value #>> '{snapshot,state}'
                       WHEN 'ABANDONED' THEN to_jsonb(snapshot_wish.abandonment_amount)
                       ELSE 'null'::jsonb
                   END,
                   true)
               ELSE record.value
           END AS snapshot_enriched,
           destination_wish.abandonment_amount AS destination_abandonment_amount
    FROM student
    CROSS JOIN LATERAL jsonb_each(wish_idempotency_records) record
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
                   '{destinationSnapshot,abandonmentAmount}',
                   CASE snapshot_enriched #>> '{destinationSnapshot,state}'
                       WHEN 'ABANDONED' THEN to_jsonb(destination_abandonment_amount)
                       ELSE 'null'::jsonb
                   END,
                   true)
               ELSE snapshot_enriched
           END AS value
    FROM snapshot_records
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
    ADD CONSTRAINT ck_wish_abandonment_amount CHECK (
        (state = 'ABANDONED'
            AND abandonment_amount IS NOT NULL
            AND abandonment_amount >= 0
            AND abandonment_amount <= target_amount)
        OR (state <> 'ABANDONED' AND abandonment_amount IS NULL)
    );
