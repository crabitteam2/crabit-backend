-- Additive simulation metadata. Ordinary financial history protection is unchanged.
CREATE TABLE demo_simulation_dataset (
    dataset_id VARCHAR(71) PRIMARY KEY CHECK (dataset_id ~ '^sha256:[0-9a-f]{64}$'),
    manifest_digest VARCHAR(71) NOT NULL CHECK (manifest_digest ~ '^sha256:[0-9a-f]{64}$'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('BUILDING','READY','APPLIED','RESTORED')),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL CHECK (ends_at > starts_at),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    applied_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
CREATE UNIQUE INDEX one_active_demo_simulation ON demo_simulation_dataset ((true)) WHERE state = 'APPLIED';
CREATE TABLE demo_simulation_account (
    account_id UUID PRIMARY KEY REFERENCES card_balance_account(id) DEFERRABLE,
    dataset_id VARCHAR(71) NOT NULL REFERENCES demo_simulation_dataset(dataset_id) DEFERRABLE,
    logical_student_id VARCHAR(160) NOT NULL,
    logical_account_id VARCHAR(160) NOT NULL,
    grade INTEGER NOT NULL CHECK (grade BETWEEN 3 AND 6),
    is_owner BOOLEAN NOT NULL,
    card_funds BIGINT NOT NULL DEFAULT 0 CHECK (card_funds BETWEEN 0 AND 9007199254740991),
    cash_sequence BIGINT NOT NULL DEFAULT 0 CHECK (cash_sequence >= 0),
    UNIQUE(dataset_id,logical_student_id), UNIQUE(dataset_id,logical_account_id),
    UNIQUE(dataset_id,account_id),
    CHECK (is_owner = (account_id = '00000000-0000-0000-0000-000000000301'::uuid))
);
CREATE UNIQUE INDEX one_demo_owner ON demo_simulation_account(dataset_id) WHERE is_owner;
CREATE TABLE demo_simulation_persona (
    persona VARCHAR(7) PRIMARY KEY CHECK (persona IN ('grade-3','grade-4','grade-5','grade-6')),
    dataset_id VARCHAR(71) NOT NULL,
    account_id UUID NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL CHECK (length(display_name) > 0),
    FOREIGN KEY(dataset_id,account_id) REFERENCES demo_simulation_account(dataset_id,account_id) DEFERRABLE,
    CHECK (account_id <> '00000000-0000-0000-0000-000000000301'::uuid)
);
CREATE TABLE demo_simulation_cash_event (
    dataset_id VARCHAR(71) NOT NULL,
    event_id VARCHAR(160) NOT NULL,
    account_id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('GRANT','PURCHASE')),
    amount_krw BIGINT NOT NULL CHECK (amount_krw BETWEEN 1 AND 9007199254740991),
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(dataset_id,event_id), UNIQUE(account_id,sequence),
    FOREIGN KEY(dataset_id,account_id) REFERENCES demo_simulation_account(dataset_id,account_id) DEFERRABLE
);
CREATE TRIGGER immutable_demo_cash_event BEFORE UPDATE OR DELETE ON demo_simulation_cash_event
    FOR EACH ROW EXECUTE FUNCTION reject_historical_fact_mutation();
ALTER TABLE balance_observation ADD COLUMN source_kind VARCHAR(16) NOT NULL DEFAULT 'PROVIDER';
ALTER TABLE balance_observation ADD COLUMN simulation_dataset_id VARCHAR(71);
ALTER TABLE balance_observation ADD COLUMN simulation_source_ref VARCHAR(240);
ALTER TABLE balance_observation ADD CONSTRAINT ck_observation_source CHECK (
    (source_kind = 'PROVIDER' AND simulation_dataset_id IS NULL AND simulation_source_ref IS NULL)
    OR (source_kind = 'SIMULATION' AND simulation_dataset_id IS NOT NULL AND simulation_dataset_id ~ '^sha256:[0-9a-f]{64}$'
        AND simulation_source_ref IS NOT NULL AND length(simulation_source_ref) > 0));
ALTER TABLE balance_observation ADD CONSTRAINT fk_observation_simulation_dataset
    FOREIGN KEY(simulation_dataset_id) REFERENCES demo_simulation_dataset(dataset_id) DEFERRABLE;
CREATE TABLE demo_simulation_application (
    journal_id UUID PRIMARY KEY,
    dataset_id VARCHAR(71) NOT NULL REFERENCES demo_simulation_dataset(dataset_id) DEFERRABLE,
    manifest_digest VARCHAR(71) NOT NULL CHECK (manifest_digest ~ '^sha256:[0-9a-f]{64}$'),
    operation VARCHAR(8) NOT NULL CHECK (operation IN ('APPLY','RESTORE')),
    target_identity VARCHAR(200) NOT NULL,
    expected_revision BIGINT NOT NULL CHECK(expected_revision >= 0),
    before_fingerprint VARCHAR(71) NOT NULL,
    after_fingerprint VARCHAR(71) NOT NULL,
    backup_digest VARCHAR(71) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(target_identity,expected_revision),
    UNIQUE(dataset_id,manifest_digest,operation,target_identity)
);
CREATE TRIGGER immutable_demo_application BEFORE UPDATE OR DELETE ON demo_simulation_application
    FOR EACH ROW EXECUTE FUNCTION reject_historical_fact_mutation();

