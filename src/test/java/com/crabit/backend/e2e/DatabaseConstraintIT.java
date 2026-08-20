package com.crabit.backend.e2e;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

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
	void enforcesOnePendingCanonicalFriendRequestAndProcessedStatusTimeConsistency() {
		UUID first = UUID.randomUUID();
		PostgresTestDatabase.JDBC.update("""
				INSERT INTO friend_request
				    (id, academy_id, sender_id, receiver_id, student_low_id, student_high_id,
				     status, created_at, processed_at)
				VALUES (?, ?, ?, ?, LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid),
				        'PENDING', now(), NULL)
				""", first, PRIMARY_ACADEMY_ID, OWNER_ID, NONFRIEND_ID,
				OWNER_ID, NONFRIEND_ID, OWNER_ID, NONFRIEND_ID);

		assertThatThrownBy(() -> PostgresTestDatabase.JDBC.update("""
				INSERT INTO friend_request
				    (id, academy_id, sender_id, receiver_id, student_low_id, student_high_id,
				     status, created_at, processed_at)
				VALUES (?, ?, ?, ?, LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid),
				        'PENDING', now(), NULL)
				""", UUID.randomUUID(), PRIMARY_ACADEMY_ID, NONFRIEND_ID, OWNER_ID,
				NONFRIEND_ID, OWNER_ID, NONFRIEND_ID, OWNER_ID))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> PostgresTestDatabase.JDBC.update("""
				INSERT INTO friend_request
				    (id, academy_id, sender_id, receiver_id, student_low_id, student_high_id,
				     status, created_at, processed_at)
				VALUES (?, ?, ?, ?, LEAST(?::uuid, ?::uuid), GREATEST(?::uuid, ?::uuid),
				        'ACCEPTED', now(), NULL)
				""", UUID.randomUUID(), PRIMARY_ACADEMY_ID, OWNER_ID, NONFRIEND_ID,
				OWNER_ID, NONFRIEND_ID, OWNER_ID, NONFRIEND_ID))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private static void insertWish(long amount, long target, String state) {
		PostgresTestDatabase.JDBC.update("""
				INSERT INTO wish
				    (id, account_id, academy_id, purpose, target_amount, wish_amount,
				     state, visibility, created_at, version)
				VALUES (?, ?, ?, 'invalid', ?, ?, ?, 'PRIVATE', now(), 0)
				""", UUID.randomUUID(), OWNER_ACCOUNT_ID, PRIMARY_ACADEMY_ID, target, amount, state);
	}
}
