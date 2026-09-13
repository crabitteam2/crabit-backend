package com.crabit.backend.simulation;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Read-only preservation evidence for an owned disposable DB. Not a backup or write authorization. */
public final class SimulationPreservationFingerprint {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Table(long rows, String digest) {}
    public record Sequence(long lastValue, boolean called) {}
    public record Snapshot(String database, String schemaDigest, Map<String,Table> tables,
                           Map<String,Sequence> sequences, String digest) {
        public Snapshot {
            tables = Collections.unmodifiableMap(new TreeMap<>(tables));
            sequences = Collections.unmodifiableMap(new TreeMap<>(sequences));
        }
    }
    public record Difference(boolean unchanged, List<String> changedTables,
                             List<String> changedSequences, boolean schemaChanged) {
        public Difference { changedTables=List.copyOf(changedTables); changedSequences=List.copyOf(changedSequences); }
    }
    private SimulationPreservationFingerprint() {}

    /** Accepts only a runtime that created its own local DB; no URL, credentials, SQL or remote mode. */
    public static Snapshot capture(SimulationPostgresClock runtime) {
        synchronized (runtime) {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("PRESERVATION_AMBIENT_TRANSACTION");
            var dataSource = runtime.dataSource();
            var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            tx.setReadOnly(true);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            tx.setTimeout(30);
            return tx.execute(status -> {
                var jdbc = new JdbcTemplate(dataSource);
                jdbc.setQueryTimeout(15);
                jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
                jdbc.execute("SET LOCAL DateStyle = 'ISO, YMD'");
                jdbc.execute("SET LOCAL IntervalStyle = 'postgres'");
                if (!"on".equals(jdbc.queryForObject("SHOW transaction_read_only", String.class)))
                    throw new IllegalStateException("PRESERVATION_READ_ONLY_REQUIRED");
                return captureTransaction(jdbc);
            });
        }
    }

    /** Internal observer for the already locked backup transaction; does not start a new snapshot. */
    static Snapshot captureTransaction(JdbcTemplate jdbc) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("PRESERVATION_TRANSACTION_REQUIRED");
        var inventory = new TreeSet<>(jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname='public'", String.class));
        var expected = new TreeSet<>(SimulationRelationalState.TABLES);
        expected.addAll(Set.of("flyway_schema_history", "relationship_cursor_key"));
        if (!inventory.equals(expected)) throw new IllegalStateException("PRESERVATION_TABLE_INVENTORY");
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        Map<String,Sequence> sequences = sequences(jdbc);
        Map<String,Table> tables = new TreeMap<>();
        // Hash each row inside PostgreSQL. Only count and SHA-256 cross the JDBC boundary.
        // Sorting fixed-length hashes preserves duplicates and ignores physical row order.
        for (String table : inventory) tables.put(table, jdbc.queryForObject("""
            SELECT count(*), 'sha256:' || encode(sha256(convert_to(
                coalesce(string_agg(row_hash, '' ORDER BY row_hash COLLATE "C"), ''), 'UTF8')), 'hex')
            FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text, 'UTF8')), 'hex') row_hash
                  FROM public.%s t) hashed
            """.formatted(identifier(table)), (r,n) -> new Table(r.getLong(1), r.getString(2))));
        String schema = schema(jdbc);
        // Sequence values are not MVCC. Refuse a visibly changing sequence, never call nextval/setval.
        if (!sequences.equals(sequences(jdbc))) throw new IllegalStateException("PRESERVATION_SEQUENCE_MOVED");
        String digest = SimulationBundleReader.digest(SimulationBundleReader.canonical(JSON.valueToTree(
            Map.of("database",database,"schemaDigest",schema,"tables",tables,"sequences",sequences)))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Snapshot(database, schema, tables, sequences, digest);
    }

    /** Compares two observations from the same DB. It cannot certify an intervening write or approve an import. */
    public static Difference compare(Snapshot before, Snapshot after) {
        if (!before.database().equals(after.database())) throw new IllegalArgumentException("PRESERVATION_DATABASE_MISMATCH");
        var tables = changed(before.tables(),after.tables());
        var sequences = changed(before.sequences(),after.sequences());
        boolean schema = !before.schemaDigest().equals(after.schemaDigest());
        return new Difference(tables.isEmpty() && sequences.isEmpty() && !schema,tables,sequences,schema);
    }
    private static <T> List<String> changed(Map<String,T> before, Map<String,T> after) {
        var keys = new TreeSet<>(before.keySet()); keys.addAll(after.keySet());
        return keys.stream().filter(k -> !Objects.equals(before.get(k),after.get(k))).toList();
    }
    private static String identifier(String name) {
        if (!name.matches("[a-z_][a-z0-9_]*")) throw new IllegalStateException("PRESERVATION_IDENTIFIER");
        return '"' + name + '"';
    }
    private static Map<String,Sequence> sequences(JdbcTemplate jdbc) {
        var result = new TreeMap<String,Sequence>();
        for (String sequence : jdbc.queryForList(
                "SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename",String.class))
            result.put(sequence,jdbc.queryForObject("SELECT last_value,is_called FROM public."+identifier(sequence),
                (r,n) -> new Sequence(r.getLong(1),r.getBoolean(2))));
        return result;
    }
    private static String schema(JdbcTemplate jdbc) {
        // Definitions stay server-side too. Include immutability triggers, their bodies, defaults and indexes.
        return jdbc.queryForObject("""
            SELECT 'sha256:' || encode(sha256(convert_to(coalesce(string_agg(
                encode(sha256(convert_to(value, 'UTF8')), 'hex'), '' ORDER BY value COLLATE "C"), ''), 'UTF8')), 'hex')
            FROM (
                SELECT 'column:' || to_jsonb(c)::text value FROM information_schema.columns c WHERE table_schema='public'
                UNION ALL SELECT 'constraint:' || t.relname || ':' || c.conname || ':' || pg_get_constraintdef(c.oid)
                    FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                    WHERE n.nspname='public'
                UNION ALL SELECT 'trigger:' || t.relname || ':' || g.tgenabled::text || ':' || pg_get_triggerdef(g.oid)
                    FROM pg_trigger g JOIN pg_class t ON t.oid=g.tgrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                    WHERE n.nspname='public' AND NOT g.tgisinternal
                UNION ALL SELECT 'function:' || pg_get_functiondef(p.oid)
                    FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.prokind='f'
                UNION ALL SELECT 'index:' || indexdef FROM pg_indexes WHERE schemaname='public'
                UNION ALL SELECT 'sequence:' || (to_jsonb(s)-'last_value')::text FROM pg_sequences s WHERE schemaname='public'
            ) definitions
            """,String.class);
    }
}