-- Non-web management capability. Application logins must never be members of this role.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='crabit_demo_manager') THEN
        CREATE ROLE crabit_demo_manager NOLOGIN NOINHERIT;
    END IF;
END $$;
ALTER TABLE demo_simulation_application ADD COLUMN request_digest VARCHAR(71)
    CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$');

CREATE FUNCTION demo_import_tables() RETURNS TEXT[] LANGUAGE sql IMMUTABLE AS $$
SELECT ARRAY['academy','student','academy_membership','card_balance_account',
'demo_simulation_dataset','demo_simulation_account','demo_simulation_persona','demo_simulation_cash_event',
'student_block','student_follow','wish','balance_observation','ledger_event','ledger_wish_effect',
'balance_adjustment_case','balance_adjustment_case_event','mismatch_notification_outbox',
'representative_wish_selection','shared_card','behavior_result_context','behavior_result_item',
'behavior_impression','behavior_event','recap_generation','historical_ledger_application',
'historical_balance_checkpoint','feed_source_history','feed_visit_evidence','feed_page_context',
'feed_page_state','feed_page_transition']::TEXT[]
$$;

-- Only digests of excluded rows (including key material and media) cross the management boundary.
CREATE FUNCTION demo_import_snapshot() RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public AS $$
DECLARE t TEXT; facts JSONB:='{}'; sequences JSONB:='{}'; value JSONB; schema_text TEXT;
BEGIN
    FOREACH t IN ARRAY (public.demo_import_tables() || ARRAY['behavior_collection','feed_history_collection',
        'flyway_schema_history','relationship_cursor_key','wish_photo','wish_photo_upload_receipt',
        'wish_photo_processing_attempt','wish_photo_cleanup_work']) LOOP
        EXECUTE format('SELECT jsonb_build_object(''rows'',count(*),''digest'',encode(sha256(convert_to(coalesce(string_agg(h, '''' ORDER BY h COLLATE "C"),''''),''UTF8'')),''hex'')) FROM (SELECT encode(sha256(convert_to(to_jsonb(r)::text,''UTF8'')),''hex'') h FROM public.%I r) x',t) INTO value;
        facts:=facts || jsonb_build_object(t,value);
    END LOOP;
    FOR t IN SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename LOOP
        EXECUTE format('SELECT jsonb_build_object(''lastValue'',last_value,''called'',is_called) FROM public.%I',t) INTO value;
        sequences:=sequences || jsonb_build_object(t,value);
    END LOOP;
    SELECT string_agg(def,E'\n' ORDER BY def COLLATE "C") INTO schema_text FROM (
        SELECT 'column:'||c.relname||':'||a.attname||':'||a.atttypid||':'||a.attnotnull||':'||a.attidentity::text AS def
        FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
        UNION ALL SELECT 'constraint:'||conrelid::regclass::text||':'||pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace='public'::regnamespace
        UNION ALL SELECT 'trigger:'||pg_get_triggerdef(t.oid) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid WHERE c.relnamespace='public'::regnamespace AND NOT t.tgisinternal
        UNION ALL SELECT 'function:'||pg_get_functiondef(p.oid) FROM pg_proc p WHERE p.pronamespace='public'::regnamespace AND p.prokind='f'
        UNION ALL SELECT 'index:'||indexdef FROM pg_indexes WHERE schemaname='public'
        UNION ALL SELECT 'default:'||adrelid::regclass::text||':'||adnum||':'||pg_get_expr(adbin,adrelid) FROM pg_attrdef d JOIN pg_class c ON c.oid=d.adrelid WHERE c.relnamespace='public'::regnamespace
    ) defs;
    RETURN jsonb_build_object('database',current_database(),'databaseOid',(SELECT oid FROM pg_database WHERE datname=current_database()),
        'schemaDigest','sha256:'||encode(sha256(convert_to(schema_text,'UTF8')),'hex'),'tables',facts,'sequences',sequences);
END $$;
REVOKE ALL ON FUNCTION demo_import_snapshot() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION demo_import_snapshot() TO crabit_demo_manager;

CREATE FUNCTION demo_import_export() RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public AS $$
DECLARE t TEXT; result JSONB:='{}'; rows JSONB;
BEGIN
    FOREACH t IN ARRAY public.demo_import_tables() LOOP
        EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text COLLATE "C"),''[]'') FROM public.%I r',t) INTO rows;
        result:=result || jsonb_build_object(t,rows);
    END LOOP;
    RETURN result;
