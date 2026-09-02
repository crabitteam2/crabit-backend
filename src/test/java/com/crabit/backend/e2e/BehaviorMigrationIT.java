package com.crabit.backend.e2e;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class BehaviorMigrationIT {
    @Test
    void populatedV13UpgradeAddsDurableActivationWithoutHistoricalBackfill() {
        try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            var ds =
                    new DriverManagerDataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(ds).target("13").load().migrate();
            var jdbc = new JdbcTemplate(ds);
            var fixtures = new SeedFixtureService(jdbc, new SeedFixtureCatalog());
            fixtures.initialize();
            long cards = jdbc.queryForObject("SELECT count(*) FROM shared_card", Long.class);
            assertThat(Flyway.configure().dataSource(ds).load().migrate().migrationsExecuted)
                    .isOne();
            var activation =
                    jdbc.queryForObject(
                            "SELECT started_at FROM behavior_collection", java.sql.Timestamp.class);
            assertThat(Flyway.configure().dataSource(ds).load().migrate().migrationsExecuted)
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT started_at FROM behavior_collection",
                                    java.sql.Timestamp.class))
                    .isEqualTo(activation);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM behavior_event", Long.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM shared_card", Long.class))
                    .isEqualTo(cards);
            fixtures.resetAndInitialize();
            fixtures.resetAndInitialize();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM behavior_collection", Long.class))
                    .isOne();
        }
    }
}
