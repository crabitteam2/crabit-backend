package com.crabit.backend.e2e;

import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

class SeedFixtureIT {

	private final SeedFixtureService fixtures = PostgresTestDatabase.fixtures();

	@BeforeEach
	void reset() {
		fixtures.resetAndInitialize();
	}

	@Test
	void initializeAndResetPreserveTheCanonicalLogicalFixture() {
		fixtures.initialize();
		fixtures.initialize();

		assertThat(count("academy")).isEqualTo(2);
		assertThat(count("student")).isEqualTo(5);
		assertThat(count("academy_membership")).isEqualTo(5);
		assertThat(count("friendship")).isOne();
		assertThat(count("student_block")).isOne();
		assertThat(count("friend_request")).isZero();
		assertThat(count("card_balance_account")).isOne();
		assertThat(count("wish")).isEqualTo(2);
		assertThat(count("shared_card")).isEqualTo(2);
		assertThat(PostgresTestDatabase.JDBC.queryForObject(
				"SELECT count(*) FROM card_balance_account WHERE id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isOne();

		PostgresTestDatabase.JDBC.update(
				"UPDATE wish SET purpose = 'changed' WHERE id = ?", LAPTOP_WISH_ID);
		fixtures.resetAndInitialize();

		assertThat(PostgresTestDatabase.JDBC.queryForObject(
				"SELECT purpose FROM wish WHERE id = ?", String.class, LAPTOP_WISH_ID))
				.isEqualTo("노트북");
	}

	@Test
	void resetRemovesSandboxMutationsThatDoNotUseCanonicalFixtureIds() {
		PostgresTestDatabase.JDBC.update("""
				INSERT INTO friendship
				    (id, academy_id, student_low_id, student_high_id, started_at, ended_at)
				VALUES (?, ?, ?, ?, ?, NULL)
				""", UUID.randomUUID(), PRIMARY_ACADEMY_ID, OWNER_ID, NONFRIEND_ID,
				Timestamp.from(Instant.parse("2026-08-17T00:00:00Z")));

		fixtures.resetAndInitialize();

		assertThat(count("friendship")).isOne();
	}

	private static long count(String table) {
		return PostgresTestDatabase.JDBC.queryForObject(
				"SELECT count(*) FROM " + table, Long.class);
	}
}