END $$;
REVOKE ALL ON FUNCTION demo_import_export() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION demo_import_export() TO crabit_demo_manager;

-- Not a session flag: the exact-row capability table is owned by the SECURITY DEFINER
-- entry point, never by the application/management login, and exists only in its transaction.
CREATE FUNCTION demo_import_row_allowed(table_name TEXT, operation TEXT, old_row JSONB, new_row JSONB)
RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE scope_oid OID:=to_regclass('pg_temp.crabit_demo_import_scope'); trusted_owner OID; permitted BOOLEAN;
BEGIN
    IF scope_oid IS NULL OR operation NOT IN ('INSERT','UPDATE','DELETE') THEN RETURN false; END IF;
    SELECT proowner INTO trusted_owner FROM pg_proc WHERE oid='public.demo_import_row_allowed(text,text,jsonb,jsonb)'::regprocedure;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid=scope_oid AND relnamespace=pg_my_temp_schema() AND relowner=trusted_owner) THEN RETURN false; END IF;
    EXECUTE 'SELECT EXISTS(SELECT 1 FROM pg_temp.crabit_demo_import_scope WHERE transaction_id=txid_current() AND name=$1
      AND ($2=''INSERT'' OR old_rows @> jsonb_build_array($3)) AND ($2=''DELETE'' OR new_rows @> jsonb_build_array($4)))'
      INTO permitted USING table_name,operation,old_row,new_row;
    RETURN permitted;
END $$;

-- Add a narrowly scoped early return while retaining the original invariant code for all
-- ordinary writes. No trigger is disabled and no global clock or collection start changes.
DO $$
DECLARE fn TEXT; definition TEXT; guard TEXT;
BEGIN
    guard:=E'BEGIN\n    IF public.demo_import_row_allowed(TG_TABLE_NAME,TG_OP,CASE WHEN TG_OP IN (''UPDATE'',''DELETE'') THEN to_jsonb(OLD) ELSE NULL END,CASE WHEN TG_OP IN (''INSERT'',''UPDATE'') THEN to_jsonb(NEW) ELSE NULL END) THEN\n        IF TG_WHEN=''BEFORE'' THEN IF TG_OP=''DELETE'' THEN RETURN OLD; ELSE RETURN NEW; END IF; ELSE RETURN NULL; END IF;\n    END IF;';
    FOREACH fn IN ARRAY ARRAY['reject_historical_fact_mutation','preserve_historical_account_identity',
        'queue_historical_balance_checkpoint','record_historical_ledger_application','order_historical_ledger_application',
        'feed_record_source_history','select_first_active_wish','clear_representative_wish_on_account_close',
        'protect_recap_storage','feed_preserve_visit_evidence'] LOOP
        SELECT pg_get_functiondef(('public.'||fn||'()')::regprocedure) INTO definition;
        EXECUTE regexp_replace(definition,'\mBEGIN\M',guard,'i');
    END LOOP;
END $$;

CREATE FUNCTION demo_import_owner_graph(tables JSONB) RETURNS JSONB LANGUAGE plpgsql IMMUTABLE
SET search_path=pg_catalog,public AS $$
DECLARE result JSONB:='{}'; t TEXT; r JSONB; p JSONB; chosen JSONB; old_result JSONB; keep BOOLEAN; f TEXT;
BEGIN
    FOREACH t IN ARRAY public.demo_import_tables() LOOP result:=result||jsonb_build_object(t,'[]'::jsonb); END LOOP;
    LOOP
        old_result:=result;
        FOREACH t IN ARRAY public.demo_import_tables() LOOP
            IF t LIKE 'demo_simulation_%' THEN CONTINUE; END IF;
            chosen:=result->t;
            FOR r IN SELECT x FROM jsonb_array_elements(tables->t) x LOOP
                IF chosen @> jsonb_build_array(r) THEN CONTINUE; END IF;
                p:=CASE WHEN t='feed_source_history' THEN r->'payload' ELSE r END;
                keep:=coalesce(p->>'account_id'='00000000-0000-0000-0000-000000000301',false)
                    OR (t='card_balance_account' AND p->>'id'='00000000-0000-0000-0000-000000000301')
                    OR (t='student' AND p->>'id'='00000000-0000-0000-0000-000000000201')
                    OR (t='academy' AND p->>'id'='00000000-0000-0000-0000-000000000101');
                FOREACH f IN ARRAY ARRAY['actor_id','student_id','viewer_id','source_id','blocker_id','blocked_id','target_author_id','target_id'] LOOP
                    keep:=keep OR coalesce(p->>f='00000000-0000-0000-0000-000000000201',false);
                END LOOP;
                IF t='shared_card' THEN
                    keep:=keep OR EXISTS(SELECT 1 FROM jsonb_array_elements(result->'wish') x WHERE x->>'id'=r->>'wish_id');
                ELSIF t='behavior_result_item' THEN
                    keep:=keep OR EXISTS(SELECT 1 FROM jsonb_array_elements(result->'behavior_result_context') x WHERE x->>'id'=r->>'context_id');
                ELSIF t='feed_page_state' THEN
                    keep:=keep OR EXISTS(SELECT 1 FROM jsonb_array_elements(result->'feed_page_context') x WHERE x->>'id'=r->>'context_id');
                ELSIF t='feed_page_transition' THEN
                    keep:=keep OR EXISTS(SELECT 1 FROM jsonb_array_elements(result->'feed_page_state') x WHERE x->>'id'=r->>'input_state_id');
                ELSIF t='feed_source_history' AND r->>'source_kind'='shared_card' THEN
                    keep:=keep OR EXISTS(SELECT 1 FROM jsonb_array_elements(result->'wish') x WHERE x->>'id'=p->>'wish_id');
                END IF;
                IF keep THEN chosen:=chosen||jsonb_build_array(r); END IF;
            END LOOP;
            SELECT coalesce(jsonb_agg(x ORDER BY x::text COLLATE "C"),'[]') INTO chosen FROM jsonb_array_elements(chosen) x;
            result:=jsonb_set(result,ARRAY[t],chosen);
        END LOOP;
        EXIT WHEN result=old_result;
    END LOOP;
    RETURN result;
