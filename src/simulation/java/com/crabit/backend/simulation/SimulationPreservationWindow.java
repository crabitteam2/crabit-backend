package com.crabit.backend.simulation;

import java.util.*;
import java.util.function.BiFunction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal backup read window for an owned disposable DB. No target URL or write API. */
final class SimulationPreservationWindow {
    private SimulationPreservationWindow() {}

    static <T> T read(SimulationPostgresClock runtime, String expected,
            BiFunction<JdbcTemplate,SimulationPreservationFingerprint.Snapshot,T> read) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("PRESERVATION_WINDOW_AMBIENT_TRANSACTION");
        Objects.requireNonNull(expected); Objects.requireNonNull(read);
        synchronized (runtime) {
            var source=runtime.dataSource();
            var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            // Take row snapshots only after every lock is acquired. A writer that commits while
            // lock acquisition waits must be visible to the expected-fingerprint comparison.
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            tx.setTimeout(30);
            return tx.execute(status->{
                var jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(15);
                String exportZone=jdbc.queryForObject("SHOW TimeZone",String.class);
                jdbc.execute("SET LOCAL lock_timeout = '2s'");
                jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
                jdbc.execute("SET LOCAL DateStyle = 'ISO, YMD'");
                jdbc.execute("SET LOCAL IntervalStyle = 'postgres'");
                var tables=new TreeSet<>(SimulationRelationalState.TABLES);
                tables.addAll(Set.of("flyway_schema_history","relationship_cursor_key"));
                // Fixed inventory and deterministic order; never input SQL. Transaction completion
                // releases these locks even on timeout, stale revision, callback failure or rollback.
                jdbc.execute("LOCK TABLE "+String.join(",",tables.stream().map(t->"public.\""+t+"\"").toList())
                    +" IN SHARE ROW EXCLUSIVE MODE");
                var before=SimulationPreservationFingerprint.captureTransaction(jdbc);
                if (!before.digest().equals(expected))
                    throw new IllegalStateException("SELECTION_BACKUP_REVISION_CONFLICT");
                // Domain history JSON retains its original JDBC session timezone spelling.
                jdbc.queryForObject("SELECT set_config('TimeZone', ?, true)",String.class,exportZone);
                T result=read.apply(jdbc,before);
                jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
                if (!before.equals(SimulationPreservationFingerprint.captureTransaction(jdbc)))
                    throw new IllegalStateException("PRESERVATION_WINDOW_STATE_CHANGED");
                return result;
            });
        }
    }
}
