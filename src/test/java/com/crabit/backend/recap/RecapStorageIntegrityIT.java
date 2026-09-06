package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.wish.SharedCardQueryRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecapStorageIntegrityIT {
	private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
	private ConfigurableApplicationContext context;
	private JdbcTemplate jdbc;
	private RecapGenerationCoordinator coordinator;
	private RecapGenerationRepository generations;
	private UUID account, student, academy;
	private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
	private static final RecapPeriods.Period WEEK = new RecapPeriods.Period(LocalDate.parse("2026-08-24"), LocalDate.parse("2026-08-31"));
	private static final RecapPeriods.Period MONTH = new RecapPeriods.Period(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-09-01"));

	@BeforeAll void start() { postgres.start(); open(); }
	@AfterAll void stop() { if (context != null) context.close(); postgres.stop(); }
	private void open() {
		context = RecapRegenerationCommand.start("--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(), "--spring.datasource.password=" + postgres.getPassword(),
				"--spring.jpa.hibernate.ddl-auto=validate", "--logging.level.root=warn",
				"--spring.profiles.active=demo", "--crabit.demo.lifecycle=reset");
		jdbc = context.getBean(JdbcTemplate.class); coordinator = context.getBean(RecapGenerationCoordinator.class);
		generations = context.getBean(RecapGenerationRepository.class);
	}
	@BeforeEach void seed() {
		jdbc.execute("TRUNCATE academy, student CASCADE");
		academy = UUID.randomUUID(); student = UUID.randomUUID(); account = UUID.randomUUID();
		jdbc.update("insert into academy(id,name) values (?,'storage test')", academy);
		jdbc.update("insert into student(id,nickname,age) values (?,'owner',15)", student);
		jdbc.update("insert into academy_membership(id,student_id,academy_id,joined_at) values (?,?,?,?)", UUID.randomUUID(), student, academy, Timestamp.from(NOW.minusSeconds(86400 * 30)));
		jdbc.update("insert into card_balance_account(id,student_id,academy_id,opened_at) values (?,?,?,?)", account, student, academy, Timestamp.from(NOW.minusSeconds(86400 * 30)));
	}

	@Test void concurrentScheduledReservationAndExplicitKeysKeepSeparateStableIdentities() throws Exception {
		try (var pool = Executors.newFixedThreadPool(6)) {
			List<Callable<UUID>> work = new ArrayList<>();
			for (int i = 0; i < 12; i++) work.add(() -> coordinator.reserveScheduled(account, RecapKind.WEEKLY, WEEK, NOW).id());
			var ids = new ArrayList<UUID>(); for (var result : pool.invokeAll(work)) ids.add(result.get());
			assertThat(ids).containsOnly(ids.getFirst());
			assertThat(generations.count()).isOne();
			assertThat(generations.findById(ids.getFirst()).orElseThrow().requestJson()).isNull();
		}
		UUID key = UUID.randomUUID(); var explicit = coordinator.reserveRegeneration(key, account, RecapKind.WEEKLY, WEEK, NOW);
		assertThat(explicit.generationVersion()).isEqualTo(2);
		assertThat(coordinator.reserveRegeneration(key, account, RecapKind.WEEKLY, WEEK, NOW.plusSeconds(10)).id()).isEqualTo(explicit.id());
		assertThatThrownBy(() -> coordinator.reserveRegeneration(key, account, RecapKind.MONTHLY, MONTH, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThat(generations.count()).isEqualTo(2);
	}

	@Test void concurrentExplicitKeyAcrossDifferentAccountsRejectsOneWithoutPartialReservation() throws Exception {
		UUID otherAccount = UUID.randomUUID(), otherStudent = UUID.randomUUID(), key = UUID.randomUUID();
		jdbc.update("insert into student(id,nickname,age) values (?,'other',15)", otherStudent);
		jdbc.update("insert into card_balance_account(id,student_id,academy_id,opened_at) values (?,?,?,?)", otherAccount, otherStudent, academy, Timestamp.from(NOW));
		try (var pool = Executors.newFixedThreadPool(2)) {
			var barrier = new java.util.concurrent.CyclicBarrier(2);
			List<Callable<Boolean>> operations = List.of(
				() -> { barrier.await(); try { coordinator.reserveRegeneration(key, account, RecapKind.WEEKLY, WEEK, NOW); return true; } catch (RuntimeException e) { return false; } },
				() -> { barrier.await(); try { coordinator.reserveRegeneration(key, otherAccount, RecapKind.WEEKLY, WEEK, NOW); return true; } catch (RuntimeException e) { return false; } });
			var results = pool.invokeAll(operations);
			assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
		}
		assertThat(generations.count()).isOne();
		assertThat(jdbc.queryForObject("select generation_version from recap_generation", Long.class)).isOne();
	}

	@Test void legacyAdoptionChangesOnlyTheKeyAndUsesTheEarliestVersion() throws Exception {
		var legacy = coordinator.reserve(UUID.randomUUID(), account, student, academy, RecapKind.WEEKLY, WEEK.start(), WEEK.endExclusive(), "sha256:legacy", "{\"frozen\":1}", NOW);
		coordinator.reserve(UUID.randomUUID(), account, student, academy, RecapKind.WEEKLY, WEEK.start(), WEEK.endExclusive(), "sha256:newer", "{\"frozen\":2}", NOW);
		String before = jdbc.queryForObject("select (to_jsonb(g)-'reservation_key')::text from recap_generation g where id=?", String.class, legacy.id());
		try (var pool = Executors.newFixedThreadPool(4)) {
			List<Callable<UUID>> work = new ArrayList<>();
			for (int i=0; i<8; i++) work.add(() -> coordinator.reserveScheduled(account, RecapKind.WEEKLY, WEEK, NOW).id());
			for (var result : pool.invokeAll(work)) assertThat(result.get()).isEqualTo(legacy.id());
		}
		assertThat(jdbc.queryForObject("select (to_jsonb(g)-'reservation_key')::text from recap_generation g where id=?", String.class, legacy.id())).isEqualTo(before);
		assertThat(generations.count()).isEqualTo(2);
	}

	@Test void preparationFailureIsDurableAndOnlyTheNewClaimCanFreezeInputAcrossRestart() {
		var reserved = coordinator.reserveScheduled(account, RecapKind.WEEKLY, WEEK, NOW);
		assertThat(query().status()).isEqualTo("GENERATING"); assertThat(query().generationVersion()).isEqualTo(1);
		var old = coordinator.claimPreparation(NOW).orElseThrow();
		assertThat(coordinator.claim(NOW)).isEmpty();
		coordinator.failPreparation(old, true, NOW);
		assertThat(query().status()).isEqualTo("FAILED"); assertThat(query().generatedAt()).isNull();
		assertThat(coordinator.claimPreparation(NOW.plusSeconds(59))).isEmpty();
		var next = coordinator.claimPreparation(NOW.plusSeconds(60)).orElseThrow();
		coordinator.prepared(old, snapshot(old, "sha256:stale", "{\"stale\":true}", 0), NOW);
		assertThat(generations.findById(reserved.id()).orElseThrow().requestJson()).isNull();
		var snapshot = new RecapSnapshotService(jdbc, new ObjectMapper()).build(next.id(), account, RecapKind.WEEKLY, WEEK);
		coordinator.prepared(next, snapshot, NOW.plusSeconds(61));
		assertThat(generations.findById(reserved.id()).orElseThrow().requestJson()).isEqualTo(snapshot.requestJson());
		assertThatThrownBy(() -> jdbc.update("update recap_generation set request_json='{}' where id=?", reserved.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
		context.close(); open();
		var claim = coordinator.claim(NOW.plusSeconds(62)).orElseThrow();
		assertThat(claim.requestJson()).isEqualTo(snapshot.requestJson()); assertThat(claim.inputDigest()).isEqualTo(snapshot.inputDigest());
		assertThat(claim.attempt()).isOne();
		coordinator.prepared(next, snapshot(next, "sha256:replacement", "{}", 0), NOW.plusSeconds(63));
		assertThat(generations.findById(reserved.id()).orElseThrow().requestJson()).isEqualTo(snapshot.requestJson());
		coordinator.fail(claim, "UNAVAILABLE", true, NOW.plusSeconds(64));
		var retry = coordinator.claim(NOW.plusSeconds(124)).orElseThrow();
		assertThat(retry.requestJson()).isEqualTo(claim.requestJson()); assertThat(retry.attempt()).isEqualTo(2);
	}

	@Test void simultaneousWorkersClaimEachStageOnlyOnce() throws Exception {
		var reserved = coordinator.reserveScheduled(account, RecapKind.WEEKLY, WEEK, NOW);
		RecapGenerationCoordinator.PreparationClaim preparation;
		try (var pool = Executors.newFixedThreadPool(2)) {
			var barrier = new java.util.concurrent.CyclicBarrier(2);
			List<Callable<java.util.Optional<RecapGenerationCoordinator.PreparationClaim>>> work = List.of(
				() -> { barrier.await(); return coordinator.claimPreparation(NOW); },
				() -> { barrier.await(); return coordinator.claimPreparation(NOW); });
			var results = pool.invokeAll(work);
			var claimed = java.util.stream.Stream.of(results.get(0).get(), results.get(1).get()).flatMap(java.util.Optional::stream).toList();
			assertThat(claimed).hasSize(1); preparation = claimed.getFirst();
		}
		coordinator.prepared(preparation, snapshot(preparation, "sha256:parallel", "{}", 0), NOW);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var barrier = new java.util.concurrent.CyclicBarrier(2);
			List<Callable<java.util.Optional<RecapGenerationCoordinator.Claim>>> work = List.of(
				() -> { barrier.await(); return coordinator.claim(NOW); },
				() -> { barrier.await(); return coordinator.claim(NOW); });
			var results = pool.invokeAll(work);
			assertThat(java.util.stream.Stream.of(results.get(0).get(), results.get(1).get()).flatMap(java.util.Optional::stream).toList()).hasSize(1);
		}
		var actual = generations.findById(reserved.id()).orElseThrow();
		assertThat(actual.preparationAttemptCount()).isOne(); assertThat(actual.attemptCount()).isOne();
	}

	@Test void boundedPreparationRetriesDoNotStarveTheNextAccount() {
		var first = coordinator.reserveScheduled(account, RecapKind.WEEKLY, WEEK, NOW);
		for (int i = 0; i < 3; i++) {
			var claim = coordinator.claimPreparation(NOW.plusSeconds(i * 600L)).orElseThrow();
			coordinator.failPreparation(claim, true, NOW.plusSeconds(i * 600L));
		}
		assertThat(coordinator.claimPreparation(NOW.plusSeconds(3600))).isEmpty();
		assertThat(generations.findById(first.id()).orElseThrow().preparationAttemptCount()).isEqualTo(3);
		assertThat(generations.findById(first.id()).orElseThrow().nextAttemptAt()).isNull();
		var second = coordinator.reserveRegeneration(UUID.randomUUID(), account, RecapKind.MONTHLY, MONTH, NOW);
		assertThat(coordinator.claimPreparation(NOW.plusSeconds(3601)).orElseThrow().id()).isEqualTo(second.id());
	}

	@Test void delayedOlderSuccessAndConflictingRedeliveryCannotReplaceTheNewestResult() {
		var old = frozenWeekly("old"); var oldClaim = coordinator.claim(NOW).orElseThrow();
		var newer = frozenWeekly("new"); var newClaim = coordinator.claim(NOW.plusSeconds(1)).orElseThrow();
		coordinator.succeed(newClaim, "{\"result\":2}", "{\"qa\":2}", NOW.plusSeconds(2));
		coordinator.succeed(oldClaim, "{\"result\":1}", "{\"qa\":1}", NOW.plusSeconds(3));
		assertThat(generations.findById(newer.id()).orElseThrow().currentVersion()).isTrue();
		assertThat(generations.findById(old.id()).orElseThrow().currentVersion()).isFalse();
		assertThat(generations.findById(old.id()).orElseThrow().viewJson()).isEqualTo("{\"result\":1}");
		coordinator.succeed(newClaim, "{\"result\":2}", "{\"qa\":2}", NOW.plusSeconds(4));
		assertThatThrownBy(() -> coordinator.succeed(newClaim, "{\"result\":9}", "{}", NOW.plusSeconds(5))).isInstanceOf(IllegalStateException.class);
		assertThat(generations.findById(newer.id()).orElseThrow().viewJson()).isEqualTo("{\"result\":2}");
		assertThatThrownBy(() -> jdbc.update("update recap_generation set view_json='{}' where id=?", newer.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("update recap_generation set state='FAILED' where id=?", newer.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
		var first = query();
		jdbc.update("update student set nickname='changed' where id=?", student);
		context.close(); open();
		assertThat(query()).isEqualTo(first);
		var pending = coordinator.reserveRegeneration(UUID.randomUUID(), account, RecapKind.WEEKLY, WEEK, NOW);
		assertThat(query()).isEqualTo(first);
		coordinator.failPreparation(coordinator.claimPreparation(NOW).orElseThrow(), false, NOW);
		assertThat(query()).isEqualTo(first); assertThat(generations.findById(pending.id()).orElseThrow().state()).isEqualTo(RecapGenerationState.FAILED);
	}

	@Test void concurrentCompletionsAndMonthlyIneligibilityAreMonotonicInBothDirections() throws Exception {
		var old = frozenWeekly("old"); var c1 = coordinator.claim(NOW).orElseThrow();
		var newer = frozenWeekly("new"); var c2 = coordinator.claim(NOW.plusSeconds(1)).orElseThrow();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var a = pool.submit(() -> coordinator.succeed(c1, "{}", "{}", NOW.plusSeconds(2)));
			var b = pool.submit(() -> coordinator.succeed(c2, "{}", "{}", NOW.plusSeconds(2)));
			a.get(); b.get();
		}
		assertThat(generations.findById(newer.id()).orElseThrow().currentVersion()).isTrue();
		assertThat(generations.findById(old.id()).orElseThrow().currentVersion()).isFalse();
		var m1 = coordinator.reserveRegeneration(UUID.randomUUID(), account, RecapKind.MONTHLY, MONTH, NOW);
		var p1 = coordinator.claimPreparation(NOW).orElseThrow(); coordinator.prepared(p1, snapshot(p1, "sha256:m1", "{}", 3), NOW);
		var generationClaim = coordinator.claim(NOW).orElseThrow();
		var m2 = coordinator.reserveRegeneration(UUID.randomUUID(), account, RecapKind.MONTHLY, MONTH, NOW);
		var p2 = coordinator.claimPreparation(NOW).orElseThrow(); coordinator.prepared(p2, snapshot(p2, "sha256:m2", "{}", 0), NOW);
		coordinator.succeed(generationClaim, "{}", "{}", NOW);
		assertThat(generations.findById(m2.id()).orElseThrow().state()).isEqualTo(RecapGenerationState.NOT_ELIGIBLE);
		assertThat(generations.findById(m2.id()).orElseThrow().currentVersion()).isTrue();
		assertThat(generations.findById(m1.id()).orElseThrow().currentVersion()).isFalse();
		var m3 = coordinator.reserveRegeneration(UUID.randomUUID(), account, RecapKind.MONTHLY, MONTH, NOW);
		var p3 = coordinator.claimPreparation(NOW).orElseThrow(); coordinator.prepared(p3, snapshot(p3, "sha256:m3", "{}", 3), NOW);
		coordinator.succeed(coordinator.claim(NOW).orElseThrow(), "{}", "{}", NOW);
		assertThat(generations.findById(m3.id()).orElseThrow().currentVersion()).isTrue();
		assertThat(generations.findById(m2.id()).orElseThrow().currentVersion()).isFalse();
	}

	@Test void isolatedCommandContextNeverStartsFixtureOrSchedulerWritersEvenWithDemoResetSettings() {
		assertThat(context).isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
		assertThat(context.getBeansOfType(com.crabit.backend.demo.DemoFixtureInitializer.class)).isEmpty();
		assertThat(context.getBeansOfType(com.crabit.backend.e2e.SeedFixtureInitializer.class)).isEmpty();
		assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
		assertThat(context.getBeansOfType(RecapGenerationJob.class)).isEmpty(); assertThat(context.getBeansOfType(RecapPythonClient.class)).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from student", Long.class)).isOne();
	}
	private RecapGeneration frozenWeekly(String marker) {
		var g = coordinator.reserveRegeneration(UUID.randomUUID(), account, RecapKind.WEEKLY, WEEK, NOW);
		var p = coordinator.claimPreparation(NOW).orElseThrow(); coordinator.prepared(p, snapshot(p, "sha256:" + marker, "{\"frozen\":\"" + marker + "\"}", 0), NOW); return g;
	}
	private RecapSnapshotService.Snapshot snapshot(RecapGenerationCoordinator.PreparationClaim p, String digest, String request, long count) {
		return new RecapSnapshotService.Snapshot(p.id(), p.studentId(), p.academyId(), digest, request, count);
	}
	private RecapQueryService.Response query() {
		return new RecapQueryService(context.getBean(CardBalanceAccountRepository.class), generations, mock(SharedCardQueryRepository.class), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC))
				.weekly(student, academy, account, WEEK.start().toString());
	}
}