END $$;

CREATE FUNCTION demo_import_graph(request JSONB, dry_run BOOLEAN) RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public AS $$
DECLARE
    names TEXT[]:=public.demo_import_tables(); t TEXT; value JSONB; before_state JSONB; after_state JSONB;
    report JSONB; old_rows JSONB; new_rows JSONB; expected_rows JSONB; observed_rows JSONB;
    request_hash TEXT; post_hash TEXT; before_hash TEXT; columns_sql TEXT; normalized JSONB;
    entry demo_simulation_application%ROWTYPE; current_revision BIGINT; journal UUID:=gen_random_uuid();
    sequence_name TEXT; sequence_value BIGINT; sequence_called BOOLEAN; target UUID[]; students UUID[];
    allowed BOOLEAN; r JSONB; field TEXT; full_before JSONB; full_after JSONB; n BIGINT;
BEGIN
    IF jsonb_typeof(request)<>'object' OR request->>'schemaVersion'<>'1'
        OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(request) k) IS DISTINCT FROM
           ARRAY['after','backupDigest','before','beforeSnapshot','codeSha','consoleBaselineDigest','datasetId','expectedAfterFingerprint','expectedRevision','manifestDigest','operation','restoreSequences','schemaVersion','targetAccounts','targetIdentity','targetStudents']::TEXT[]
        OR request->>'operation' NOT IN ('APPLY','RESTORE')
        OR request->>'datasetId' !~ '^sha256:[0-9a-f]{64}$' OR request->>'manifestDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR request->>'backupDigest' !~ '^sha256:[0-9a-f]{64}$' OR request->>'consoleBaselineDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR request->>'codeSha' !~ '^[0-9a-f]{40}$' OR length(request->>'targetIdentity') NOT BETWEEN 1 AND 200
        OR request->>'expectedRevision' !~ '^(0|[1-9][0-9]*)$' OR octet_length(request::text)>268435456 THEN
        RAISE EXCEPTION 'SCHEMA_INVALID';
    END IF;
    SELECT array_agg(x::uuid) INTO target FROM jsonb_array_elements_text(request->'targetAccounts') x;
    SELECT array_agg(x::uuid) INTO students FROM jsonb_array_elements_text(request->'targetStudents') x;
    IF cardinality(target)<>100 OR cardinality(students)<>100
        OR (SELECT count(DISTINCT x) FROM unnest(target) x)<>100 OR (SELECT count(DISTINCT x) FROM unnest(students) x)<>100
        OR NOT ('00000000-0000-0000-0000-000000000301'::uuid=ANY(target))
        OR NOT ('00000000-0000-0000-0000-000000000201'::uuid=ANY(students)) THEN RAISE EXCEPTION 'TARGET_MISMATCH'; END IF;
    IF to_regclass('pg_temp.crabit_demo_import_scope') IS NOT NULL THEN RAISE EXCEPTION 'IMPORT_SCOPE_ALREADY_EXISTS'; END IF;
    PERFORM set_config('lock_timeout','5s',true); PERFORM set_config('TimeZone','UTC',true);
    -- All backend writers are serialized for this bounded local management transaction.
    FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename LOOP
        EXECUTE format('LOCK TABLE public.%I IN SHARE ROW EXCLUSIVE MODE',t);
    END LOOP;
    IF (SELECT count(*) FROM pg_tables WHERE schemaname='public')<>40 THEN RAISE EXCEPTION 'IMPORT_TABLE_INVENTORY'; END IF;
    before_state:=public.demo_import_snapshot();
    before_hash:='sha256:'||encode(sha256(convert_to(before_state::text,'UTF8')),'hex');
    request_hash:='sha256:'||encode(sha256(convert_to(request::text,'UTF8')),'hex');
    SELECT * INTO entry FROM demo_simulation_application WHERE dataset_id=request->>'datasetId'
        AND manifest_digest=request->>'manifestDigest' AND operation=request->>'operation' AND target_identity=request->>'targetIdentity';
    IF FOUND THEN
        IF entry.request_digest IS DISTINCT FROM request_hash OR entry.after_fingerprint<>before_hash THEN RAISE EXCEPTION 'RESTORE_DRIFT_OR_DATASET_CONFLICT'; END IF;
        RETURN jsonb_build_object('status','NO_OP','journalId',entry.journal_id,'beforeFingerprint',before_hash,'afterFingerprint',before_hash);
    END IF;
    SELECT coalesce(max(expected_revision)+1,0) INTO current_revision FROM demo_simulation_application WHERE target_identity=request->>'targetIdentity';
    IF current_revision<>(request->>'expectedRevision')::BIGINT OR before_state IS DISTINCT FROM request->'beforeSnapshot' THEN RAISE EXCEPTION 'REVISION_CONFLICT'; END IF;
    IF NOT EXISTS(SELECT 1 FROM card_balance_account WHERE id='00000000-0000-0000-0000-000000000301'
        AND student_id='00000000-0000-0000-0000-000000000201' AND academy_id='00000000-0000-0000-0000-000000000101') THEN
        RAISE EXCEPTION 'OWNER_IDENTITY_DRIFT'; END IF;
    IF (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(request->'before') k) IS DISTINCT FROM (SELECT array_agg(x ORDER BY x) FROM unnest(names) x)
        OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(request->'after') k) IS DISTINCT FROM (SELECT array_agg(x ORDER BY x) FROM unnest(names) x) THEN RAISE EXCEPTION 'IMPORT_TABLE_SET'; END IF;
    full_before:=public.demo_import_export();
    CREATE TEMP TABLE crabit_demo_import_scope(name TEXT PRIMARY KEY,transaction_id BIGINT,old_rows JSONB,new_rows JSONB) ON COMMIT DROP;
    REVOKE ALL ON TABLE pg_temp.crabit_demo_import_scope FROM PUBLIC,crabit_demo_manager;
    FOREACH t IN ARRAY names LOOP
        old_rows:=request->'before'->t; new_rows:=request->'after'->t;
        IF jsonb_typeof(old_rows)<>'array' OR jsonb_typeof(new_rows)<>'array' THEN RAISE EXCEPTION 'IMPORT_ROW_SHAPE'; END IF;
        -- Parse through the fixed current SQL row type. Unknown/missing columns are rejected.
        SELECT array_agg(attname::text ORDER BY attname) INTO names FROM pg_attribute WHERE attrelid=('public.'||t)::regclass AND attnum>0 AND NOT attisdropped;
        FOR r IN SELECT x FROM jsonb_array_elements(old_rows || new_rows) x LOOP
            IF (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(r) k) IS DISTINCT FROM names THEN RAISE EXCEPTION 'IMPORT_COLUMN_SET'; END IF;
        END LOOP;
        EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(r)),''[]'') FROM jsonb_populate_recordset(NULL::public.%I,$1) r',t) INTO old_rows USING old_rows;
        EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(r)),''[]'') FROM jsonb_populate_recordset(NULL::public.%I,$1) r',t) INTO new_rows USING new_rows;
        IF NOT (full_before->t @> old_rows) OR (SELECT count(*) FROM jsonb_array_elements(old_rows))<>(SELECT count(DISTINCT x) FROM jsonb_array_elements(old_rows) x)
            OR (SELECT count(*) FROM jsonb_array_elements(new_rows))<>(SELECT count(DISTINCT x) FROM jsonb_array_elements(new_rows) x) THEN RAISE EXCEPTION 'IMPORT_BEFORE_MISMATCH'; END IF;
        INSERT INTO pg_temp.crabit_demo_import_scope VALUES(t,txid_current(),old_rows,new_rows);
    END LOOP;
    names:=public.demo_import_tables();
    -- A row belongs to the explicit target only through typed identity/ownership columns.
    -- Parent-owned children (effects, transitions, history) are checked against selected parent rows.
    FOR t,old_rows,new_rows IN SELECT name,s.old_rows,s.new_rows FROM pg_temp.crabit_demo_import_scope s LOOP
        FOR r IN SELECT x FROM jsonb_array_elements(old_rows || new_rows) x LOOP
            allowed:=false;
            IF t='academy' THEN allowed:=r->>'id'='00000000-0000-0000-0000-000000000101';
            ELSIF t='student' THEN allowed:=(r->>'id')::uuid=ANY(students);
            ELSIF t='card_balance_account' THEN allowed:=(r->>'id')::uuid=ANY(target) AND (r->>'student_id')::uuid=ANY(students) AND r->>'academy_id'='00000000-0000-0000-0000-000000000101';
            ELSIF t='demo_simulation_dataset' THEN allowed:=r->>'dataset_id'=request->>'datasetId';
            ELSIF t IN ('feed_page_state','feed_page_transition') THEN
                field:=CASE WHEN t='feed_page_state' THEN 'context_id' ELSE 'input_state_id' END;
                SELECT EXISTS(SELECT 1 FROM pg_temp.crabit_demo_import_scope s CROSS JOIN LATERAL jsonb_array_elements(s.old_rows||s.new_rows) p
                    WHERE s.name=CASE WHEN t='feed_page_state' THEN 'feed_page_context' ELSE 'feed_page_state' END AND p->>'id'=r->>field) INTO allowed;
            ELSIF t='shared_card' THEN
                SELECT EXISTS(SELECT 1 FROM pg_temp.crabit_demo_import_scope s CROSS JOIN LATERAL jsonb_array_elements(s.old_rows||s.new_rows) p WHERE s.name='wish' AND p->>'id'=r->>'wish_id') INTO allowed;
            ELSIF t IN ('behavior_result_item') THEN
                SELECT EXISTS(SELECT 1 FROM pg_temp.crabit_demo_import_scope s CROSS JOIN LATERAL jsonb_array_elements(s.old_rows||s.new_rows) p WHERE s.name='behavior_result_context' AND p->>'id'=r->>'context_id') INTO allowed;
            ELSIF t='feed_source_history' THEN
                SELECT EXISTS(SELECT 1 FROM pg_temp.crabit_demo_import_scope s CROSS JOIN LATERAL jsonb_array_elements(s.old_rows||s.new_rows) p WHERE s.name=r->>'source_kind' AND p->>'id'=r->>'source_id') INTO allowed;
                -- Deleted historical source identities may exist only in the history payload.
                IF NOT allowed AND r->>'source_kind'='shared_card' THEN
                    SELECT EXISTS(SELECT 1 FROM pg_temp.crabit_demo_import_scope s CROSS JOIN LATERAL jsonb_array_elements(s.old_rows||s.new_rows) p
                        WHERE s.name='wish' AND p->>'id'=r->'payload'->>'wish_id') INTO allowed;
                END IF;
                IF NOT allowed THEN
                    allowed:=coalesce((r->'payload'->>'account_id')::uuid=ANY(target),false)
                        OR coalesce((r->'payload'->>'student_id')::uuid=ANY(students),false)
                        OR coalesce((r->'payload'->>'source_id')::uuid=ANY(students),false)
                        OR coalesce((r->'payload'->>'blocker_id')::uuid=ANY(students),false);
                END IF;
            ELSE
                FOREACH field IN ARRAY ARRAY['account_id','actor_id','student_id','viewer_id','source_id','blocker_id','blocked_id','target_author_id','target_id'] LOOP
                    IF r ? field AND r->>field IS NOT NULL THEN
                        IF field='account_id' THEN allowed:=allowed OR (r->>field)::uuid=ANY(target);
                        ELSE allowed:=allowed OR (r->>field)::uuid=ANY(students); END IF;
                    END IF;
                END LOOP;
            END IF;
            IF NOT coalesce(allowed,false) THEN RAISE EXCEPTION 'IMPORT_ROW_OUTSIDE_TARGET: %',t; END IF;
        END LOOP;
    END LOOP;
    -- Preserve the Owner authentication/account/academy identity tuple and membership identity.
    SELECT s.new_rows INTO value FROM pg_temp.crabit_demo_import_scope s WHERE name='card_balance_account';
    IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(value) z WHERE z->>'id'='00000000-0000-0000-0000-000000000301'
        AND z->>'student_id'='00000000-0000-0000-0000-000000000201' AND z->>'academy_id'='00000000-0000-0000-0000-000000000101') THEN RAISE EXCEPTION 'OWNER_IDENTITY_DRIFT'; END IF;
    SELECT s.new_rows INTO value FROM pg_temp.crabit_demo_import_scope s WHERE name='academy_membership';
    IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(value) z WHERE z->>'id'='00000000-0000-0000-0000-000000000501'
        AND z->>'student_id'='00000000-0000-0000-0000-000000000201' AND z->>'academy_id'='00000000-0000-0000-0000-000000000101') THEN RAISE EXCEPTION 'OWNER_IDENTITY_DRIFT'; END IF;
    BEGIN
        SET CONSTRAINTS ALL DEFERRED;
        -- Reverse dependency order for immediate behavior and traversal foreign keys.
        FOR n IN REVERSE cardinality(names)..1 LOOP
            t:=names[n];
            EXECUTE format('DELETE FROM public.%I r WHERE EXISTS(SELECT 1 FROM pg_temp.crabit_demo_import_scope s CROSS JOIN LATERAL jsonb_array_elements(s.old_rows) x WHERE s.name=$1 AND to_jsonb(r)=x AND NOT s.new_rows @> jsonb_build_array(x))',t) USING t;
        END LOOP;
        FOREACH t IN ARRAY names LOOP
            SELECT string_agg(format('%I',attname),',' ORDER BY attnum) INTO columns_sql FROM pg_attribute WHERE attrelid=('public.'||t)::regclass AND attnum>0 AND NOT attisdropped;
            SELECT coalesce(jsonb_agg(x),'[]') INTO value FROM pg_temp.crabit_demo_import_scope s CROSS JOIN LATERAL jsonb_array_elements(s.new_rows) x WHERE s.name=t AND NOT s.old_rows @> jsonb_build_array(x);
            EXECUTE format('INSERT INTO public.%I (%s) OVERRIDING SYSTEM VALUE SELECT %s FROM jsonb_populate_recordset(NULL::public.%I,$1)',t,columns_sql,columns_sql,t) USING value;
        END LOOP;
        -- Evaluate every deferred financial/representative and FK constraint while the exact scope exists.
        SET CONSTRAINTS ALL IMMEDIATE;
        full_after:=public.demo_import_export();
        FOREACH t IN ARRAY names LOOP
            SELECT s.old_rows,s.new_rows INTO old_rows,new_rows FROM pg_temp.crabit_demo_import_scope s WHERE name=t;
            SELECT coalesce(jsonb_agg(x ORDER BY x::text COLLATE "C"),'[]') INTO expected_rows FROM (
                SELECT x FROM jsonb_array_elements(full_before->t) x WHERE NOT old_rows @> jsonb_build_array(x)
                UNION ALL SELECT x FROM jsonb_array_elements(new_rows) x) all_rows;
            IF full_after->t IS DISTINCT FROM expected_rows THEN RAISE EXCEPTION 'IMPORT_READ_BACK_MISMATCH: %',t; END IF;
        END LOOP;
        IF public.demo_import_owner_graph(full_before) IS DISTINCT FROM public.demo_import_owner_graph(full_after) THEN
            RAISE EXCEPTION 'OWNER_GRAPH_DRIFT';
        END IF;
        IF request->>'operation'='APPLY' THEN
            IF (SELECT count(*) FROM demo_simulation_account WHERE dataset_id=request->>'datasetId')<>100
                OR (SELECT count(*) FROM demo_simulation_account WHERE dataset_id=request->>'datasetId' AND is_owner)<>1
                OR EXISTS(SELECT 1 FROM demo_simulation_account WHERE dataset_id=request->>'datasetId' GROUP BY grade HAVING count(*)<>25)
                OR (SELECT count(*) FROM demo_simulation_persona WHERE dataset_id=request->>'datasetId')<>4
                OR EXISTS(SELECT 1 FROM balance_observation WHERE account_id=ANY(target) AND account_id<>'00000000-0000-0000-0000-000000000301' AND (source_kind<>'SIMULATION' OR simulation_dataset_id IS DISTINCT FROM request->>'datasetId'))
                OR NOT EXISTS(SELECT 1 FROM demo_simulation_dataset WHERE dataset_id=request->>'datasetId' AND state='APPLIED') THEN RAISE EXCEPTION 'IMPORT_POPULATION_OR_PROVENANCE'; END IF;
            IF EXISTS(SELECT 1 FROM demo_simulation_account a LEFT JOIN LATERAL (
                SELECT coalesce(sum(CASE kind WHEN 'GRANT' THEN amount_krw ELSE -amount_krw END),0) balance,count(*) total,coalesce(max(sequence),0) last
                FROM demo_simulation_cash_event e WHERE e.dataset_id=a.dataset_id AND e.account_id=a.account_id) c ON true
                WHERE a.dataset_id=request->>'datasetId' AND (a.card_funds<>c.balance OR a.cash_sequence<>c.total OR a.cash_sequence<>c.last)) THEN RAISE EXCEPTION 'IMPORT_CASH_MISMATCH'; END IF;
        END IF;
        -- Transactional sequence restart (not setval): rollback also restores counter state.
        FOR sequence_name,t,field IN SELECT * FROM (VALUES('feed_source_history_version_seq','feed_source_history','version'),
            ('ledger_event_application_order_seq','ledger_event','application_order'),('student_follow_activation_seq','student_follow','activation')) s(seq,tab,col) LOOP
            EXECUTE format('SELECT coalesce(max(%I),0) FROM public.%I',field,t) INTO n;
            IF request->>'operation'='RESTORE' THEN
                sequence_value:=(request->'restoreSequences'->sequence_name->>'lastValue')::BIGINT;
                sequence_called:=(request->'restoreSequences'->sequence_name->>'called')::BOOLEAN;
                IF sequence_value IS NULL OR sequence_called IS NULL OR sequence_value<1
                    OR n>sequence_value OR (NOT sequence_called AND n>=sequence_value) THEN RAISE EXCEPTION 'RESTORE_SEQUENCE_CONFLICT'; END IF;
                EXECUTE format('ALTER SEQUENCE public.%I RESTART WITH %s',sequence_name,sequence_value);
                IF sequence_called THEN PERFORM nextval(('public.'||sequence_name)::regclass); END IF;
            ELSE
                sequence_value:=(before_state->'sequences'->sequence_name->>'lastValue')::BIGINT;
                sequence_called:=(before_state->'sequences'->sequence_name->>'called')::BOOLEAN;
                sequence_value:=greatest(n+1,sequence_value+CASE WHEN sequence_called THEN 1 ELSE 0 END);
                EXECUTE format('ALTER SEQUENCE public.%I RESTART WITH %s',sequence_name,sequence_value);
            END IF;
        END LOOP;
        after_state:=public.demo_import_snapshot();
        -- Exclusions and global collection coverage must remain byte-for-byte identical.
        FOR t IN SELECT jsonb_object_keys(before_state->'tables') LOOP
            IF NOT(t=ANY(names)) AND before_state->'tables'->t IS DISTINCT FROM after_state->'tables'->t THEN RAISE EXCEPTION 'IMPORT_EXCLUDED_DRIFT'; END IF;
        END LOOP;
        post_hash:='sha256:'||encode(sha256(convert_to(after_state::text,'UTF8')),'hex');
        IF request->>'expectedAfterFingerprint' IS NOT NULL AND request->>'expectedAfterFingerprint'<>post_hash THEN RAISE EXCEPTION 'IMPORT_AFTER_FINGERPRINT'; END IF;
        INSERT INTO demo_simulation_application(journal_id,dataset_id,manifest_digest,operation,target_identity,expected_revision,before_fingerprint,after_fingerprint,backup_digest,request_digest)
            VALUES(journal,request->>'datasetId',request->>'manifestDigest',request->>'operation',request->>'targetIdentity',current_revision,before_hash,post_hash,request->>'backupDigest',request_hash);
        report:=jsonb_build_object('status',CASE WHEN dry_run THEN 'DRY_RUN_READY' WHEN request->>'operation'='RESTORE' THEN 'RESTORED' ELSE 'APPLIED' END,
            'journalId',CASE WHEN dry_run THEN NULL ELSE journal END,'beforeFingerprint',before_hash,'afterFingerprint',post_hash,'afterSnapshot',after_state,
            'expectedRevision',current_revision,'externalConsoleVerified',false);
        IF dry_run THEN RAISE EXCEPTION USING ERRCODE='P0002',MESSAGE='CRABIT_DEMO_DRY_RUN_ROLLBACK'; END IF;
    EXCEPTION WHEN no_data_found THEN
        IF NOT dry_run OR SQLERRM<>'CRABIT_DEMO_DRY_RUN_ROLLBACK' THEN RAISE; END IF;
    END;
    DROP TABLE pg_temp.crabit_demo_import_scope;
    RETURN report;
