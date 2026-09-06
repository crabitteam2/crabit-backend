package com.crabit.backend.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.CrabitBackendApplication;
import com.crabit.backend.wish.BalanceLookupMethod;
import com.crabit.backend.wish.CardBalanceObservationService;
import com.crabit.backend.wish.KrwAmount;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class HistoricalBalanceRecordingIT {

	private static final Instant BUSINESS_TIME = Instant.parse("2026-08-01T00:00:00Z");
	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse("postgres:16-alpine"));
	private static JdbcTemplate jdbc;
	private static TransactionTemplate transactions;

	@BeforeAll
	static void migrate() {
		DataSource source = dataSource(POSTGRES);
		Flyway.configure().dataSource(source).load().migrate();
		jdbc = new JdbcTemplate(source);
		transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
	}

	@Test
	void legacyMigrationCapturesActivationBaselineWithoutBackdatingLedgerApplicationTime() {
		try (PostgreSQLContainer legacy = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))) {
			legacy.start();
			DataSource source = dataSource(legacy);
			Flyway.configure().dataSource(source).target("16").load().migrate();
			JdbcTemplate old = new JdbcTemplate(source);
			Account account = createAccount(old);
			UUID wish = createWish(old, account, 300, 1000);
			UUID ledger = createLedger(old, account.id(), "CARD_BALANCE_CHANGE", 1000);
			UUID observation = UUID.randomUUID();
			old.update("""
					INSERT INTO balance_observation(id, account_id, status, lookup_method,
					actual_card_balance, first_successful, previous_successful_balance,
					balance_change_event_id, balance_change_event_type, balance_change_event_delta, observed_at)
					VALUES (?, ?, 'SUCCEEDED', 'USER_REQUESTED', 1000, TRUE, 0, ?, 'CARD_BALANCE_CHANGE', 1000, ?)
					""", observation, account.id(), ledger, Timestamp.from(BUSINESS_TIME));
			Instant beforeMigration = databaseNow(old);
			Flyway.configure().dataSource(source).load().migrate();
			Map<String, Object> baseline = old.queryForMap(
					"SELECT * FROM historical_balance_checkpoint WHERE account_id = ?", account.id());
			assertThat(baseline).containsEntry("revision", 1L).containsEntry("is_baseline", true)
					.containsEntry("active_wish_allocation", 300L).containsEntry("representative_wish_id", wish)
					.containsEntry("representative_target_amount", 1000L)
					.containsEntry("latest_observation_id", observation)
					.containsEntry("last_successful_observation_id", observation)
					.containsEntry("observation_lookup_version", null);
			assertThat(((Timestamp) baseline.get("applied_at")).toInstant()).isAfterOrEqualTo(beforeMigration)
					.isAfter(BUSINESS_TIME);
			assertThat(baseline.get("ledger_application_order")).isEqualTo(old.queryForObject(
					"SELECT application_order FROM ledger_event WHERE id = ?", Long.class, ledger));
			assertThat(old.queryForObject("SELECT count(*) FROM historical_ledger_application", Long.class)).isZero();
			assertThat(Flyway.configure().dataSource(source).load().migrate().migrationsExecuted).isZero();
			try (var application = new SpringApplicationBuilder(CrabitBackendApplication.class)
					.web(WebApplicationType.NONE)
					.properties("spring.main.banner-mode=off", "logging.level.root=warn")
					.run("--spring.datasource.url=" + legacy.getJdbcUrl(),
							"--spring.datasource.username=" + legacy.getUsername(),
							"--spring.datasource.password=" + legacy.getPassword(),
							"--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.enabled=false")) {
				var observations = application.getBean(CardBalanceObservationService.class);
				var refreshed = observations.recordSuccess(account.id(), BalanceLookupMethod.USER_REQUESTED,
						KrwAmount.nonNegative(1200), BUSINESS_TIME.plusSeconds(60));
				assertThat(refreshed.isFirstConnection()).isFalse();
				assertThat(refreshed.previousSuccessfulObservationId()).isEqualTo(observation);
				assertThat(refreshed.balanceChangeEventDelta()).isEqualTo(KrwAmount.positive(200));
				assertThat(refreshed.accountLookupVersion()).isEqualTo(1L);
				assertThat(old.queryForMap("SELECT * FROM historical_balance_checkpoint WHERE id = ?", baseline.get("id")))
						.isEqualTo(baseline);
				assertThat(old.queryForObject("SELECT count(*) FROM historical_ledger_application", Long.class)).isOne();
				assertThat(old.queryForObject("SELECT count(*) FROM historical_balance_checkpoint WHERE account_id = ?",
						Long.class, account.id())).isEqualTo(2);
			}
		}
	}

	@Test
	void newEmptyAccountGetsABaselineWithoutAnyReadInitializingHistory() {
		Instant beforeCreation = databaseNow(jdbc);
		Account account = createAccount(jdbc);
		Map<String, Object> baseline = latest(account.id());
		assertThat(baseline).containsEntry("revision", 1L).containsEntry("is_baseline", true)
				.containsEntry("ledger_application_order", 0L).containsEntry("active_wish_allocation", 0L)
				.containsEntry("latest_observation_id", null).containsEntry("last_successful_observation_id", null)
				.containsEntry("representative_wish_id", null);
		assertThat(((Timestamp) baseline.get("applied_at")).toInstant()).isAfterOrEqualTo(beforeCreation);
		assertThat(baseline.get("active_wishes").toString()).isEqualTo("[]");
		jdbc.queryForMap("SELECT * FROM card_balance_account WHERE id = ?", account.id());
		assertThat(checkpointCount(account.id())).isOne();
	}

	@Test
	void transactionFinalSnapshotsPreserveAtomicTransferAndHistoricalTargetChanges() {
		Account account = createAccount(jdbc);
		UUID source = createWish(jdbc, account, 300, 1000);
		UUID destination = createWish(jdbc, account, 400, 800);
		jdbc.update("UPDATE representative_wish_selection SET wish_id = ? WHERE account_id = ?", destination, account.id());
		long before = checkpointCount(account.id());
		Map<String, Object> prior = latest(account.id());
		transactions.executeWithoutResult(status -> {
			lock(account.id());
			jdbc.update("UPDATE wish SET wish_amount = 200 WHERE id = ?", source);
			jdbc.update("UPDATE wish SET wish_amount = 500 WHERE id = ?", destination);
			UUID event = createLedger(jdbc, account.id(), "WISH_TRANSFER", 0);
			createEffect(event, account.id(), source, -100);
			createEffect(event, account.id(), destination, 100);
			assertThat(checkpointCount(account.id())).isEqualTo(before);
		});
		assertThat(checkpointCount(account.id())).isEqualTo(before + 1);
		Map<String, Object> transferred = latest(account.id());
		assertThat(transferred).containsEntry("active_wish_allocation", 700L)
				.containsEntry("representative_wish_id", destination).containsEntry("representative_amount", 500L)
				.containsEntry("representative_target_amount", 800L);
		jdbc.update("UPDATE wish SET target_amount = 1000 WHERE id = ?", destination);
		assertThat(latest(account.id())).containsEntry("representative_amount", 500L)
				.containsEntry("representative_target_amount", 1000L);
		assertThat(jdbc.queryForMap("SELECT * FROM historical_balance_checkpoint WHERE id = ?", prior.get("id")))
				.isEqualTo(prior);
		assertThat(jdbc.queryForMap("SELECT * FROM historical_balance_checkpoint WHERE id = ?", transferred.get("id")))
				.isEqualTo(transferred);
	}

	@Test
	void repeatedSelectionAndNonfinancialEditsDoNotInventHistory() {
		Account account = createAccount(jdbc);
		UUID wish = createWish(jdbc, account, 300, 1000);
		long before = checkpointCount(account.id());
		jdbc.update("UPDATE representative_wish_selection SET wish_id = wish_id WHERE account_id = ?", account.id());
		jdbc.update("UPDATE wish SET purpose = 'Changed purpose', visibility = 'ACADEMY', version = version + 1 WHERE id = ?", wish);
		jdbc.update("UPDATE wish SET target_amount = target_amount, updated_at = clock_timestamp() WHERE id = ?", wish);
		assertThat(checkpointCount(account.id())).isEqualTo(before);
		assertThat(latest(account.id()).get("active_wishes").toString())
				.doesNotContain("purpose", "visibility", "photo", "profile", "Changed purpose");
	}

	@Test
	void firstZeroUnchangedSuccessAndFailureAdvanceLookupWithoutInventingLedgerEvents() {
		Account account = createAccount(jdbc);
		UUID first = recordZeroSuccess(account.id(), null, 1);
		Map<String, Object> firstCheckpoint = latest(account.id());
		assertThat(firstCheckpoint).containsEntry("latest_observation_id", first)
				.containsEntry("last_successful_observation_id", first).containsEntry("observation_lookup_version", 1L);
		UUID unchanged = recordZeroSuccess(account.id(), first, 2);
		UUID failed = UUID.randomUUID();
		transactions.executeWithoutResult(status -> {
			lock(account.id());
			jdbc.update("UPDATE card_balance_account SET balance_lookup_version = 3 WHERE id = ?", account.id());
			jdbc.update("""
					INSERT INTO balance_observation(id, account_id, status, lookup_method,
					failure_code, account_lookup_version, observed_at)
					VALUES (?, ?, 'FAILED', 'AUTO_DAILY', 'PROVIDER_TIMEOUT', 3, ?)
					""", failed, account.id(), Timestamp.from(BUSINESS_TIME.minusSeconds(60)));
		});
		assertThat(checkpointCount(account.id())).isEqualTo(4);
		assertThat(latest(account.id())).containsEntry("latest_observation_id", failed)
				.containsEntry("last_successful_observation_id", unchanged).containsEntry("observation_lookup_version", 3L)
				.containsEntry("ledger_application_order", 0L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_event WHERE account_id = ?", Long.class, account.id())).isZero();
		assertThat(jdbc.queryForMap("SELECT * FROM historical_balance_checkpoint WHERE id = ?", firstCheckpoint.get("id")))
				.isEqualTo(firstCheckpoint);
	}

	@Test
	void zeroMoneyTerminalTransitionRecordsFinalKnownAbsence() {
		Account account = createAccount(jdbc);
		UUID wish = createWish(jdbc, account, 0, 1000);
		long before = checkpointCount(account.id());
		transactions.executeWithoutResult(status -> {
			lock(account.id());
			jdbc.update("UPDATE wish SET state = 'ABANDONED', abandoned_at = ?, abandonment_amount = 0 WHERE id = ?",
					Timestamp.from(BUSINESS_TIME.plusSeconds(1)), wish);
			jdbc.update("DELETE FROM representative_wish_selection WHERE account_id = ?", account.id());
		});
		assertThat(checkpointCount(account.id())).isEqualTo(before + 1);
		assertThat(latest(account.id())).containsEntry("representative_wish_id", null)
				.containsEntry("representative_target_amount", null).containsEntry("representative_amount", null)
				.containsEntry("active_wish_allocation", 0L).containsEntry("ledger_application_order", 0L);
	}

	@Test
	void rollbackRemovesBothApplicationMetadataAndQueuedCheckpoint() {
		Account account = createAccount(jdbc);
		UUID wish = createWish(jdbc, account, 300, 1000);
		Map<String, Object> before = latest(account.id());
		long count = checkpointCount(account.id());
		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
			lock(account.id());
			jdbc.update("UPDATE wish SET target_amount = 1200 WHERE id = ?", wish);
			createLedger(jdbc, account.id(), "WISH_WITHDRAWAL", 1);
			throw new IllegalStateException("rollback-test");
		})).isInstanceOf(IllegalStateException.class).hasMessage("rollback-test");
		assertThat(checkpointCount(account.id())).isEqualTo(count);
		assertThat(latest(account.id())).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM historical_ledger_application WHERE account_id = ?",
				Long.class, account.id())).isZero();
	}

	@Test
	void immutableProvenanceRejectsUpdatesDeletesAndLateLedgerEffects() {
		Account account = createAccount(jdbc);
		UUID wish = createWish(jdbc, account, 0, 1000);
		UUID observation = recordZeroSuccess(account.id(), null, 1);
		UUID event = createLedger(jdbc, account.id(), "WISH_WITHDRAWAL", 0);
		UUID checkpoint = (UUID) latest(account.id()).get("id");
		for (String statement : List.of(
				"UPDATE historical_balance_checkpoint SET active_wish_allocation = 1 WHERE id = '" + checkpoint + "'",
				"DELETE FROM historical_balance_checkpoint WHERE id = '" + checkpoint + "'",
				"UPDATE historical_ledger_application SET applied_at = clock_timestamp() WHERE event_id = '" + event + "'",
				"DELETE FROM historical_ledger_application WHERE event_id = '" + event + "'",
				"UPDATE ledger_event SET account_delta = 1 WHERE id = '" + event + "'",
				"DELETE FROM ledger_event WHERE id = '" + event + "'",
				"UPDATE balance_observation SET observed_at = clock_timestamp() WHERE id = '" + observation + "'",
				"DELETE FROM balance_observation WHERE id = '" + observation + "'")) {
			assertThatThrownBy(() -> jdbc.update(statement)).isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThatThrownBy(() -> createEffect(event, account.id(), wish, 0))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void checkpointInsertionRejectsForeignWishIdentityAndInconsistentFinancialSnapshots() {
		Account account = createAccount(jdbc);
		Account foreign = createAccount(jdbc);
		UUID ownWish = createWish(jdbc, account, 0, 1000);
		UUID foreignWish = createWish(jdbc, foreign, 0, 1000);
		String ownFact = "{\"wishId\":\"" + ownWish + "\",\"state\":\"IN_PROGRESS\",\"targetAmount\":1000,\"amount\":0}";
		String foreignFact = ownFact.replace(ownWish.toString(), foreignWish.toString());
		for (String facts : List.of("[" + foreignFact + "]", "[" + ownFact + "," + ownFact + "]",
				"[" + ownFact.replace("\"amount\":0", "\"amount\":1") + "]",
				"[" + ownFact.replace("\"state\":\"IN_PROGRESS\"", "\"state\":\"AMOUNT_REACHED\"") + "]")) {
			assertThatThrownBy(() -> jdbc.update("""
					INSERT INTO historical_balance_checkpoint(id, account_id, revision, applied_at, is_baseline,
					ledger_application_order, active_wish_allocation, active_wishes)
					VALUES (?, ?, 3, clock_timestamp(), FALSE, 0, 0, ?::jsonb)
					""", UUID.randomUUID(), account.id(), facts)).isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThat(checkpointCount(account.id())).isEqualTo(2);
	}

	@Test
	void regressedClockCannotPlaceLedgerApplicationsOutsideTheirCheckpointInterval() {
		Account account = createAccount(jdbc);
		Instant priorTime = databaseNow(jdbc).plusSeconds(60);
		jdbc.update("""
				INSERT INTO historical_balance_checkpoint(id, account_id, revision, applied_at, is_baseline,
				ledger_application_order, active_wish_allocation, active_wishes)
				VALUES (?, ?, 2, ?, FALSE, 0, 0, '[]'::jsonb)
				""", UUID.randomUUID(), account.id(), Timestamp.from(priorTime));
		UUID first = createLedger(jdbc, account.id(), "WISH_WITHDRAWAL", 0);
		UUID second = createLedger(jdbc, account.id(), "WISH_WITHDRAWAL", 0);
		Instant checkpointTime = ((Timestamp) latest(account.id()).get("applied_at")).toInstant();
		assertThat(checkpointTime).isAfterOrEqualTo(priorTime);
		for (UUID event : List.of(first, second)) {
			Instant appliedAt = jdbc.queryForObject("SELECT applied_at FROM historical_ledger_application WHERE event_id = ?",
					Timestamp.class, event).toInstant();
			assertThat(appliedAt).isAfterOrEqualTo(priorTime).isBeforeOrEqualTo(checkpointTime);
		}
		assertThat(latest(account.id())).containsEntry("revision", 4L);
	}

	@Test
	void deferredSnapshotFailureRollsBackTheWholeFinancialTransaction() {
		Account account = createAccount(jdbc);
		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
			lock(account.id());
			createWish(jdbc, account, 9007199254740990L, 9007199254740991L);
			createWish(jdbc, account, 9007199254740990L, 9007199254740991L);
		})).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
		assertThat(checkpointCount(account.id())).isOne();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wish WHERE account_id = ?", Long.class, account.id())).isZero();
	}

	@Test
	void waitingWriterUsesPostLockTimeAndMonotonicAccountRevision() throws Exception {
		Account account = createAccount(jdbc);
		UUID wish = createWish(jdbc, account, 300, 1000);
		long count = checkpointCount(account.id());
		CountDownLatch firstLocked = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		AtomicReference<Instant> secondPreLock = new AtomicReference<>();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> transactions.executeWithoutResult(status -> {
				lock(account.id());
				firstLocked.countDown();
				await(secondStarted);
				jdbc.update("UPDATE wish SET target_amount = 1200 WHERE id = ?", wish);
			}));
			var second = pool.submit(() -> {
				await(firstLocked);
				transactions.executeWithoutResult(status -> {
					secondPreLock.set(databaseNow(jdbc));
					secondStarted.countDown();
					lock(account.id());
					jdbc.update("UPDATE wish SET target_amount = 1300 WHERE id = ?", wish);
				});
			});
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		}
		List<Map<String, Object>> recorded = jdbc.queryForList("""
				SELECT revision, applied_at, representative_target_amount FROM historical_balance_checkpoint
				WHERE account_id = ? AND revision > ? ORDER BY revision
				""", account.id(), count);
		assertThat(recorded).hasSize(2);
		assertThat(recorded.get(0)).containsEntry("revision", count + 1).containsEntry("representative_target_amount", 1200L);
		assertThat(recorded.get(1)).containsEntry("revision", count + 2).containsEntry("representative_target_amount", 1300L);
		assertThat(((Timestamp) recorded.get(1).get("applied_at")).toInstant())
				.isAfter(secondPreLock.get()).isAfterOrEqualTo(((Timestamp) recorded.get(0).get("applied_at")).toInstant());
	}

	private static Account createAccount(JdbcTemplate target) {
		Account account = new Account(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		target.update("INSERT INTO academy(id, name) VALUES (?, 'Historical Academy')", account.academyId());
		target.update("INSERT INTO student(id, nickname, age) VALUES (?, 'historical-student', 14)", account.studentId());
		target.update("INSERT INTO academy_membership(id, student_id, academy_id, joined_at) VALUES (?, ?, ?, ?)",
				UUID.randomUUID(), account.studentId(), account.academyId(), Timestamp.from(BUSINESS_TIME));
		target.update("INSERT INTO card_balance_account(id, student_id, academy_id, opened_at) VALUES (?, ?, ?, ?)",
				account.id(), account.studentId(), account.academyId(), Timestamp.from(BUSINESS_TIME));
		return account;
	}

	private static UUID createWish(JdbcTemplate target, Account account, long amount, long targetAmount) {
		UUID id = UUID.randomUUID();
		target.update("""
				INSERT INTO wish(id, account_id, academy_id, purpose, target_amount, wish_amount,
				state, visibility, created_at) VALUES (?, ?, ?, 'Historical wish', ?, ?, 'IN_PROGRESS', 'PRIVATE', ?)
				""", id, account.id(), account.academyId(), targetAmount, amount, Timestamp.from(BUSINESS_TIME));
		return id;
	}

	private static UUID createLedger(JdbcTemplate target, UUID account, String type, long delta) {
		UUID id = UUID.randomUUID();
		target.update("INSERT INTO ledger_event(id, account_id, event_type, account_delta, occurred_at) VALUES (?, ?, ?, ?, ?)",
				id, account, type, delta, Timestamp.from(BUSINESS_TIME));
		return id;
	}

	private static void createEffect(UUID event, UUID account, UUID wish, long delta) {
		jdbc.update("""
				INSERT INTO ledger_wish_effect(id, event_id, account_id, wish_id, wish_purpose_snapshot, wish_delta)
				VALUES (?, ?, ?, ?, 'Historical wish', ?)
				""", UUID.randomUUID(), event, account, wish, delta);
	}

	private static UUID recordZeroSuccess(UUID account, UUID previous, long version) {
		UUID id = UUID.randomUUID();
		transactions.executeWithoutResult(status -> {
			lock(account);
			jdbc.update("UPDATE card_balance_account SET balance_lookup_version = ? WHERE id = ?", version, account);
			jdbc.update("""
					INSERT INTO balance_observation(id, account_id, status, lookup_method, actual_card_balance,
					account_lookup_version, first_successful, previous_successful_observation_id,
					previous_successful_balance, observed_at)
					VALUES (?, ?, 'SUCCEEDED', 'USER_REQUESTED', 0, ?, ?, ?, 0, ?)
					""", id, account, version, previous == null ? true : null, previous, Timestamp.from(BUSINESS_TIME));
		});
		return id;
	}

	private static Map<String, Object> latest(UUID account) {
		return jdbc.queryForMap("SELECT * FROM historical_balance_checkpoint WHERE account_id = ? ORDER BY revision DESC LIMIT 1", account);
	}

	private static long checkpointCount(UUID account) {
		return jdbc.queryForObject("SELECT count(*) FROM historical_balance_checkpoint WHERE account_id = ?", Long.class, account);
	}

	private static void lock(UUID account) {
		jdbc.queryForObject("SELECT id FROM card_balance_account WHERE id = ? FOR UPDATE", UUID.class, account);
	}

	private static Instant databaseNow(JdbcTemplate target) {
		return target.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test did not rendezvous");
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}

	private static DataSource dataSource(PostgreSQLContainer container) {
		return new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
	}

	private record Account(UUID id, UUID studentId, UUID academyId) { }
}
