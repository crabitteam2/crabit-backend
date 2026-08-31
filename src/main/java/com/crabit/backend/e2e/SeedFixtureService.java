package com.crabit.backend.e2e;

import static com.crabit.backend.e2e.SeedFixtureCatalog.BLOCKED_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FIXTURE_TIME;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_STUDENT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile({"e2e", "demo"})
public class SeedFixtureService {

	private static final UUID OWNER_MEMBERSHIP_ID = id("00000000-0000-0000-0000-000000000501");
	private static final UUID FRIEND_MEMBERSHIP_ID = id("00000000-0000-0000-0000-000000000502");
	private static final UUID NONFRIEND_MEMBERSHIP_ID = id("00000000-0000-0000-0000-000000000503");
	private static final UUID BLOCKED_MEMBERSHIP_ID = id("00000000-0000-0000-0000-000000000504");
	private static final UUID OTHER_MEMBERSHIP_ID = id("00000000-0000-0000-0000-000000000505");
	private static final UUID FRIENDSHIP_ID = id("00000000-0000-0000-0000-000000000601");
	private static final UUID BLOCK_ID = id("00000000-0000-0000-0000-000000000701");
	private static final UUID LAPTOP_SHARED_CARD_ID = id("00000000-0000-0000-0000-000000000801");
	private static final UUID CAMP_SHARED_CARD_ID = id("00000000-0000-0000-0000-000000000802");
	static final long RESET_LOCK_ID = 0x435241424954L;

	private final JdbcTemplate jdbc;
	private final SeedFixtureCatalog fixtures;

	public SeedFixtureService(JdbcTemplate jdbc, SeedFixtureCatalog fixtures) {
		this.jdbc = jdbc;
		this.fixtures = fixtures;
	}

	@Transactional
	public void initialize() {
		insertFixtures();
	}

	@Transactional
	public void resetAndInitialize() {
		jdbc.execute("SELECT pg_advisory_xact_lock(" + RESET_LOCK_ID + ")");
		jdbc.execute("""
				TRUNCATE TABLE
				    mismatch_notification_outbox,
				    balance_adjustment_case_event,
				    balance_adjustment_case,
				    representative_wish_selection,
				    shared_card,
				    ledger_wish_effect,
				    balance_observation,
				    ledger_event,
				    wish,
				    card_balance_account,
				    friend_request,
				    friendship,
				    student_block,
				    academy_membership,
				    student,
				    academy
				RESTART IDENTITY
				""");
		insertFixtures();
	}

	private void insertFixtures() {
		jdbc.update("INSERT INTO academy (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
				PRIMARY_ACADEMY_ID, "크래빗 학원");
		jdbc.update("INSERT INTO academy (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
				OTHER_ACADEMY_ID, "다른 학원");

		fixtures.personas().stream()
				.filter(SeedFixtureCatalog.Persona::persistedStudent)
				.forEach(persona -> jdbc.update(
						"INSERT INTO student (id, nickname, age) VALUES (?, ?, ?) "
								+ "ON CONFLICT (id) DO NOTHING",
						persona.id(), persona.displayName(), persona.age()));

		insertMembership(OWNER_MEMBERSHIP_ID, OWNER_ID, PRIMARY_ACADEMY_ID);
		insertMembership(FRIEND_MEMBERSHIP_ID, FRIEND_ID, PRIMARY_ACADEMY_ID);
		insertMembership(NONFRIEND_MEMBERSHIP_ID, NONFRIEND_ID, PRIMARY_ACADEMY_ID);
		insertMembership(BLOCKED_MEMBERSHIP_ID, BLOCKED_ID, PRIMARY_ACADEMY_ID);
		insertMembership(OTHER_MEMBERSHIP_ID, OTHER_ACADEMY_STUDENT_ID, OTHER_ACADEMY_ID);

		jdbc.update("""
				INSERT INTO friendship
				    (id, academy_id, student_low_id, student_high_id, started_at, ended_at)
				VALUES (?, ?, ?, ?, ?, NULL)
				ON CONFLICT (id) DO NOTHING
				""", FRIENDSHIP_ID, PRIMARY_ACADEMY_ID, OWNER_ID, FRIEND_ID, timestamp());
		jdbc.update("""
				INSERT INTO student_block
				    (id, blocker_id, blocked_id, blocked_at, released_at)
				VALUES (?, ?, ?, ?, NULL)
				ON CONFLICT (id) DO NOTHING
				""", BLOCK_ID, OWNER_ID, BLOCKED_ID, timestamp());
		jdbc.update("""
				INSERT INTO card_balance_account
				    (id, student_id, academy_id, opened_at, closed_at, balance_lookup_version, version)
				VALUES (?, ?, ?, ?, NULL, 0, 0)
				ON CONFLICT (id) DO NOTHING
				""", OWNER_ACCOUNT_ID, OWNER_ID, PRIMARY_ACADEMY_ID, timestamp());

		fixtures.wishes().forEach(wish -> jdbc.update("""
				INSERT INTO wish
				    (id, account_id, academy_id, purpose, target_amount, wish_amount, state,
				     visibility, created_at, target_date, completed_at, deleted_at,
				     deleted_purpose_snapshot, version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 0)
				ON CONFLICT (id) DO NOTHING
				""", wish.id(), OWNER_ACCOUNT_ID, PRIMARY_ACADEMY_ID, wish.purpose(),
				wish.targetAmount(), wish.wishAmount(), wish.state(), wish.visibility(),
				timestamp(), wish.targetDate()));

		insertSharedCard(LAPTOP_SHARED_CARD_ID, LAPTOP_WISH_ID);
		insertSharedCard(CAMP_SHARED_CARD_ID, CAMP_WISH_ID);
	}

	private void insertMembership(UUID id, UUID studentId, UUID academyId) {
		jdbc.update("""
				INSERT INTO academy_membership (id, student_id, academy_id, joined_at, left_at)
				VALUES (?, ?, ?, ?, NULL)
				ON CONFLICT (id) DO NOTHING
				""", id, studentId, academyId, timestamp());
	}

	private void insertSharedCard(UUID id, UUID wishId) {
		jdbc.update("""
				INSERT INTO shared_card (id, wish_id, kind, visibility, updated_at)
				SELECT ?, wish.id,
				       CASE WHEN wish.state = 'COMPLETED' THEN 'COMPLETION' ELSE 'PROGRESS' END,
				       wish.visibility,
				       ?
				FROM wish
				WHERE wish.id = ?
				  AND wish.deleted_at IS NULL
				  AND wish.state <> 'ABANDONED'
				  AND wish.visibility <> 'PRIVATE'
				ON CONFLICT DO NOTHING
				""", id, timestamp(), wishId);
	}

	private static Timestamp timestamp() {
		return Timestamp.from(FIXTURE_TIME);
	}

	private static UUID id(String value) {
		return UUID.fromString(value);
	}
}
