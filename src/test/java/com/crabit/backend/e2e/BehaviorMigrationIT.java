package com.crabit.backend.e2e;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

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
            initializeLegacyFixture(jdbc);
            long cards = jdbc.queryForObject("SELECT count(*) FROM shared_card", Long.class);
            assertThat(Flyway.configure().dataSource(ds).load().migrate().migrationsExecuted)
                    .isPositive();
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
            var fixtures = new SeedFixtureService(jdbc, new SeedFixtureCatalog());
            fixtures.resetAndInitialize();
            fixtures.resetAndInitialize();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM behavior_collection", Long.class))
                    .isOne();
        }
    }

    private static void initializeLegacyFixture(JdbcTemplate jdbc) {
        UUID academyId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID studentId = UUID.fromString("20000000-0000-4000-8000-000000000001");
        UUID accountId = UUID.fromString("30000000-0000-4000-8000-000000000001");
        UUID wishId = UUID.fromString("40000000-0000-4000-8000-000000000001");
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        jdbc.update("INSERT INTO academy(id, name) VALUES (?, 'Legacy Academy')", academyId);
        jdbc.update(
                "INSERT INTO student(id, nickname, age) VALUES (?, 'Legacy Student', 12)",
                studentId);
        jdbc.update(
                "INSERT INTO academy_membership(id, student_id, academy_id, joined_at) "
                        + "VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), studentId, academyId, Timestamp.from(now));
        jdbc.update(
                "INSERT INTO card_balance_account(id, student_id, academy_id, opened_at) "
                        + "VALUES (?, ?, ?, ?)",
                accountId, studentId, academyId, Timestamp.from(now));
        jdbc.update(
                "INSERT INTO wish(id, account_id, academy_id, purpose, target_amount, "
                        + "wish_amount, state, visibility, created_at) "
                        + "VALUES (?, ?, ?, 'Legacy Wish', 100, 0, 'IN_PROGRESS', 'FRIENDS', ?)",
                wishId, accountId, academyId, Timestamp.from(now));
        jdbc.update(
                "INSERT INTO shared_card(id, wish_id, kind, visibility, updated_at) "
                        + "VALUES (?, ?, 'PROGRESS', 'FRIENDS', ?)",
                UUID.randomUUID(), wishId, Timestamp.from(now));
    }
}
