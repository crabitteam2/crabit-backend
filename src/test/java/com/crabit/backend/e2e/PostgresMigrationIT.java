package com.crabit.backend.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class PostgresMigrationIT {

	@Test
	void migratesAnEmptyPostgresDatabaseAndIsIdempotent() {
		Set<String> tables = Set.copyOf(PostgresTestDatabase.JDBC.queryForList("""
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema = 'public'
				""", String.class));

		assertThat(tables).contains(
				"academy", "student", "academy_membership", "friendship", "student_block",
				"card_balance_account", "balance_observation", "wish", "ledger_event",
				"ledger_wish_effect", "balance_adjustment_case",
				"balance_adjustment_case_event", "mismatch_notification_outbox", "shared_card");
		assertThat(PostgresTestDatabase.FLYWAY.migrate().migrationsExecuted).isZero();

		Set<String> indexes = Set.copyOf(PostgresTestDatabase.JDBC.queryForList("""
				SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
				""", String.class));
		assertThat(indexes).contains(
				"uk_card_account_active", "uk_adjustment_case_open",
				"uk_shared_card_current_wish", "uk_mismatch_notification_case");
	}
}
