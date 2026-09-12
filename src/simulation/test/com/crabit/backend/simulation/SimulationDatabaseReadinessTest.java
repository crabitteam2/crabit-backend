package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class SimulationDatabaseReadinessTest {
    private static CannotGetJdbcConnectionException unavailable() {
        return new CannotGetJdbcConnectionException("startup connection",
            new SQLException("connection failed", "08001", new SocketTimeoutException("Read timed out")));
    }

    @Test void transientConnectionFailureCanRecoverBeforeCallerStartsAnyWrites() {
        var probes = new AtomicInteger();
        var writes = new AtomicInteger();
        SimulationDatabaseReadiness.await(() -> {
            assertThat(writes.get()).isZero();
            if (probes.incrementAndGet() < 3) throw unavailable();
        });
        writes.incrementAndGet();
        assertThat(probes.get()).isEqualTo(3);
        assertThat(writes.get()).isEqualTo(1);
    }

    @Test void exhaustedConnectionFailureStopsBeforeCallerCanWrite() {
        var probes = new AtomicInteger();
        var writes = new AtomicInteger();
        var failure = unavailable();
        assertThatThrownBy(() -> {
            SimulationDatabaseReadiness.await(() -> { probes.incrementAndGet(); throw failure; });
            writes.incrementAndGet();
        }).isSameAs(failure);
        assertThat(probes.get()).isEqualTo(3);
        assertThat(writes.get()).isZero();
    }

    @Test void authenticationAndClockMismatchAreNotRetried() {
        for (RuntimeException failure : new RuntimeException[] {
                new CannotGetJdbcConnectionException("authentication", new SQLException("denied", "28P01")),
                new IllegalStateException("SIMULATION_SQL_CLOCK_MISMATCH")}) {
            var probes = new AtomicInteger();
            assertThatThrownBy(() -> SimulationDatabaseReadiness.await(() -> {
                probes.incrementAndGet(); throw failure;
            })).isSameAs(failure);
            assertThat(probes.get()).isEqualTo(1);
        }
    }

    @Test void interruptionStopsStartupAndPreservesInterruptFlag() {
        var probes = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> SimulationDatabaseReadiness.await(() -> {
                probes.incrementAndGet(); throw unavailable();
            })).hasMessage("SIMULATION_DATABASE_STARTUP_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(probes.get()).isEqualTo(1);
        } finally { Thread.interrupted(); }
    }
}
