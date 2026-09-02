package com.crabit.backend.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

class WishPhotoResetMigrationIT {

	private static final Instant EARLY = Instant.parse("2026-08-01T00:00:00Z");
	private static final UUID PHOTO = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private static final UUID ORPHAN = UUID.fromString("22222222-2222-4222-8222-222222222222");
	private static final String PREFIX = "wish-photos/" + SeedFixtureCatalog.OWNER_ID + "/" + PHOTO;

	@Test
	void populatedV12UpgradeAndRepeatedResetPreserveExactCleanupIdentitiesAndRetryState() {
		withV12Database(db -> {
			insertPhoto(db.jdbc);
			insertWork(db.jdbc, PHOTO, PREFIX);
			insertWork(db.jdbc, ORPHAN, "wish-photos/orphan/existing");
			db.migrate();
			assertThat(count(db.jdbc, "wish_photo")).isOne();
			assertThat(count(db.jdbc, "wish_photo_cleanup_work")).isEqualTo(2);
			db.reset();
			db.reset();
			assertThat(count(db.jdbc, "wish_photo")).isZero();
			assertThat(count(db.jdbc, "wish_photo_cleanup_work")).isEqualTo(2);
			assertThat(db.jdbc.queryForMap("SELECT object_prefix, requested_at, attempt_count, "
					+ "next_attempt_at, last_error FROM wish_photo_cleanup_work WHERE photo_id = ?", PHOTO))
					.containsEntry("object_prefix", PREFIX)
					.containsEntry("requested_at", Timestamp.from(EARLY))
					.containsEntry("attempt_count", 4)
					.containsEntry("next_attempt_at", Timestamp.from(EARLY.plusSeconds(3600)))
					.containsEntry("last_error", "storage deletion failed");
			assertThat(db.jdbc.queryForObject("SELECT object_prefix FROM wish_photo_cleanup_work "
					+ "WHERE photo_id = ?", String.class, ORPHAN)).isEqualTo("wish-photos/orphan/existing");
		});
	}

	@Test
	void resetQueuesEveryPhotoStateAndClearsOnlyEphemeralUploadState() {
		withV12Database(db -> {
			insertPhoto(db.jdbc);
			UUID attached = UUID.randomUUID();
			UUID deleting = UUID.randomUUID();
			db.jdbc.update("INSERT INTO wish_photo SELECT ?, owner_student_id, ?, 'ATTACHED', "
					+ "content_digest, ?, created_at, expires_at, NULL, 0 FROM wish_photo WHERE id = ?",
					attached, SeedFixtureCatalog.LAPTOP_WISH_ID, PREFIX + "-attached", PHOTO);
			db.jdbc.update("INSERT INTO wish_photo SELECT ?, owner_student_id, NULL, 'DELETE_PENDING', "
					+ "content_digest, ?, created_at, expires_at, ?, 0 FROM wish_photo WHERE id = ?",
					deleting, PREFIX + "-deleting", Timestamp.from(EARLY), PHOTO);
			db.jdbc.update("INSERT INTO wish_photo_processing_attempt VALUES (?, ?, ?)",
					UUID.randomUUID(), SeedFixtureCatalog.OWNER_ID, Timestamp.from(EARLY));
			db.jdbc.update("INSERT INTO wish_photo_upload_receipt VALUES (?, 'key', ?, "
					+ "jsonb_build_object('kind','ACTIVE_SUCCESS','retainUntil', ?), ?)",
					SeedFixtureCatalog.OWNER_ID, "a".repeat(64), EARLY.plusSeconds(86400).toString(), PHOTO);
			db.migrate();
			db.reset();
			assertThat(db.jdbc.queryForList("SELECT photo_id FROM wish_photo_cleanup_work", UUID.class))
					.containsExactlyInAnyOrder(PHOTO, attached, deleting);
			assertThat(count(db.jdbc, "wish_photo")).isZero();
			assertThat(count(db.jdbc, "wish_photo_upload_receipt")).isZero();
			assertThat(count(db.jdbc, "wish_photo_processing_attempt")).isZero();
			assertThat(db.jdbc.queryForObject("SELECT requested_at FROM wish_photo_cleanup_work "
					+ "WHERE photo_id = ?", Timestamp.class, deleting)).isEqualTo(Timestamp.from(EARLY));
		});
	}

