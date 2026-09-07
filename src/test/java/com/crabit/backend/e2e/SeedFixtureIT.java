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
import java.time.ZoneId;
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
		assertThat(count("student_follow")).isOne();
		assertThat(count("student_block")).isOne();
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
				INSERT INTO student_follow
				    (id, academy_id, source_id, target_id, started_at, ended_at)
				VALUES (?, ?, ?, ?, ?, NULL)
				""", UUID.randomUUID(), PRIMARY_ACADEMY_ID, OWNER_ID, NONFRIEND_ID,
				Timestamp.from(Instant.parse("2026-08-17T00:00:00Z")));

		fixtures.resetAndInitialize();

		assertThat(count("student_follow")).isOne();
	}

	@Test
	void demoResetSeedsThreeCoherentDepositsOutsideTheCompletedWeek() {
		SeedFixtureCatalog.RecapResetFixture recap = fixtures.resetDemoAndInitialize(
				Instant.parse("2026-09-06T05:30:00Z"));
		ZoneId seoul = ZoneId.of("Asia/Seoul");
		Timestamp monthlyStart = Timestamp.from(recap.monthly().start()
				.atStartOfDay(seoul).toInstant());
		Timestamp monthlyEnd = Timestamp.from(recap.monthly().endExclusive()
				.atStartOfDay(seoul).toInstant());
		Timestamp weeklyStart = Timestamp.from(recap.weekly().start()
				.atStartOfDay(seoul).toInstant());
		Timestamp weeklyEnd = Timestamp.from(recap.weekly().endExclusive()
				.atStartOfDay(seoul).toInstant());

		assertThat(PostgresTestDatabase.JDBC.queryForObject("""
				SELECT count(*)
				FROM ledger_event event
				JOIN ledger_wish_effect effect ON effect.event_id = event.id
				WHERE event.account_id = ? AND event.event_type = 'WISH_DEPOSIT'
				  AND event.account_delta = 0 AND effect.wish_id = ?
				  AND effect.wish_delta > 0
				  AND event.occurred_at >= ? AND event.occurred_at < ?
				  AND NOT (event.occurred_at >= ? AND event.occurred_at < ?)
				""", Long.class, OWNER_ACCOUNT_ID, LAPTOP_WISH_ID,
				monthlyStart, monthlyEnd, weeklyStart, weeklyEnd)).isEqualTo(3);
		assertThat(PostgresTestDatabase.JDBC.queryForObject("""
				SELECT sum(effect.wish_delta)
				FROM ledger_event event
				JOIN ledger_wish_effect effect ON effect.event_id = event.id
				WHERE event.account_id = ? AND event.event_type = 'WISH_DEPOSIT'
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(250_000L);
		assertThat(PostgresTestDatabase.JDBC.queryForObject(
				"SELECT wish_amount FROM wish WHERE id = ?", Long.class, LAPTOP_WISH_ID))
				.isEqualTo(250_000L);
		assertThat(PostgresTestDatabase.JDBC.queryForObject("""
				SELECT count(*)
				FROM balance_observation
				WHERE account_id = ? AND status = 'SUCCEEDED'
				  AND lookup_method = 'PRE_DEPOSIT'
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(3);
		assertThat(PostgresTestDatabase.JDBC.queryForObject("""
				SELECT balance_lookup_version
				FROM card_balance_account
				WHERE id = ?
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(3);
	}

	private static long count(String table) {
		return PostgresTestDatabase.JDBC.queryForObject(
				"SELECT count(*) FROM " + table, Long.class);
	}
}
