package com.crabit.backend.simulation;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.PortBinding;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Owns a fresh local database. No existing DB URL, volume or remote host can be supplied. */
public final class SimulationPostgresClock implements AutoCloseable {
    public static final String IMAGE = "crabit-simulation-postgres:clock-v1";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    private final PostgreSQLContainer container;
    private final JdbcTemplate jdbc;
    private final javax.sql.DataSource dataSource;
    private final TransactionTemplate tx;
    private Instant current = SimulationCashOracle.START;
    private boolean closed, running, poisoned;
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return new ViewClock(zone); }
        public Instant instant() { synchronized (SimulationPostgresClock.this) { ensureOpen(); return current; } }
    };
    private final class ViewClock extends Clock {
        private final ZoneId zone;
        private ViewClock(ZoneId zone) { this.zone = java.util.Objects.requireNonNull(zone); }
        public ZoneId getZone() { return zone; }
        public Clock withZone(ZoneId next) { return new ViewClock(next); }
        public Instant instant() { return clock.instant(); }
    }
    public record Step(JdbcTemplate jdbc, Clock clock) {}

    public SimulationPostgresClock() {
        var endpoint = DockerClientFactory.instance().getTransportConfig().getDockerHost();
        if (!"unix".equals(endpoint.getScheme())) throw new IllegalStateException("SIMULATION_REQUIRES_LOCAL_UNIX_DOCKER");
        container = new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("simulation_" + UUID.randomUUID().toString().replace("-", ""))
            .withUsername("simulation").withPassword(UUID.randomUUID().toString())
            .withReuse(false).withStartupTimeout(Duration.ofSeconds(60))
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(5432))));
        try {
            container.start();
            if (!java.util.Set.of("127.0.0.1", "localhost").contains(container.getHost()))
                throw new IllegalStateException("SIMULATION_REQUIRES_LOOPBACK_DATABASE");
            var ds = new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
            dataSource = ds;
            jdbc = new JdbcTemplate(ds);
            jdbc.setQueryTimeout(15);
            tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.setTimeout(30);
            // Fresh disposable container only: no migration or domain write has happened yet.
            SimulationDatabaseReadiness.await(() -> assertSqlTime(current));
            Flyway.configure().dataSource(ds).load().migrate();
            assertSqlTime(current);
        } catch (RuntimeException | Error failure) {
            container.stop();
            throw failure;
        }
    }

    /** Advance only between transactions; callback failure rolls back rows, never rewinds time. */
    public synchronized <T> T executeAt(Instant time, Function<Step,T> operation) {
        return executeStep(time, operation, true);
    }

    /** Services retain their own transaction boundaries, including committed PRE_DEPOSIT failures. */
    public synchronized <T> T executeServicesAt(Instant time, Function<Step,T> operation) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("SIMULATION_AMBIENT_TRANSACTION");
        return executeStep(time, operation, false);
    }

    synchronized javax.sql.DataSource dataSource() { ensureOpen(); return dataSource; }
    synchronized Clock domainClock() { ensureOpen(); return clock; }

    private <T> T executeStep(Instant time, Function<Step,T> operation, boolean atomic) {
        ensureOpen();
        if (running) throw new IllegalStateException("SIMULATION_NESTED_STEP");
        if (time == null || time.isBefore(current) || time.isBefore(SimulationCashOracle.START)
                || !time.isBefore(SimulationCashOracle.END) || time.getNano() % 1000 != 0)
            throw new IllegalArgumentException("SIMULATION_TIME_RANGE_OR_PRECISION");
        running = true;
        try {
            var result = container.execInContainer("sh", "-c",
                "printf '%s\\n' \"$1\" > /simulation-clock/next && mv /simulation-clock/next /simulation-clock/current",
                "simulation-clock", FORMAT.format(time));
            if (result.getExitCode() != 0) { poisoned = true; throw new IllegalStateException("SIMULATION_CLOCK_WRITE_FAILED"); }
            try { assertSqlTime(time); }
            catch (RuntimeException | Error failure) { poisoned = true; throw failure; }
            current = time;
            T committed = atomic ? tx.execute(status -> {
                assertSqlTime(time); // Transaction now() and clock_timestamp() must agree too.
                T value = operation.apply(new Step(jdbc, clock));
                assertSqlTime(time);
                return value;
            }) : executeServiceCommand(time, operation);
            assertSqlTime(time); // Commit-time triggers have now run at the same process clock.
            return committed;
        } catch (InterruptedException e) {
            poisoned = true; Thread.currentThread().interrupt(); throw new IllegalStateException("SIMULATION_CLOCK_INTERRUPTED", e);
        } catch (java.io.IOException e) {
            poisoned = true; throw new IllegalStateException("SIMULATION_CLOCK_IO", e);
        } finally { running = false; }
    }
    private <T> T executeServiceCommand(Instant time, Function<Step,T> operation) {
        try { return operation.apply(new Step(jdbc, clock)); }
        finally { assertSqlTime(time); } // Earlier service transactions may commit even on command failure.
    }
    private void assertSqlTime(Instant expected) {
        jdbc.query("SELECT clock_timestamp(), current_timestamp, statement_timestamp()", rs -> {
            for (int i=1;i<=3;i++) if (!expected.equals(rs.getTimestamp(i).toInstant())) {
                poisoned = true;
                throw new IllegalStateException("SIMULATION_SQL_CLOCK_MISMATCH expected="+expected+" observed="+rs.getTimestamp(i).toInstant());
            }
        });
    }
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("SIMULATION_CLOSED");
        if (poisoned) throw new IllegalStateException("SIMULATION_CLOCK_UNCERTAIN");
    }
    @Override public synchronized void close() {
        if (running) throw new IllegalStateException("SIMULATION_STEP_ACTIVE");
        if (!closed) { closed = true; container.stop(); }
    }
}
