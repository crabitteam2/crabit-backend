package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.e2e.PostgresTestDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class RecapSnapshotServiceTest {
	private static final JdbcTemplate JDBC = PostgresTestDatabase.JDBC;
	private static final ObjectMapper JSON = new ObjectMapper();

	@Test void representativeWishAchievementUsesTheViewerZeroToOneHundredScale() {
		assertThat(RecapSnapshotService.achievementRate(25_000, 100_000)).isEqualTo(25.0);
		assertThat(RecapSnapshotService.achievementRate(-1, 100_000)).isZero();
		assertThat(RecapSnapshotService.achievementRate(150_000, 100_000)).isEqualTo(100.0);
		assertThatThrownBy(() -> RecapSnapshotService.achievementRate(1, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test void fixedPeerCohortUsesRepresentativePercentagesAndExcludesRepresentativeLessPeers() throws Exception {
		UUID academy = UUID.randomUUID();
		UUID viewer = UUID.randomUUID(); UUID viewerAccount = UUID.randomUUID();
		UUID lowerPeer = UUID.randomUUID(); UUID lowerAccount = UUID.randomUUID();
		UUID higherPeer = UUID.randomUUID(); UUID higherAccount = UUID.randomUUID();
		UUID representativeLessPeer = UUID.randomUUID(); UUID representativeLessAccount = UUID.randomUUID();
		JDBC.update("insert into academy(id,name) values (?,?)", academy, "Fixed cohort");
		insertStudent(viewer, viewerAccount, academy, 12);
		insertStudent(lowerPeer, lowerAccount, academy, 11);
		insertStudent(higherPeer, higherAccount, academy, 13);
		insertStudent(representativeLessPeer, representativeLessAccount, academy, 12);
		insertRepresentativeWish(viewerAccount, academy, 10_000, 5_000);
		insertRepresentativeWish(lowerAccount, academy, 10_000, 2_500);
		insertRepresentativeWish(higherAccount, academy, 10_000, 7_500);

		var period = new RecapPeriods.Period(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-09-01"));
		var snapshot = new RecapSnapshotService(JDBC, JSON).build(viewerAccount, RecapKind.MONTHLY, period);
		@SuppressWarnings("unchecked") Map<String, Object> request = JSON.readValue(snapshot.requestJson(), Map.class);
		@SuppressWarnings("unchecked") Map<String, Object> input = (Map<String, Object>) request.get("input");
		@SuppressWarnings("unchecked") Map<String, Object> peers = (Map<String, Object>) input.get("peer_metrics");
		List<Double> achievementRates = ((List<?>) peers.get("achievement_rates")).stream()
				.map(Number.class::cast).map(Number::doubleValue).toList();
		List<Integer> activeWeeks = ((List<?>) peers.get("habit_active_weeks")).stream()
				.map(Number.class::cast).map(Number::intValue).toList();
		@SuppressWarnings("unchecked") Map<String, Object> representativeWish = ((List<Map<String, Object>>) input.get("wishes")).stream()
				.filter(wish -> Boolean.TRUE.equals(wish.get("is_representative"))).findFirst().orElseThrow();

		double viewerRate = RecapSnapshotService.achievementRate(
				((Number) representativeWish.get("saved_amount_at_period_end")).longValue(),
				((Number) representativeWish.get("target_amount")).longValue());
		assertThat(viewerRate).isEqualTo(50.0);
		assertThat(achievementRates).containsExactlyInAnyOrder(25.0, 75.0);
		assertThat(activeWeeks).hasSize(3).containsExactlyInAnyOrder(1, 1, 0);
		assertThat(percentile(viewerRate, achievementRates)).isEqualTo(50);
	}

	private static void insertStudent(UUID student, UUID account, UUID academy, int age) {
		JDBC.update("insert into student(id,nickname,age,age_provenance) values (?,?,?,'PROVIDED')",
				student, "student-" + student, age);
		JDBC.update("insert into academy_membership(id,student_id,academy_id,joined_at) values (?,?,?,?)",
				UUID.randomUUID(), student, academy, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
		JDBC.update("insert into card_balance_account(id,student_id,academy_id,opened_at) values (?,?,?,?)",
				account, student, academy, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
	}

	private static void insertRepresentativeWish(UUID account, UUID academy, long target, long saved) {
		UUID wish = UUID.randomUUID();
		JDBC.update("""
				insert into wish(id,account_id,academy_id,purpose,target_amount,wish_amount,state,visibility,created_at)
				values (?,?,?,?,?,?,'IN_PROGRESS','PRIVATE',?)
				""", wish, account, academy, "Representative", target, saved,
				Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
		UUID observation = UUID.randomUUID();
		JDBC.update("""
				insert into balance_observation(id,account_id,status,lookup_method,actual_card_balance,
				 first_successful,previous_successful_balance,observed_at)
				values (?,?,'SUCCEEDED','PRE_DEPOSIT',0,true,0,?)
				""", observation, account, Timestamp.from(Instant.parse("2026-08-15T00:00:00Z")));
		UUID event = UUID.randomUUID();
		JDBC.update("""
				insert into ledger_event(id,account_id,event_type,account_delta,occurred_at,
				 deposit_balance_observation_id,deposit_observation_status,deposit_observation_lookup_method)
				values (?,?,'WISH_DEPOSIT',?,?,?,'SUCCEEDED','PRE_DEPOSIT')
				""", event, account, -saved, Timestamp.from(Instant.parse("2026-08-15T00:00:00Z")), observation);
		JDBC.update("""
				insert into ledger_wish_effect(id,event_id,account_id,wish_id,wish_purpose_snapshot,wish_delta)
				values (?,?,?,?,?,?)
				""", UUID.randomUUID(), event, account, wish, "Representative", saved);
	}

	private static int percentile(double viewer, List<Double> peers) {
		long lower = peers.stream().filter(value -> value < viewer).count();
		long tied = peers.stream().filter(value -> value == viewer).count();
		return (int) Math.min(99, Math.max(1, Math.round((lower + tied * 0.5) * 100 / peers.size())));
	}
}
