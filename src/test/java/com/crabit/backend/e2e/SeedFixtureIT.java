package com.crabit.backend.e2e;

import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

	private static long count(String table) {
		return PostgresTestDatabase.JDBC.queryForObject(
				"SELECT count(*) FROM " + table, Long.class);
	}
}
