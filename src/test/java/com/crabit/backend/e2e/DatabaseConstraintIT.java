package com.crabit.backend.e2e;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class DatabaseConstraintIT {

	@BeforeEach
	void reset() {
		PostgresTestDatabase.fixtures().resetAndInitialize();
	}

	@Test
	void rejectsDuplicateActiveAccountsAndInvalidWishAmountsAndStates() {
		assertThatThrownBy(() -> PostgresTestDatabase.JDBC.update("""
				INSERT INTO card_balance_account
				    (id, student_id, academy_id, opened_at, balance_lookup_version, version)
				VALUES (?, ?, ?, now(), 0, 0)
				""", UUID.randomUUID(), OWNER_ID, PRIMARY_ACADEMY_ID))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> insertWish(101, 100, "AMOUNT_REACHED"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertWish(100, 100, "IN_PROGRESS"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rejectsStudentAgesOutsideTheLocalProjectionBounds() {
		assertThatThrownBy(() -> PostgresTestDatabase.JDBC.update(
				"UPDATE student SET age = -1 WHERE id = ?", OWNER_ID))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_student_age");
		assertThatThrownBy(() -> PostgresTestDatabase.JDBC.update(
				"UPDATE student SET age = 121 WHERE id = ?", OWNER_ID))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_student_age");
	}

	@Test
	void permitsNullableWishPlanDatesAndRejectsOnlyAnInvertedNonNullPair() {
		assertThatCode(() -> PostgresTestDatabase.JDBC.update("""
				UPDATE wish
				SET start_date = DATE '2026-12-31', target_date = DATE '2026-12-31'
				WHERE id = ?
				""", LAPTOP_WISH_ID)).doesNotThrowAnyException();
		assertThatCode(() -> PostgresTestDatabase.JDBC.update("""
				UPDATE wish SET target_date = NULL WHERE id = ?
				""", LAPTOP_WISH_ID)).doesNotThrowAnyException();
		assertThatCode(() -> PostgresTestDatabase.JDBC.update("""
				UPDATE wish SET start_date = NULL, target_date = DATE '2025-01-01' WHERE id = ?
				""", LAPTOP_WISH_ID)).doesNotThrowAnyException();

		assertThatThrownBy(() -> PostgresTestDatabase.JDBC.update("""
				UPDATE wish
				SET start_date = DATE '2027-03-01', target_date = DATE '2027-02-28'
				WHERE id = ?
				""", LAPTOP_WISH_ID))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_wish_plan_date_range");
	}

 @Test
 void enforcesUniqueDirectionalFollowAndRejectsSelfFollow() {
  String insert = "INSERT INTO student_follow(id, academy_id, source_id, target_id, started_at) VALUES (?, ?, ?, ?, now())";
  PostgresTestDatabase.JDBC.update(insert, UUID.randomUUID(), PRIMARY_ACADEMY_ID, OWNER_ID, NONFRIEND_ID);
  PostgresTestDatabase.JDBC.update(insert, UUID.randomUUID(), PRIMARY_ACADEMY_ID, NONFRIEND_ID, OWNER_ID);
  assertThatThrownBy(() -> PostgresTestDatabase.JDBC.update(insert, UUID.randomUUID(), PRIMARY_ACADEMY_ID, OWNER_ID, NONFRIEND_ID)).isInstanceOf(DataIntegrityViolationException.class);
  assertThatThrownBy(() -> PostgresTestDatabase.JDBC.update(insert, UUID.randomUUID(), PRIMARY_ACADEMY_ID, OWNER_ID, OWNER_ID)).isInstanceOf(DataIntegrityViolationException.class);
 }

	@Test
	void rejectsInvalidRepresentativeWishFinalStatesAtTransactionCommit() {
		assertThatThrownBy(() -> transaction(jdbc -> {
			jdbc.update("DELETE FROM representative_wish_selection WHERE account_id = ?",
					OWNER_ACCOUNT_ID);
			jdbc.update("""
					UPDATE wish
					SET state = 'COMPLETED', wish_amount = 0, completed_at = now()
					WHERE id = ?
					""", CAMP_WISH_ID);
		}))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("exactly one active Wish");

		assertThatThrownBy(() -> transaction(jdbc -> {
			jdbc.update("""
					UPDATE representative_wish_selection SET wish_id = ? WHERE account_id = ?
					""", CAMP_WISH_ID, OWNER_ACCOUNT_ID);
			jdbc.update("""
					UPDATE wish
					SET state = 'COMPLETED', wish_amount = 0, completed_at = now()
					WHERE id = ?
					""", CAMP_WISH_ID);
		}))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("active nondeleted Wish");

		assertThatThrownBy(() -> transaction(jdbc -> {
			jdbc.update("UPDATE card_balance_account SET closed_at = now() WHERE id = ?",
					OWNER_ACCOUNT_ID);
			jdbc.update("""
					INSERT INTO representative_wish_selection (account_id, wish_id)
					VALUES (?, ?)
					""", OWNER_ACCOUNT_ID, LAPTOP_WISH_ID);
		}))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("closed Card Balance Account");

		assertThatThrownBy(() -> transaction(jdbc -> jdbc.update("""
				UPDATE representative_wish_selection SET wish_id = ? WHERE account_id = ?
				""", UUID.randomUUID(), OWNER_ACCOUNT_ID)))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_representative_selection_wish_account");
	}

	@Test
	void acceptsAtomicRepresentativeCleanupAndSingletonReselection() {
		assertThatCode(() -> transaction(jdbc -> {
			jdbc.update("""
					UPDATE representative_wish_selection SET wish_id = ? WHERE account_id = ?
					""", CAMP_WISH_ID, OWNER_ACCOUNT_ID);
			jdbc.update("""
					UPDATE wish
					SET state = 'COMPLETED', wish_amount = 0, completed_at = now()
					WHERE id = ?
					""", CAMP_WISH_ID);
			jdbc.update("""
					UPDATE representative_wish_selection SET wish_id = ? WHERE account_id = ?
					""", LAPTOP_WISH_ID, OWNER_ACCOUNT_ID);
		})).doesNotThrowAnyException();
	}

	@Test
	void enforcesWishPhotoLifecycleAndSingleAttachmentConstraints() {
		UUID photoId = UUID.randomUUID();
		PostgresTestDatabase.JDBC.update("""
				INSERT INTO wish_photo(id, owner_student_id, state, content_digest, object_prefix,
				        created_at, expires_at, version)
				VALUES (?, ?, 'PENDING', ?, ?, now(), now() + interval '24 hours', 0)
				""", photoId, OWNER_ID, "a".repeat(64), "wish-photos/test/" + photoId);
		assertThatThrownBy(() -> PostgresTestDatabase.JDBC.update("""
				UPDATE wish_photo SET state = 'ATTACHED' WHERE id = ?
				""", photoId)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_wish_photo_attachment");
		PostgresTestDatabase.JDBC.update("""
				UPDATE wish_photo SET state = 'ATTACHED', attached_wish_id = ? WHERE id = ?
				""", CAMP_WISH_ID, photoId);
		UUID second = UUID.randomUUID();
		assertThatThrownBy(() -> PostgresTestDatabase.JDBC.update("""
				INSERT INTO wish_photo(id, owner_student_id, attached_wish_id, state, content_digest,
				        object_prefix, created_at, expires_at, version)
				VALUES (?, ?, ?, 'ATTACHED', ?, ?, now(), now() + interval '24 hours', 0)
				""", second, OWNER_ID, CAMP_WISH_ID, "b".repeat(64), "wish-photos/test/" + second))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_wish_photo_attached_wish");
	}

	private static void insertWish(long amount, long target, String state) {
		PostgresTestDatabase.JDBC.update("""
				INSERT INTO wish
				    (id, account_id, academy_id, purpose, target_amount, wish_amount,
				     state, visibility, created_at, version)
				VALUES (?, ?, ?, 'invalid', ?, ?, ?, 'PRIVATE', now(), 0)
				""", UUID.randomUUID(), OWNER_ACCOUNT_ID, PRIMARY_ACADEMY_ID, target, amount, state);
	}

	private static void transaction(SqlWork work) throws SQLException {
		try (Connection connection = PostgresTestDatabase.DATA_SOURCE.getConnection()) {
			connection.setAutoCommit(false);
			JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
			try {
				work.run(jdbc);
				connection.commit();
			} catch (RuntimeException | SQLException exception) {
				connection.rollback();
				throw exception;
			}
		}
	}

	@FunctionalInterface
	private interface SqlWork {
		void run(JdbcTemplate jdbc);
	}
}
