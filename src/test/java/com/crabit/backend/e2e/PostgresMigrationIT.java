package com.crabit.backend.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.CrabitBackendApplication;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class PostgresMigrationIT {

	private static final Instant OBSERVED_AT = Instant.parse("2026-08-18T00:00:00Z");

	@Test
	void migratesAnEmptyPostgresDatabaseAndIsIdempotent() {
		Set<String> tables = Set.copyOf(PostgresTestDatabase.JDBC.queryForList("""
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema = 'public'
				""", String.class));

			assertThat(tables).contains(
				"academy", "student", "academy_membership", "friendship", "student_block", "friend_request",
				"card_balance_account", "balance_observation", "wish", "ledger_event",
				"ledger_wish_effect", "balance_adjustment_case",
					"balance_adjustment_case_event", "mismatch_notification_outbox", "shared_card",
					"representative_wish_selection");
		assertThat(PostgresTestDatabase.FLYWAY.migrate().migrationsExecuted).isZero();

		Set<String> indexes = Set.copyOf(PostgresTestDatabase.JDBC.queryForList("""
				SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
				""", String.class));
		assertThat(indexes).contains(
				"uk_card_account_active", "uk_adjustment_case_open",
				"uk_shared_card_current_wish", "uk_mismatch_notification_case",
				"idx_shared_card_feed_order",
				"uk_ledger_event_application_order",
				"idx_ledger_event_account_application_order");
		assertThat(PostgresTestDatabase.JDBC.queryForObject("""
				SELECT count(*)
				FROM information_schema.columns
				WHERE table_schema = 'public'
				  AND table_name = 'ledger_event'
				  AND column_name = 'application_order'
				  AND is_nullable = 'NO'
				""", Long.class)).isOne();
	}

	@Test
	void backfillsOnlyTheSingleActiveWishOfEachOpenLegacyAccount() {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(
				DockerImageName.parse("postgres:16-alpine"))) {
			postgres.start();
			DataSource dataSource = dataSource(postgres);
			Flyway.configure()
					.dataSource(dataSource)
					.locations("classpath:db/migration")
					.target("6")
					.load()
					.migrate();
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			UUID academyId = UUID.randomUUID();
			UUID singleAccountId = UUID.randomUUID();
			UUID singleWishId = UUID.randomUUID();
			persistLegacyAccount(jdbc, academyId, UUID.randomUUID(), singleAccountId, "Single");
			insertLegacyWish(jdbc, singleWishId, singleAccountId, academyId, "IN_PROGRESS");

			UUID multiAccountId = UUID.randomUUID();
			persistLegacyAccount(jdbc, academyId, UUID.randomUUID(), multiAccountId, "Multiple");
			insertLegacyWish(jdbc, UUID.randomUUID(), multiAccountId, academyId, "IN_PROGRESS");
			insertLegacyWish(jdbc, UUID.randomUUID(), multiAccountId, academyId, "AMOUNT_REACHED");

			UUID closedAccountId = UUID.randomUUID();
			persistLegacyAccount(jdbc, academyId, UUID.randomUUID(), closedAccountId, "Closed");
			insertLegacyWish(jdbc, UUID.randomUUID(), closedAccountId, academyId, "IN_PROGRESS");
			jdbc.update("UPDATE card_balance_account SET closed_at = ? WHERE id = ?",
					timestamp(OBSERVED_AT), closedAccountId);

			Flyway.configure()
					.dataSource(dataSource)
					.locations("classpath:db/migration")
					.load()
					.migrate();

			assertThat(jdbc.queryForObject("""
					SELECT wish_id FROM representative_wish_selection WHERE account_id = ?
					""", UUID.class, singleAccountId)).isEqualTo(singleWishId);
			assertThat(jdbc.queryForObject("""
					SELECT count(*) FROM representative_wish_selection
					WHERE account_id IN (?, ?)
					""", Long.class, multiAccountId, closedAccountId)).isZero();
		}
	}

	@Test
	void upgradesLegacyOpeningEventsAndAcceptsAnEventlessFirstObservationCase() {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(
				DockerImageName.parse("postgres:16-alpine"))) {
			postgres.start();
			DataSource dataSource = dataSource(postgres);
			Flyway.configure()
					.dataSource(dataSource)
					.locations("classpath:db/migration")
					.target("2")
					.load()
					.migrate();
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);

			UUID academyId = UUID.randomUUID();
			UUID studentId = UUID.randomUUID();
			UUID accountId = UUID.randomUUID();
			UUID initialEventId = UUID.randomUUID();
			UUID firstObservationId = UUID.randomUUID();
			UUID decreaseEventId = UUID.randomUUID();
			UUID decreaseObservationId = UUID.randomUUID();
			UUID adjustmentId = UUID.randomUUID();
			persistLegacyAccount(jdbc, academyId, studentId, accountId, "Legacy Student");
			jdbc.update("""
					INSERT INTO ledger_event (
					  id, account_id, event_type, account_delta, occurred_at
					) VALUES (?, ?, 'CARD_BALANCE_CHANGE', 100, ?)
					""", initialEventId, accountId, timestamp(OBSERVED_AT.minusSeconds(1)));
			jdbc.update("""
					INSERT INTO balance_observation (
					  id, account_id, status, lookup_method, actual_card_balance,
					  first_successful, previous_successful_balance,
					  balance_change_event_id, balance_change_event_type,
					  balance_change_event_delta, observed_at
					) VALUES (?, ?, 'SUCCEEDED', 'USER_REQUESTED', 100,
					  TRUE, 0, ?, 'CARD_BALANCE_CHANGE', 100, ?)
					""", firstObservationId, accountId, initialEventId,
					timestamp(OBSERVED_AT.minusSeconds(1)));
			jdbc.update("""
					INSERT INTO ledger_event (
					  id, account_id, event_type, account_delta, occurred_at
					) VALUES (?, ?, 'CARD_BALANCE_CHANGE', -30, ?)
					""", decreaseEventId, accountId, timestamp(OBSERVED_AT));
			jdbc.update("""
					INSERT INTO balance_observation (
					  id, account_id, status, lookup_method, actual_card_balance,
					  previous_successful_observation_id, previous_successful_balance,
					  balance_change_event_id, balance_change_event_type,
					  balance_change_event_delta, observed_at
					) VALUES (?, ?, 'SUCCEEDED', 'USER_REQUESTED', 70,
					  ?, 100, ?, 'CARD_BALANCE_CHANGE', -30, ?)
					""", decreaseObservationId, accountId, firstObservationId,
					decreaseEventId, timestamp(OBSERVED_AT));
			jdbc.update("""
					INSERT INTO balance_adjustment_case (
					  id, account_id, opening_event_id, opening_event_type,
					  opening_event_delta, status, opened_shortage, opened_at
					) VALUES (?, ?, ?, 'CARD_BALANCE_CHANGE', -30, 'OPEN', 30, ?)
					""", adjustmentId, accountId, decreaseEventId, timestamp(OBSERVED_AT));
			jdbc.update("""
					INSERT INTO balance_adjustment_case_event (
					  id, adjustment_case_id, event_id, account_id, sequence_number, event_role
					) VALUES (?, ?, ?, ?, 0, 'OPENING')
					""", UUID.randomUUID(), adjustmentId, decreaseEventId, accountId);

			Flyway.configure()
					.dataSource(dataSource)
					.locations("classpath:db/migration")
					.load()
					.migrate();

			assertThat(jdbc.queryForObject("""
					SELECT opening_balance_observation_id
					FROM balance_adjustment_case WHERE id = ?
					""", UUID.class, adjustmentId)).isEqualTo(decreaseObservationId);
			assertThat(jdbc.queryForObject("""
					SELECT event_role FROM balance_adjustment_case_event
					WHERE adjustment_case_id = ?
					""", String.class, adjustmentId)).isEqualTo("OPENING_DECREASE");
			Long initialApplicationOrder = jdbc.queryForObject(
					"SELECT application_order FROM ledger_event WHERE id = ?",
					Long.class, initialEventId);
			Long decreaseApplicationOrder = jdbc.queryForObject(
					"SELECT application_order FROM ledger_event WHERE id = ?",
					Long.class, decreaseEventId);
			assertThat(initialApplicationOrder).isLessThan(decreaseApplicationOrder);
			UUID appendedEventId = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO ledger_event (
					  id, account_id, event_type, account_delta, occurred_at
					) VALUES (?, ?, 'CARD_BALANCE_CHANGE', 1, ?)
					""", appendedEventId, accountId, timestamp(OBSERVED_AT.plusSeconds(1)));
			assertThat(jdbc.queryForObject(
					"SELECT application_order FROM ledger_event WHERE id = ?",
					Long.class, appendedEventId)).isGreaterThan(decreaseApplicationOrder);

			UUID eventlessStudentId = UUID.randomUUID();
			UUID eventlessAccountId = UUID.randomUUID();
			UUID eventlessObservationId = UUID.randomUUID();
			persistLegacyAccount(
					jdbc, academyId, eventlessStudentId, eventlessAccountId, "First Observer");
			jdbc.update("""
					INSERT INTO balance_observation (
					  id, account_id, status, lookup_method, actual_card_balance,
					  first_successful, previous_successful_balance, observed_at
					) VALUES (?, ?, 'SUCCEEDED', 'USER_REQUESTED', 0, TRUE, 0, ?)
					""", eventlessObservationId, eventlessAccountId, timestamp(OBSERVED_AT));
			int inserted = insertEventlessAdjustmentCase(
					jdbc, eventlessAccountId, eventlessObservationId);
			assertThat(inserted).isOne();

			UUID failedStudentId = UUID.randomUUID();
			UUID failedAccountId = UUID.randomUUID();
			UUID failedObservationId = UUID.randomUUID();
			persistLegacyAccount(
					jdbc, academyId, failedStudentId, failedAccountId, "Failed Observer");
			jdbc.update("""
					INSERT INTO balance_observation (
					  id, account_id, status, lookup_method, failure_code, observed_at
					) VALUES (?, ?, 'FAILED', 'USER_REQUESTED', 'UPSTREAM_TIMEOUT', ?)
					""", failedObservationId, failedAccountId, timestamp(OBSERVED_AT));
			assertThatThrownBy(() -> insertEventlessAdjustmentCase(
					jdbc, failedAccountId, failedObservationId))
					.isInstanceOf(DataIntegrityViolationException.class)
					.hasMessageContaining("fk_adjustment_eventless_first_success");
			UUID successfulForeignObservationId = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO balance_observation (
					  id, account_id, status, lookup_method, actual_card_balance,
					  first_successful, previous_successful_balance, observed_at
					) VALUES (?, ?, 'SUCCEEDED', 'USER_REQUESTED', 0, TRUE, 0, ?)
					""", successfulForeignObservationId, failedAccountId,
					timestamp(OBSERVED_AT));

			UUID foreignStudentId = UUID.randomUUID();
			UUID foreignAccountId = UUID.randomUUID();
			persistLegacyAccount(
					jdbc, academyId, foreignStudentId, foreignAccountId, "Foreign Account");
			assertThatThrownBy(() -> insertEventlessAdjustmentCase(
					jdbc, foreignAccountId, successfulForeignObservationId))
					.isInstanceOf(DataIntegrityViolationException.class)
					.hasMessageContaining("fk_adjustment_opening_observation_origin");

			assertThat(Flyway.configure()
					.dataSource(dataSource)
					.locations("classpath:db/migration")
					.load()
					.migrate().migrationsExecuted).isZero();
		}
	}

	@Test
	void v8BackfillsLegacyTerminalTimesAndHistoricalReplaySnapshots() {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(
				DockerImageName.parse("postgres:16-alpine"))) {
			postgres.start();
			DataSource dataSource = dataSource(postgres);
			resetToVersion7(dataSource);
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			UUID academyId = UUID.randomUUID();

			LegacyWish funded = insertVersion7Wish(jdbc, academyId, "ABANDONED", false);
			insertAbandonmentReturn(jdbc, funded, funded.terminalAt());
			setIdempotencyRecords(jdbc, funded.studentId(), idempotencyEntry(
					"abandon-funded", "ABANDON", funded.wishId(), funded.terminalAt(),
					funded, null));

			LegacyWish zeroFunded = insertVersion7Wish(jdbc, academyId, "ABANDONED", false);
			setIdempotencyRecords(jdbc, zeroFunded.studentId(), idempotencyEntry(
					"abandon-zero", "ABANDON", zeroFunded.wishId(), zeroFunded.terminalAt(),
					zeroFunded, null));

			LegacyWish deleted = insertVersion7Wish(jdbc, academyId, "ABANDONED", true);
			setIdempotencyRecords(jdbc, deleted.studentId(), idempotencyEntry(
					"abandon-deleted", "ABANDON", deleted.wishId(), deleted.terminalAt(),
					deleted, null));

			LegacyWish completed = insertVersion7Wish(jdbc, academyId, "COMPLETED", false);
			setIdempotencyRecords(jdbc, completed.studentId(), idempotencyEntry(
					"complete-history", "COMPLETE", completed.wishId(), completed.terminalAt(),
					completed, null));

			LegacyWish active = insertVersion7Wish(jdbc, academyId, "IN_PROGRESS", false);
			setIdempotencyRecords(jdbc, active.studentId(), idempotencyEntry(
					"transfer-history", "TRANSFER", active.wishId(), active.terminalAt(),
					active, deleted));

			assertThat(Flyway.configure()
					.dataSource(dataSource)
					.locations("classpath:db/migration")
					.load()
					.migrate().migrationsExecuted).isOne();

			assertAbandonedAt(jdbc, funded, funded.terminalAt());
			assertAbandonedAt(jdbc, zeroFunded, zeroFunded.terminalAt());
			assertAbandonedAt(jdbc, deleted, deleted.terminalAt());
			assertAbandonedAt(jdbc, completed, null);
			assertAbandonedAt(jdbc, active, null);

			assertJsonInstant(jdbc, funded.studentId(),
					"{abandon-funded,snapshot,closedAt}", funded.terminalAt());
			assertJsonInstant(jdbc, zeroFunded.studentId(),
					"{abandon-zero,snapshot,closedAt}", zeroFunded.terminalAt());
			assertJsonInstant(jdbc, deleted.studentId(),
					"{abandon-deleted,snapshot,closedAt}", deleted.terminalAt());
			assertJsonInstant(jdbc, completed.studentId(),
					"{complete-history,snapshot,closedAt}", completed.terminalAt());
			assertJsonInstant(jdbc, active.studentId(),
					"{transfer-history,snapshot,closedAt}", null);
			assertJsonInstant(jdbc, active.studentId(),
					"{transfer-history,destinationSnapshot,closedAt}", deleted.terminalAt());
		}
	}

	@Test
	void v8RejectsMissingDuplicateConflictingMalformedAndPreCreationProvenance() {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(
				DockerImageName.parse("postgres:16-alpine"))) {
			postgres.start();
			DataSource dataSource = dataSource(postgres);

			for (String failure : List.of(
					"missing", "duplicate-idempotency", "duplicate-return",
					"conflicting", "malformed", "before-created-at")) {
				resetToVersion7(dataSource);
				JdbcTemplate jdbc = new JdbcTemplate(dataSource);
				LegacyWish wish = insertVersion7Wish(
						jdbc, UUID.randomUUID(), "ABANDONED", false);

				switch (failure) {
					case "missing" -> {
						// The V7 default is an empty idempotency namespace and there is no return event.
					}
					case "duplicate-idempotency" -> setIdempotencyRecords(
							jdbc, wish.studentId(),
							idempotencyEntry("abandon-one", "ABANDON", wish.wishId(),
									wish.terminalAt(), wish, null),
							idempotencyEntry("abandon-two", "ABANDON", wish.wishId(),
									wish.terminalAt(), wish, null));
					case "duplicate-return" -> {
						setIdempotencyRecords(jdbc, wish.studentId(), idempotencyEntry(
								"abandon", "ABANDON", wish.wishId(), wish.terminalAt(),
								wish, null));
						insertAbandonmentReturn(jdbc, wish, wish.terminalAt());
						insertAbandonmentReturn(jdbc, wish, wish.terminalAt());
					}
					case "conflicting" -> {
						setIdempotencyRecords(jdbc, wish.studentId(), idempotencyEntry(
								"abandon", "ABANDON", wish.wishId(), wish.terminalAt(),
								wish, null));
						insertAbandonmentReturn(jdbc, wish, wish.terminalAt().plusSeconds(1));
					}
					case "malformed" -> setIdempotencyRecords(jdbc, wish.studentId(), """
							"abandon":{"operation":"ABANDON","targetId":"%s","httpStatus":200,
							"recordedAt":"not-an-instant","snapshot":{"id":"%s",
							"state":"ABANDONED","completedAt":null}}
							""".formatted(wish.wishId(), wish.wishId()));
					case "before-created-at" -> setIdempotencyRecords(
							jdbc, wish.studentId(), idempotencyEntry(
									"abandon", "ABANDON", wish.wishId(),
									wish.createdAt().minusSeconds(1), wish, null));
					default -> throw new IllegalStateException("Unhandled case: " + failure);
				}

				var migration = assertThatThrownBy(() -> Flyway.configure()
						.dataSource(dataSource)
						.locations("classpath:db/migration")
						.load()
						.migrate())
						.as(failure + " provenance must fail closed");
				if (failure.equals("malformed")) {
					migration.hasStackTraceContaining("invalid input syntax for type timestamp with time zone");
				}
				else {
					migration.hasStackTraceContaining(
							"Ambiguous or invalid Wish abandonment provenance");
				}
				assertThat(jdbc.queryForObject("""
						SELECT count(*)
						FROM information_schema.columns
						WHERE table_schema = 'public'
						  AND table_name = 'wish'
						  AND column_name = 'abandoned_at'
						""", Long.class)).as(failure + " rollback").isZero();
			}
		}
	}

	@Test
	void startsTheE2eApplicationOnEmptyPostgresAndKeepsSeedComponentsOutOfProduction() {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(
				DockerImageName.parse("postgres:16-alpine"))) {
			postgres.start();

			try (ConfigurableApplicationContext e2e = startApplication(postgres, "e2e")) {
				assertThat(e2e.getBeansOfType(SeedFixtureInitializer.class)).hasSize(1);
				assertThat(e2e.getBeansOfType(SeedTokenRegistry.class)).hasSize(1);
			}

			JdbcTemplate jdbc = new JdbcTemplate(dataSource(postgres));
			assertThat(jdbc.queryForObject(
					"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'",
						Long.class)).isEqualTo(17L);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM student", Long.class)).isEqualTo(5L);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM wish", Long.class)).isEqualTo(2L);

			try (ConfigurableApplicationContext e2e = startApplication(postgres, "e2e")) {
				assertThat(e2e.getBeansOfType(SeedFixtureInitializer.class)).hasSize(1);
			}
			assertThat(jdbc.queryForObject("SELECT count(*) FROM student", Long.class)).isEqualTo(5L);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM wish", Long.class)).isEqualTo(2L);

			try (ConfigurableApplicationContext prod = startApplication(postgres, "prod")) {
				assertThat(prod.getBeansOfType(SeedFixtureCatalog.class)).isEmpty();
				assertThat(prod.getBeansOfType(SeedFixtureService.class)).isEmpty();
				assertThat(prod.getBeansOfType(SeedFixtureInitializer.class)).isEmpty();
				assertThat(prod.getBeansOfType(SeedTokenRegistry.class)).isEmpty();
				assertThat(prod.getBeansOfType(SeedBearerAuthenticationFilter.class)).isEmpty();
			}
		}
	}

	private static ConfigurableApplicationContext startApplication(
			PostgreSQLContainer postgres,
			String profile) {
		return new SpringApplicationBuilder(CrabitBackendApplication.class)
				.web(WebApplicationType.NONE)
				.profiles(profile)
				.initializers(applicationContext -> {
					GenericApplicationContext context =
							(GenericApplicationContext) applicationContext;
					context.registerBean(
							"postgresMigrationTestClock",
							Clock.class,
							() -> "e2e".equals(profile)
									? Clock.fixed(
											Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC)
									: Clock.systemUTC(),
							definition -> definition.setPrimary(true));
				})
				.properties("spring.main.banner-mode=off", "logging.level.root=warn")
				.run(
						"--spring.datasource.url=" + postgres.getJdbcUrl(),
						"--spring.datasource.username=" + postgres.getUsername(),
						"--spring.datasource.password=" + postgres.getPassword());
	}

	private static void resetToVersion7(DataSource dataSource) {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DROP SCHEMA IF EXISTS public CASCADE");
		jdbc.execute("CREATE SCHEMA public");
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.target("7")
				.load()
				.migrate();
	}

	private static LegacyWish insertVersion7Wish(
			JdbcTemplate jdbc, UUID academyId, String state, boolean deleted) {
		UUID studentId = UUID.randomUUID();
		UUID accountId = UUID.randomUUID();
		UUID wishId = UUID.randomUUID();
		Instant createdAt = OBSERVED_AT.minusSeconds(5);
		Instant terminalAt = OBSERVED_AT;
		persistLegacyAccount(jdbc, academyId, studentId, accountId,
				"Legacy " + studentId.toString().substring(0, 8));
		Timestamp completedAt = "COMPLETED".equals(state) ? timestamp(terminalAt) : null;
		Timestamp deletedAt = deleted ? timestamp(terminalAt.plusSeconds(1)) : null;
		String deletedPurpose = deleted ? "Legacy Wish" : null;
		Instant updatedAt = deleted ? terminalAt.plusSeconds(1)
				: ("IN_PROGRESS".equals(state) ? createdAt : terminalAt);
		jdbc.update("""
				INSERT INTO wish (
				  id, account_id, academy_id, purpose, target_amount, wish_amount,
				  state, visibility, created_at, updated_at, completed_at,
				  deleted_at, deleted_purpose_snapshot
				) VALUES (?, ?, ?, 'Legacy Wish', 100, 0, ?, 'PRIVATE', ?, ?, ?, ?, ?)
				""", wishId, accountId, academyId, state, timestamp(createdAt),
				timestamp(updatedAt), completedAt, deletedAt, deletedPurpose);
		return new LegacyWish(
				studentId, accountId, wishId, state, createdAt, terminalAt, completedAt);
	}

	private static void insertAbandonmentReturn(
			JdbcTemplate jdbc, LegacyWish wish, Instant occurredAt) {
		UUID eventId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO ledger_event (
				  id, account_id, event_type, account_delta, occurred_at
				) VALUES (?, ?, 'WISH_ABANDONMENT_RETURN', 100, ?)
				""", eventId, wish.accountId(), timestamp(occurredAt));
		jdbc.update("""
				INSERT INTO ledger_wish_effect (
				  id, event_id, account_id, wish_id, wish_purpose_snapshot, wish_delta
				) VALUES (?, ?, ?, ?, 'Legacy Wish', -100)
				""", UUID.randomUUID(), eventId, wish.accountId(), wish.wishId());
	}

	private static void setIdempotencyRecords(
			JdbcTemplate jdbc, UUID studentId, String... entries) {
		jdbc.update("""
				UPDATE student
				SET wish_idempotency_records = CAST(? AS jsonb)
				WHERE id = ?
				""", "{" + String.join(",", entries) + "}", studentId);
	}

	private static String idempotencyEntry(
			String key,
			String operation,
			UUID targetId,
			Instant recordedAt,
			LegacyWish snapshot,
			LegacyWish destinationSnapshot) {
		String destination = destinationSnapshot == null ? "" : """
				,"destinationSnapshot":{"id":"%s","state":"%s","completedAt":%s}
				""".formatted(
					destinationSnapshot.wishId(),
					destinationSnapshot.state(),
					jsonInstant(destinationSnapshot.completedAt()));
		return """
				"%s":{"operation":"%s","targetId":"%s","httpStatus":200,
				"recordedAt":"%s","snapshot":{"id":"%s","state":"%s","completedAt":%s}%s}
				""".formatted(
				key, operation, targetId, recordedAt,
				snapshot.wishId(), snapshot.state(), jsonInstant(snapshot.completedAt()), destination)
				.replace("\n", "");
	}

	private static String jsonInstant(Timestamp instant) {
		return instant == null ? "null" : "\"" + instant.toInstant() + "\"";
	}

	private static void assertAbandonedAt(
			JdbcTemplate jdbc, LegacyWish wish, Instant expected) {
		Timestamp actual = jdbc.queryForObject(
				"SELECT abandoned_at FROM wish WHERE id = ?", Timestamp.class, wish.wishId());
		assertThat(actual == null ? null : actual.toInstant()).isEqualTo(expected);
	}

	private static void assertJsonInstant(
			JdbcTemplate jdbc, UUID studentId, String path, Instant expected) {
		String actual = jdbc.queryForObject("""
				SELECT wish_idempotency_records #>> CAST(? AS text[])
				FROM student WHERE id = ?
				""", String.class, path, studentId);
		if (expected == null) {
			assertThat(actual).isNull();
			return;
		}
		assertThat(actual).endsWith("Z");
		assertThat(Instant.parse(actual)).isEqualTo(expected);
	}

	private static DataSource dataSource(PostgreSQLContainer postgres) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName(postgres.getDriverClassName());
		dataSource.setUrl(postgres.getJdbcUrl());
		dataSource.setUsername(postgres.getUsername());
		dataSource.setPassword(postgres.getPassword());
		return dataSource;
	}

	private static void persistLegacyAccount(
			JdbcTemplate jdbc,
			UUID academyId,
			UUID studentId,
			UUID accountId,
			String nickname) {
		jdbc.update("""
				INSERT INTO academy (id, name) VALUES (?, ?)
				ON CONFLICT (id) DO NOTHING
				""", academyId, "Migration Academy");
		jdbc.update("INSERT INTO student (id, nickname) VALUES (?, ?)", studentId, nickname);
		jdbc.update("""
				INSERT INTO card_balance_account (
				  id, student_id, academy_id, opened_at
				) VALUES (?, ?, ?, ?)
				""", accountId, studentId, academyId,
				timestamp(OBSERVED_AT.minusSeconds(10)));
	}

	private static void insertLegacyWish(
			JdbcTemplate jdbc,
			UUID wishId,
			UUID accountId,
			UUID academyId,
			String state) {
		long amount = "AMOUNT_REACHED".equals(state) ? 100 : 0;
		jdbc.update("""
				INSERT INTO wish (
				  id, account_id, academy_id, purpose, target_amount, wish_amount,
				  state, visibility, created_at
				) VALUES (?, ?, ?, 'Legacy Wish', 100, ?, ?, 'PRIVATE', ?)
				""", wishId, accountId, academyId, amount, state,
				timestamp(OBSERVED_AT.minusSeconds(5)));
	}

	private static int insertEventlessAdjustmentCase(
			JdbcTemplate jdbc, UUID accountId, UUID openingObservationId) {
		return jdbc.update("""
				INSERT INTO balance_adjustment_case (
				  id, account_id, opening_balance_observation_id,
				  opening_balance_observation_first_successful,
				  status, opened_shortage, opened_at
				) VALUES (?, ?, ?, TRUE, 'OPEN', 1, ?)
				""", UUID.randomUUID(), accountId,
				openingObservationId, timestamp(OBSERVED_AT));
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}

	private record LegacyWish(
			UUID studentId,
			UUID accountId,
			UUID wishId,
			String state,
			Instant createdAt,
			Instant terminalAt,
			Timestamp completedAt) {
	}
}