END $$;
REVOKE ALL ON FUNCTION demo_import_graph(JSONB,BOOLEAN) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION demo_import_graph(JSONB,BOOLEAN) TO crabit_demo_manager;

CREATE FUNCTION demo_import_capture(target_id TEXT) RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public AS $$
DECLARE t TEXT; snapshot JSONB; entries JSONB;
BEGIN
    PERFORM set_config('lock_timeout','5s',true); PERFORM set_config('TimeZone','UTC',true);
    FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename LOOP
        EXECUTE format('LOCK TABLE public.%I IN SHARE ROW EXCLUSIVE MODE',t);
    END LOOP;
    snapshot:=public.demo_import_snapshot();
    SELECT coalesce(jsonb_agg(to_jsonb(j) ORDER BY expected_revision),'[]') INTO entries
        FROM demo_simulation_application j WHERE target_identity=target_id;
    RETURN jsonb_build_object('snapshot',snapshot,'fingerprint','sha256:'||encode(sha256(convert_to(snapshot::text,'UTF8')),'hex'),
        'tables',public.demo_import_export(),'journal',entries,'capturedAt',clock_timestamp());
END $$;
REVOKE ALL ON FUNCTION demo_import_capture(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION demo_import_capture(TEXT) TO crabit_demo_manager;
-- Metadata visibility only. The manager has no direct INSERT/UPDATE/DELETE or key/media SELECT.
GRANT USAGE ON SCHEMA public TO crabit_demo_manager;
GRANT REFERENCES ON ALL TABLES IN SCHEMA public TO crabit_demo_manager;