	@Test
	void inconsistentPhotoPrefixFailsClosedAndRollsBackFixtureReset() {
		withV12Database(db -> {
			insertPhoto(db.jdbc);
			insertWork(db.jdbc, PHOTO, "wish-photos/different/identity");
			db.migrate();
			db.jdbc.update("UPDATE wish SET purpose = 'preserve me' WHERE id = ?", SeedFixtureCatalog.LAPTOP_WISH_ID);
			assertThatThrownBy(db::reset).hasStackTraceContaining("Wish photo cleanup identity mismatch");
			assertThat(count(db.jdbc, "wish_photo")).isOne();
			assertThat(db.jdbc.queryForObject("SELECT object_prefix FROM wish_photo_cleanup_work "
					+ "WHERE photo_id = ?", String.class, PHOTO)).isEqualTo("wish-photos/different/identity");
			assertThat(db.jdbc.queryForObject("SELECT purpose FROM wish WHERE id = ?", String.class,
					SeedFixtureCatalog.LAPTOP_WISH_ID)).isEqualTo("preserve me");
		});
	}

	@Test
	void laterFixtureInsertionFailureRollsBackNewCleanupWorkAndPhotoRemoval() {
		withV12Database(db -> {
			insertPhoto(db.jdbc);
			insertWork(db.jdbc, ORPHAN, "wish-photos/orphan/existing");
			db.migrate();
			db.jdbc.execute("CREATE FUNCTION reject_reset_insert() RETURNS trigger LANGUAGE plpgsql AS $$ "
					+ "BEGIN RAISE EXCEPTION 'injected fixture insertion failure'; END $$");
			db.jdbc.execute("CREATE TRIGGER reject_reset_insert BEFORE INSERT ON wish "
					+ "FOR EACH ROW EXECUTE FUNCTION reject_reset_insert()");
			assertThatThrownBy(db::reset).hasStackTraceContaining("injected fixture insertion failure");
			assertThat(count(db.jdbc, "wish_photo")).isOne();
			assertThat(db.jdbc.queryForList("SELECT photo_id FROM wish_photo_cleanup_work", UUID.class))
					.containsExactly(ORPHAN);
			assertThat(count(db.jdbc, "wish")).isEqualTo(2);
		});
	}

	private static void insertPhoto(JdbcTemplate jdbc) {
		jdbc.update("INSERT INTO wish_photo(id, owner_student_id, state, content_digest, object_prefix, "
				+ "created_at, expires_at) VALUES (?, ?, 'PENDING', ?, ?, ?, ?)", PHOTO,
				SeedFixtureCatalog.OWNER_ID, "a".repeat(64), PREFIX,
				Timestamp.from(EARLY), Timestamp.from(EARLY.plusSeconds(86400)));
	}

	private static void insertWork(JdbcTemplate jdbc, UUID id, String prefix) {
		jdbc.update("INSERT INTO wish_photo_cleanup_work VALUES (?, ?, ?, 4, ?, 'storage deletion failed')",
				id, prefix, Timestamp.from(EARLY), Timestamp.from(EARLY.plusSeconds(3600)));
	}

	private static long count(JdbcTemplate jdbc, String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}

	private static void withV12Database(Consumer<Database> test) {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")) {
			postgres.start();
			DriverManagerDataSource source = new DriverManagerDataSource(
					postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			Flyway.configure().dataSource(source).target("12").load().migrate();
			JdbcTemplate jdbc = new JdbcTemplate(source);
			SeedFixtureService fixtures = new SeedFixtureService(jdbc, new SeedFixtureCatalog());
			fixtures.initialize();
			test.accept(new Database(source, jdbc, fixtures));
		}
	}

	private record Database(DriverManagerDataSource source, JdbcTemplate jdbc, SeedFixtureService fixtures) {
		void migrate() { Flyway.configure().dataSource(source).load().migrate(); }
		void reset() {
			new TransactionTemplate(new DataSourceTransactionManager(source))
					.executeWithoutResult(status -> fixtures.resetAndInitialize());
		}
	}
}
