package com.crabit.backend.recommendation;

import static com.crabit.backend.e2e.SeedFixtureCatalog.BLOCKED_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FIXTURE_TIME;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_STUDENT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.e2e.SeedFixtureCatalog;
import com.crabit.backend.e2e.SeedFixtureService;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

@SpringBootTest(
		properties = {
			"spring.main.banner-mode=off",
			"logging.level.root=warn",
			"crabit.recommendation.handoff.enabled=true",
			"crabit.recommendation.handoff.trigger-credential=trigger-secret",
			"crabit.recommendation.handoff.receiver-credential=receiver-secret"
		})
@AutoConfigureMockMvc
@ActiveProfiles("e2e")
class RecommendationHandoffPostgresIT {

	private static final String PATH = "/internal/v1/recommendation-handoffs";
	private static final UUID HANDOFF_ID = id("00000000-0000-0000-0000-000000009001");
	private static final UUID FRIEND_ACCOUNT_ID = id("00000000-0000-0000-0000-000000009301");
	private static final UUID NONFRIEND_ACCOUNT_ID = id("00000000-0000-0000-0000-000000009302");
	private static final UUID BLOCKED_ACCOUNT_ID = id("00000000-0000-0000-0000-000000009303");
	private static final UUID OTHER_ACADEMY_ACCOUNT_ID = id("00000000-0000-0000-0000-000000009304");
	private static final UUID CLOSED_ACCOUNT_ID = id("00000000-0000-0000-0000-000000009305");
	private static final UUID FRIEND_VISIBLE_WISH_ID = id("00000000-0000-0000-0000-000000009401");
	private static final UUID NONFRIEND_VISIBLE_WISH_ID =
			id("00000000-0000-0000-0000-000000009402");
	private static final UUID NONFRIEND_FOLLOWERS_WISH_ID =
			id("00000000-0000-0000-0000-000000009403");
	private static final UUID BLOCKED_WISH_ID = id("00000000-0000-0000-0000-000000009404");
	private static final UUID OTHER_ACADEMY_WISH_ID = id("00000000-0000-0000-0000-000000009405");
	private static final UUID PRIVATE_WISH_ID = id("00000000-0000-0000-0000-000000009406");
	private static final UUID DELETED_WISH_ID = id("00000000-0000-0000-0000-000000009407");
	private static final UUID ABANDONED_WISH_ID = id("00000000-0000-0000-0000-000000009408");
	private static final UUID CLOSED_ACCOUNT_WISH_ID = id("00000000-0000-0000-0000-000000009409");

	private static final PostgreSQLContainer DATABASE = database();
	private static final TestReceiver RECEIVER = TestReceiver.start();

	@Autowired private MockMvc mockMvc;

	@Autowired private JdbcTemplate jdbc;

	@Autowired private SeedFixtureService fixtures;

	@Autowired private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void dynamicProperties(DynamicPropertyRegistry properties) {
		String separator = DATABASE.getJdbcUrl().contains("?") ? "&" : "?";
		properties.add(
				"spring.datasource.url",
				() ->
						DATABASE.getJdbcUrl()
								+ separator
								+ "ApplicationName=recommendation-handoff-it");
		properties.add("spring.datasource.username", DATABASE::getUsername);
		properties.add("spring.datasource.password", DATABASE::getPassword);
		properties.add("crabit.recommendation.handoff.receiver-url", RECEIVER::url);
	}

	@BeforeEach
	void reset() {
		fixtures.resetAndInitialize();
		RECEIVER.reset();
	}

	@AfterAll
	static void stopReceiver() {
		RECEIVER.stop();
	}

	@Test
	void enabledEndpointAppliesTheCompleteVisibilityMatrixAfterClosingItsSnapshotTransaction()
			throws Exception {
		insertVisibilityMatrix();
		jdbc.update(
				"UPDATE wish SET start_date = ?, target_date = ? WHERE id = ?",
				java.sql.Date.valueOf("2026-09-01"),
				java.sql.Date.valueOf("2027-02-28"),
				LAPTOP_WISH_ID);
		jdbc.update(
				"UPDATE wish SET start_date = ?, target_date = ? WHERE id = ?",
				java.sql.Date.valueOf("2026-10-01"),
				java.sql.Date.valueOf("2027-03-31"),
				FRIEND_VISIBLE_WISH_ID);
		AtomicInteger activeSnapshotTransactions = new AtomicInteger(-1);
		RECEIVER.beforeResponse(
				() ->
						activeSnapshotTransactions.set(
								jdbc.queryForObject(
										"""
										SELECT count(*)
										FROM pg_stat_activity
										WHERE application_name = 'recommendation-handoff-it'
										  AND pid <> pg_backend_pid()
										  AND xact_start IS NOT NULL
										""",
										Integer.class)));

		performHandoff(OWNER_ACCOUNT_ID).andExpect(status().isNoContent());

		assertThat(RECEIVER.requestCount()).isOne();
		assertThat(RECEIVER.authorization()).isEqualTo("Bearer receiver-secret");
		assertThat(RECEIVER.idempotencyKey()).isEqualTo(HANDOFF_ID.toString());
		assertThat(RECEIVER.failure()).isNull();
		assertThat(activeSnapshotTransactions).hasValue(0);
		JsonNode payload = objectMapper.readTree(RECEIVER.body());
		assertThat(payload.get("schema_version").intValue()).isEqualTo(3);
		assertThat(payload.get("synthetic_feature_version").intValue()).isEqualTo(1);
		JsonNode viewerLaptop =
				StreamSupport.stream(payload.get("viewer_wishes").spliterator(), false)
						.map(item -> item.get("wish"))
						.filter(
								wish ->
										LAPTOP_WISH_ID
												.toString()
												.equals(wish.get("wish_id").textValue()))
						.findFirst()
						.orElseThrow();
		assertThat(viewerLaptop.get("start_date").textValue()).isEqualTo("2026-09-01");
		assertThat(viewerLaptop.get("target_date").textValue()).isEqualTo("2027-02-28");
		List<String> candidateWishIds =
				StreamSupport.stream(payload.get("candidates").spliterator(), false)
						.map(candidate -> candidate.at("/wish/wish_id").textValue())
						.toList();
		assertThat(candidateWishIds)
				.containsExactlyInAnyOrder(
						FRIEND_VISIBLE_WISH_ID.toString(), NONFRIEND_VISIBLE_WISH_ID.toString());
		JsonNode friendCandidate =
				StreamSupport.stream(payload.get("candidates").spliterator(), false)
						.map(candidate -> candidate.get("wish"))
						.filter(
								wish ->
										FRIEND_VISIBLE_WISH_ID
												.toString()
												.equals(wish.get("wish_id").textValue()))
						.findFirst()
						.orElseThrow();
		assertThat(friendCandidate.get("start_date").textValue()).isEqualTo("2026-10-01");
		assertThat(friendCandidate.get("target_date").textValue()).isEqualTo("2027-03-31");
		JsonNode nullCandidate =
				StreamSupport.stream(payload.get("candidates").spliterator(), false)
						.map(candidate -> candidate.get("wish"))
						.filter(
								wish ->
										NONFRIEND_VISIBLE_WISH_ID
												.toString()
												.equals(wish.get("wish_id").textValue()))
						.findFirst()
						.orElseThrow();
		assertThat(nullCandidate.get("start_date").isNull()).isTrue();
	}

	@Test
	void departedViewerIsDeniedWithoutAnOutboundAttempt() throws Exception {
		jdbc.update(
				"""
				UPDATE academy_membership
				SET left_at = ?
				WHERE student_id = ? AND academy_id = ?
				""",
				timestamp(FIXTURE_TIME.plusSeconds(1)),
				SeedFixtureCatalog.OWNER_ID,
				PRIMARY_ACADEMY_ID);

		performHandoff(OWNER_ACCOUNT_ID)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));

		assertThat(RECEIVER.requestCount()).isZero();
	}

	@Test
	void incompleteSnapshotDataProducesNoOutboundAttempt() throws Exception {
		jdbc.update("UPDATE academy SET name = '   ' WHERE id = ?", PRIMARY_ACADEMY_ID);

		performHandoff(OWNER_ACCOUNT_ID)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error.code").value("RECOMMENDATION_DATA_INCOMPLETE"));

		assertThat(RECEIVER.requestCount()).isZero();
	}

	@Test
	void handlerEquivalentPathsRequireTheDedicatedTriggerCredentialWithContextPaths()
			throws Exception {
		for (String[] target :
				new String[][] {
					{PATH + ";x=y", ""},
					{"/crabit" + PATH, "/crabit"},
					{"/crabit" + PATH + ";jsessionid=abc", "/crabit"}
				}) {
			for (String authorization :
					new String[] {
						null, "Bearer receiver-secret", "Bearer " + SeedFixtureCatalog.OWNER_TOKEN
					}) {
				performAuthenticationProbe(target[0], target[1], authorization)
						.andExpect(status().isUnauthorized())
						.andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
			}

			performAuthenticationProbe(target[0], target[1], "Bearer trigger-secret")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
		}

		assertThat(RECEIVER.requestCount()).isZero();
	}

	@Test
	void aggregatesEventTypesZeroAbandonmentCorrectionsAndDeletedHistory() throws Exception {
		UUID a = new UUID(0, 100001),
				b = new UUID(0, 100002),
				zero = new UUID(0, 100003),
				positive = new UUID(0, 100004);
		insertWish(a, OWNER_ACCOUNT_ID, PRIMARY_ACADEMY_ID, "IN_PROGRESS", "PRIVATE", null, null);
		insertWish(b, OWNER_ACCOUNT_ID, PRIMARY_ACADEMY_ID, "IN_PROGRESS", "PRIVATE", null, null);
		Instant at = Instant.parse("2026-09-01T01:00:00Z");
		insertWish(
				zero,
				OWNER_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID,
				"ABANDONED",
				"PRIVATE",
				at.plusSeconds(10),
				at);
		jdbc.update("UPDATE wish SET abandonment_amount=0 WHERE id=?", zero);
		insertWish(
				positive, OWNER_ACCOUNT_ID, PRIMARY_ACADEMY_ID, "ABANDONED", "PRIVATE", null, at);
		UUID deposit = event("WISH_DEPOSIT", at, null, new UUID[] {a}, new long[] {10000});
		event("WISH_TRANSFER", at, null, new UUID[] {a, b}, new long[] {-3000, 3000});
		event("WISH_WITHDRAWAL", at, null, new UUID[] {b}, new long[] {-2000});
		event("WISH_COMPLETION_RETURN", at, null, new UUID[] {a}, new long[] {-5000});
		event("WISH_ABANDONMENT_RETURN", at, null, new UUID[] {positive}, new long[] {-100});
		event("WISH_WITHDRAWAL", at.plusSeconds(1), deposit, new UUID[] {a}, new long[] {-500});
		jdbc.update(
				"UPDATE wish SET deleted_at=?,deleted_purpose_snapshot=purpose,wish_amount=0 WHERE"
						+ " id=?",
				timestamp(at.plusSeconds(2)),
				a);
		periodHandoff("2026-09-01", "2026-09-03");
		JsonNode payload = objectMapper.readTree(RECEIVER.body());
		var totals = payload.at("/viewer_period_savings/totals");
		assertThat(totals.at("/deposits/count").longValue()).isEqualTo(1);
		assertThat(totals.at("/deposits/amount").longValue()).isEqualTo(10000);
		assertThat(totals.at("/transfers/count").longValue()).isEqualTo(1);
		assertThat(totals.at("/transfers/amount").longValue()).isEqualTo(3000);
		assertThat(totals.at("/withdrawals/amount").longValue()).isEqualTo(2000);
		assertThat(totals.at("/completion_returns/amount").longValue()).isEqualTo(5000);
		assertThat(totals.get("abandonment_count").longValue()).isEqualTo(2);
		assertThat(totals.at("/corrections/WISH_WITHDRAWAL/negative_amount").longValue())
				.isEqualTo(500);
		assertThat(payload.at("/viewer_period_savings/daily/0/totals")).isEqualTo(totals);
		java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/recommendation"));
		java.nio.file.Files.write(
				java.nio.file.Path.of("build/recommendation/actual-v3.json"), RECEIVER.body());
	}

	@Test
	void includesOldRepresentativeAndAggregatesBeyondHundredWithoutBoundaryOverlap()
			throws Exception {
		Instant at = Instant.parse("2026-08-31T15:00:00Z");
		for (int i = 0; i < 105; i++) {
			UUID wish = new UUID(0, 200000 + i);
			insertWish(
					wish,
					OWNER_ACCOUNT_ID,
					PRIMARY_ACADEMY_ID,
					"IN_PROGRESS",
					"PRIVATE",
					null,
					null);
			jdbc.update(
					"UPDATE wish SET created_at=?,updated_at=? WHERE id=?",
					timestamp(at),
					timestamp(at),
					wish);
			event("WISH_WITHDRAWAL", at, null, new UUID[] {wish}, new long[] {1 * -1});
		}
		periodHandoff("2026-09-01", "2026-09-02");
		var payload = objectMapper.readTree(RECEIVER.body());
		assertThat(payload.at("/viewer_period_savings/totals/withdrawals/count").longValue())
				.isEqualTo(105);
		assertThat(payload.get("viewer_wishes").size()).isEqualTo(100);
		assertThat(payload.get("viewer_wishes_truncated").booleanValue()).isTrue();
		assertThat(payload.at("/viewer_wishes/0/wish/is_representative").booleanValue()).isTrue();
		periodHandoff("2026-08-31", "2026-09-01");
		assertThat(
						objectMapper
								.readTree(RECEIVER.body())
								.at("/viewer_period_savings/totals/withdrawals/count")
								.longValue())
				.isZero();
	}

	@Test
	void suppliedCategoriesRequireOwnGroundingCurrentTitlesAndCurrentVisibility() throws Exception {
		insertVisibilityMatrix();
		var title =
				jdbc.queryForObject(
						"SELECT purpose FROM wish WHERE id=?", String.class, LAPTOP_WISH_ID);
		String ownHash =
				java.util.HexFormat.of()
						.formatHex(
								java.security.MessageDigest.getInstance("SHA-256")
										.digest(title.getBytes(StandardCharsets.UTF_8)));
		String friendTitle =
				jdbc.queryForObject(
						"SELECT purpose FROM wish WHERE id=?",
						String.class,
						FRIEND_VISIBLE_WISH_ID);
		String friendHash =
				java.util.HexFormat.of()
						.formatHex(
								java.security.MessageDigest.getInstance("SHA-256")
										.digest(friendTitle.getBytes(StandardCharsets.UTF_8)));
		String context =
				"\"interest_context\":{\"source\":\"python\",\"taxonomy_version\":\"v1\",\"classifier_version\":\"v1\",\"classified_at\":\""
						+ Instant.now().minusSeconds(60)
						+ "\",\"card_balance_account_id\":\""
						+ OWNER_ACCOUNT_ID
						+ "\",\"viewer_interest_category_ids\":[\"books\"],\"wish_classifications\":[{\"wish_id\":\""
						+ LAPTOP_WISH_ID
						+ "\",\"category_ids\":[\"books\"],\"title_sha256\":\""
						+ ownHash
						+ "\"},{\"wish_id\":\""
						+ FRIEND_VISIBLE_WISH_ID
						+ "\",\"category_ids\":[\"books\"],\"title_sha256\":\""
						+ friendHash
						+ "\"}]}";
		mockMvc.perform(
						post(PATH)
								.header(HttpHeaders.AUTHORIZATION, "Bearer trigger-secret")
								.contentType(MediaType.APPLICATION_JSON)
								.content(
										"{\"handoff_id\":\""
												+ HANDOFF_ID
												+ "\",\"card_balance_account_id\":\""
												+ OWNER_ACCOUNT_ID
												+ "\","
												+ context
												+ "}"))
				.andExpect(status().isNoContent());
		var payload = objectMapper.readTree(RECEIVER.body());
		assertThat(payload.at("/interest_evidence/status").textValue()).isEqualTo("used");
		assertThat(payload.at("/candidate_selection/selected_counts/interest").intValue())
				.isEqualTo(1);
		jdbc.update("UPDATE wish SET purpose='changed title' WHERE id=?", LAPTOP_WISH_ID);
		mockMvc.perform(
						post(PATH)
								.header(HttpHeaders.AUTHORIZATION, "Bearer trigger-secret")
								.contentType(MediaType.APPLICATION_JSON)
								.content(
										"{\"handoff_id\":\""
												+ HANDOFF_ID
												+ "\",\"card_balance_account_id\":\""
												+ OWNER_ACCOUNT_ID
												+ "\","
												+ context
												+ "}"))
				.andExpect(status().isNoContent());
		assertThat(
						objectMapper
								.readTree(RECEIVER.body())
								.at("/interest_evidence/status")
								.textValue())
				.isEqualTo("no_usable_classifications");
	}

	@Test
	void doesNotMixAnotherClosedAccountOrAnotherAcademyForTheSameStudent() throws Exception {
		UUID closed = new UUID(0, 310000), otherAcademy = new UUID(0, 310001);
		insertAccount(
				closed,
				SeedFixtureCatalog.OWNER_ID,
				PRIMARY_ACADEMY_ID,
				Instant.parse("2026-09-02T00:00:00Z"));
		insertAccount(otherAcademy, SeedFixtureCatalog.OWNER_ID, OTHER_ACADEMY_ID, null);
		UUID currentWish = new UUID(0, 320000),
				closedWish = new UUID(0, 320001),
				otherWish = new UUID(0, 320002);
		insertWish(
				currentWish,
				OWNER_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID,
				"IN_PROGRESS",
				"PRIVATE",
				null,
				null);
		insertWish(closedWish, closed, PRIMARY_ACADEMY_ID, "IN_PROGRESS", "PRIVATE", null, null);
		insertWish(otherWish, otherAcademy, OTHER_ACADEMY_ID, "IN_PROGRESS", "PRIVATE", null, null);
		Instant at = Instant.parse("2026-09-01T01:00:00Z");
		event("WISH_WITHDRAWAL", at, null, new UUID[] {currentWish}, new long[] {-5});
		for (UUID[] pair : new UUID[][] {{closed, closedWish}, {otherAcademy, otherWish}}) {
			UUID event = UUID.randomUUID();
			jdbc.update(
					"INSERT INTO ledger_event(id,account_id,event_type,account_delta,occurred_at)"
							+ " VALUES(?,?,'WISH_WITHDRAWAL',0,?)",
					event,
					pair[0],
					timestamp(at));
			jdbc.update(
					"INSERT INTO"
						+ " ledger_wish_effect(id,event_id,account_id,wish_id,wish_purpose_snapshot,wish_delta)"
						+ " VALUES(?,?,?,?,?,-999)",
					UUID.randomUUID(),
					event,
					pair[0],
					pair[1],
					"other account");
		}
		periodHandoff("2026-09-01", "2026-09-02");
		assertThat(
						objectMapper
								.readTree(RECEIVER.body())
								.at("/viewer_period_savings/totals/withdrawals/amount")
								.longValue())
				.isEqualTo(5);
	}

	@Test
	void recordsExplainAnalyzeForActualBoundedProductionQueries() throws Exception {
		insertVisibilityMatrix();
		var ids = new java.util.ArrayList<UUID>();
		for (int i = 0; i < 150; i++) {
			UUID wish = new UUID(0, 400000 + i);
			ids.add(wish);
			insertWishAndCard(
					wish,
					FRIEND_ACCOUNT_ID,
					PRIMARY_ACADEMY_ID,
					"IN_PROGRESS",
					"ACADEMY",
					null,
					null);
			if (i < 30) {
				jdbc.update(
						"UPDATE wish SET state='COMPLETED',wish_amount=0,completed_at=? WHERE id=?",
						timestamp(Instant.now().minusSeconds(86400)),
						wish);
				jdbc.update("UPDATE shared_card SET kind='COMPLETION' WHERE wish_id=?", wish);
			}
		}
		UUID own = new UUID(0, 500000);
		insertWish(own, OWNER_ACCOUNT_ID, PRIMARY_ACADEMY_ID, "IN_PROGRESS", "PRIVATE", null, null);
		Instant at = Instant.parse("2026-09-01T00:00:00Z");
		for (int i = 0; i < 150; i++)
			event("WISH_WITHDRAWAL", at, null, new UUID[] {own}, new long[] {-1});
		jdbc.execute("ANALYZE ledger_event");
		jdbc.execute("ANALYZE ledger_wish_effect");
		jdbc.execute("ANALYZE wish");
		jdbc.execute("ANALYZE shared_card");
		var recorded = new RecordingJdbc(jdbc.getDataSource());
		var repository =
				new JdbcRecommendationSnapshotRepository(
						recorded, new com.crabit.backend.wish.SharedCardQueryRepository(recorded));
		var plans = new java.util.LinkedHashMap<String, Object>();
		assertThat(repository.findCandidates(SeedFixtureCatalog.OWNER_ID, PRIMARY_ACADEMY_ID, 101))
				.hasSize(101);
		plans.put("latest", explain(recorded));
		repository.findCompletedCandidates(
				SeedFixtureCatalog.OWNER_ID,
				PRIMARY_ACADEMY_ID,
				Instant.now().minusSeconds(2592000),
				Instant.now(),
				101);
		plans.put("recently_completed", explain(recorded));
		assertThat(
						repository.findInterestCandidates(
								SeedFixtureCatalog.OWNER_ID, PRIMARY_ACADEMY_ID, ids, 101))
				.hasSize(101);
		plans.put("interest", explain(recorded));
		repository.periodSavings(OWNER_ACCOUNT_ID, at, at.plusSeconds(86400));
		plans.put("period", explain(recorded));
		java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/recommendation"));
		java.nio.file.Files.write(
				java.nio.file.Path.of("build/recommendation/query-plans.json"),
				objectMapper.writeValueAsBytes(plans));
	}

	private Object explain(RecordingJdbc recorded) {
		String raw =
				jdbc.queryForObject(
						"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + recorded.sql,
						String.class,
						recorded.arguments);
		return objectMapper.readTree(raw);
	}

	private static final class RecordingJdbc extends org.springframework.jdbc.core.JdbcTemplate {
		String sql;
		Object[] arguments;

		RecordingJdbc(javax.sql.DataSource source) {
			super(source);
		}

		@Override
		public <T> java.util.List<T> query(
				String sql,
				org.springframework.jdbc.core.RowMapper<T> mapper,
				Object... arguments) {
			this.sql = sql;
			this.arguments = arguments;
			return super.query(sql, mapper, arguments);
		}
	}

	private void periodHandoff(String start, String end) throws Exception {
		mockMvc.perform(
						post(PATH)
								.header(HttpHeaders.AUTHORIZATION, "Bearer trigger-secret")
								.contentType(MediaType.APPLICATION_JSON)
								.content(
										"{\"handoff_id\":\""
												+ HANDOFF_ID
												+ "\",\"card_balance_account_id\":\""
												+ OWNER_ACCOUNT_ID
												+ "\",\"period\":{\"start_date\":\""
												+ start
												+ "\",\"end_date_exclusive\":\""
												+ end
												+ "\"}}"))
				.andExpect(status().isNoContent());
	}

	private UUID event(String type, Instant at, UUID correction, UUID[] wishes, long[] deltas) {
		UUID id = UUID.randomUUID(), proof = null;
		if (type.equals("WISH_DEPOSIT")) {
			proof = UUID.randomUUID();
			int inserted =
					jdbc.update(
							"""
							INSERT INTO balance_observation(id,account_id,status,lookup_method,actual_card_balance,first_successful,previous_successful_observation_id,previous_successful_balance,observed_at)
							SELECT ?,account_id,'SUCCEEDED','PRE_DEPOSIT',actual_card_balance,NULL,id,actual_card_balance,? FROM balance_observation WHERE account_id=? AND status='SUCCEEDED' ORDER BY observed_at DESC LIMIT 1
							""",
							proof,
							timestamp(at),
							OWNER_ACCOUNT_ID);
			if (inserted == 0)
				jdbc.update(
						"INSERT INTO"
							+ " balance_observation(id,account_id,status,lookup_method,actual_card_balance,first_successful,previous_successful_balance,observed_at)"
							+ " VALUES(?,?,'SUCCEEDED','PRE_DEPOSIT',0,true,0,?)",
						proof,
						OWNER_ACCOUNT_ID,
						timestamp(at));
		}
		jdbc.update(
				"INSERT INTO"
					+ " ledger_event(id,account_id,event_type,account_delta,occurred_at,correction_of_event_id,deposit_balance_observation_id,deposit_observation_status,deposit_observation_lookup_method)"
					+ " VALUES(?,?,?,0,?,?,?,?,?)",
				id,
				OWNER_ACCOUNT_ID,
				type,
				timestamp(at),
				correction,
				proof,
				proof == null ? null : "SUCCEEDED",
				proof == null ? null : "PRE_DEPOSIT");
		for (int i = 0; i < wishes.length; i++)
			jdbc.update(
					"INSERT INTO"
						+ " ledger_wish_effect(id,event_id,account_id,wish_id,wish_purpose_snapshot,wish_delta)"
						+ " VALUES(?,?,?,?,?,?)",
					UUID.randomUUID(),
					id,
					OWNER_ACCOUNT_ID,
					wishes[i],
					"historical wish",
					deltas[i]);
		return id;
	}

	private org.springframework.test.web.servlet.ResultActions performHandoff(UUID accountId)
			throws Exception {
		return mockMvc.perform(
				post(PATH)
						.header(HttpHeaders.AUTHORIZATION, "Bearer trigger-secret")
						.contentType(MediaType.APPLICATION_JSON)
						.content(
								"""
								{"handoff_id":"%s","card_balance_account_id":"%s"}
								"""
										.formatted(HANDOFF_ID, accountId)));
	}

	private org.springframework.test.web.servlet.ResultActions performAuthenticationProbe(
			String requestUri, String contextPath, String authorization) throws Exception {
		MockHttpServletRequestBuilder request =
				post(requestUri).contentType(MediaType.APPLICATION_JSON).content("{}");
		if (!contextPath.isEmpty()) {
			request.contextPath(contextPath);
		}
		if (authorization != null) {
			request.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return mockMvc.perform(request);
	}

	private void insertVisibilityMatrix() {
		jdbc.update(
				"INSERT INTO student_follow(id,academy_id,source_id,target_id,started_at)"
						+ " VALUES(?,?,?,?,now())",
				UUID.randomUUID(),
				PRIMARY_ACADEMY_ID,
				SeedFixtureCatalog.OWNER_ID,
				FRIEND_ID);
		insertAccount(FRIEND_ACCOUNT_ID, FRIEND_ID, PRIMARY_ACADEMY_ID, null);
		insertAccount(NONFRIEND_ACCOUNT_ID, NONFRIEND_ID, PRIMARY_ACADEMY_ID, null);
		insertAccount(BLOCKED_ACCOUNT_ID, BLOCKED_ID, PRIMARY_ACADEMY_ID, null);
		insertAccount(OTHER_ACADEMY_ACCOUNT_ID, OTHER_ACADEMY_STUDENT_ID, OTHER_ACADEMY_ID, null);
		insertAccount(
				CLOSED_ACCOUNT_ID, FRIEND_ID, PRIMARY_ACADEMY_ID, FIXTURE_TIME.plusSeconds(30));

		insertWishAndCard(
				FRIEND_VISIBLE_WISH_ID,
				FRIEND_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID,
				"IN_PROGRESS",
				"FOLLOWERS",
				null,
				null);
		insertWishAndCard(
				NONFRIEND_VISIBLE_WISH_ID,
				NONFRIEND_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID,
				"IN_PROGRESS",
				"ACADEMY",
				null,
				null);
		insertWishAndCard(
				NONFRIEND_FOLLOWERS_WISH_ID,
				NONFRIEND_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID,
				"IN_PROGRESS",
				"FOLLOWERS",
				null,
				null);
		insertWishAndCard(
				BLOCKED_WISH_ID,
				BLOCKED_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID,
				"IN_PROGRESS",
				"ACADEMY",
				null,
				null);
		insertWishAndCard(
				OTHER_ACADEMY_WISH_ID,
				OTHER_ACADEMY_ACCOUNT_ID,
				OTHER_ACADEMY_ID,
				"IN_PROGRESS",
				"ACADEMY",
				null,
				null);
		insertWish(
				PRIVATE_WISH_ID,
				FRIEND_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID,
				"IN_PROGRESS",
				"PRIVATE",
				null,
				null);
		insertWishAndCard(
				DELETED_WISH_ID,
				FRIEND_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID,
				"IN_PROGRESS",
				"ACADEMY",
				FIXTURE_TIME.plusSeconds(20),
				null);
		insertWishAndCard(
				ABANDONED_WISH_ID,
				FRIEND_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID,
				"ABANDONED",
				"ACADEMY",
				null,
				FIXTURE_TIME.plusSeconds(20));
		insertWishAndCard(
				CLOSED_ACCOUNT_WISH_ID,
				CLOSED_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID,
				"IN_PROGRESS",
				"ACADEMY",
				null,
				null);
	}

	private void insertAccount(UUID accountId, UUID studentId, UUID academyId, Instant closedAt) {
		jdbc.update(
				"""
				INSERT INTO card_balance_account
					(id, student_id, academy_id, opened_at, closed_at,
					 balance_lookup_version, version)
				VALUES (?, ?, ?, ?, ?, 0, 0)
				""",
				accountId,
				studentId,
				academyId,
				timestamp(FIXTURE_TIME),
				closedAt == null ? null : timestamp(closedAt));
	}

	private void insertWishAndCard(
			UUID wishId,
			UUID accountId,
			UUID academyId,
			String state,
			String visibility,
			Instant deletedAt,
			Instant abandonedAt) {
		insertWish(wishId, accountId, academyId, state, visibility, deletedAt, abandonedAt);
		jdbc.update(
				"""
				INSERT INTO shared_card (id, wish_id, kind, visibility, updated_at)
				VALUES (?, ?, 'PROGRESS', ?, ?)
				""",
				cardId(wishId),
				wishId,
				visibility,
				timestamp(FIXTURE_TIME));
	}

	private void insertWish(
			UUID wishId,
			UUID accountId,
			UUID academyId,
			String state,
			String visibility,
			Instant deletedAt,
			Instant abandonedAt) {
		long wishAmount = deletedAt != null || abandonedAt != null ? 0 : 100;
		Long abandonmentAmount = abandonedAt == null ? null : 100L;
		jdbc.update(
				"""
				INSERT INTO wish
					(id, account_id, academy_id, purpose, target_amount, wish_amount,
					 abandonment_amount, state,
					 visibility, created_at, updated_at, start_date, target_date, completed_at, abandoned_at,
					 deleted_at, deleted_purpose_snapshot, version)
				VALUES (?, ?, ?, ?, 1000, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, 0)
				""",
				wishId,
				accountId,
				academyId,
				"후보 " + wishId,
				wishAmount,
				abandonmentAmount,
				state,
				visibility,
				timestamp(FIXTURE_TIME),
				timestamp(FIXTURE_TIME),
				abandonedAt == null ? null : timestamp(abandonedAt),
				deletedAt == null ? null : timestamp(deletedAt),
				deletedAt == null ? null : "삭제 후보 " + wishId);
	}

	private static UUID cardId(UUID wishId) {
		return new UUID(wishId.getMostSignificantBits(), wishId.getLeastSignificantBits() + 0x400L);
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}

	private static PostgreSQLContainer database() {
		PostgreSQLContainer database =
				new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
		database.start();
		return database;
	}

	private static UUID id(String value) {
		return UUID.fromString(value);
	}

	private static final class TestReceiver {

		private final HttpServer server;
		private final AtomicInteger requestCount = new AtomicInteger();
		private final AtomicReference<byte[]> body = new AtomicReference<>();
		private final AtomicReference<String> authorization = new AtomicReference<>();
		private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
		private final AtomicReference<Runnable> beforeResponse = new AtomicReference<>(() -> {});
		private final AtomicReference<Throwable> failure = new AtomicReference<>();

		private TestReceiver(HttpServer server) {
			this.server = server;
		}

		static TestReceiver start() {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				TestReceiver receiver = new TestReceiver(server);
				server.createContext(
						"/receiver",
						exchange -> {
							receiver.requestCount.incrementAndGet();
							receiver.body.set(exchange.getRequestBody().readAllBytes());
							receiver.authorization.set(
									exchange.getRequestHeaders()
											.getFirst(HttpHeaders.AUTHORIZATION));
							receiver.idempotencyKey.set(
									exchange.getRequestHeaders().getFirst("Idempotency-Key"));
							try {
								receiver.beforeResponse.get().run();
								byte[] ack =
										("{\"schema_version\":3,\"handoff_id\":\""
														+ HANDOFF_ID
														+ "\",\"accepted\":true}")
												.getBytes(StandardCharsets.UTF_8);
								exchange.getResponseHeaders()
										.set("Content-Type", "application/json");
								exchange.sendResponseHeaders(200, ack.length);
								exchange.getResponseBody().write(ack);
							} catch (Throwable throwable) {
								receiver.failure.set(throwable);
								exchange.sendResponseHeaders(500, -1);
							} finally {
								exchange.close();
							}
						});
				server.start();
				return receiver;
			} catch (IOException exception) {
				throw new ExceptionInInitializerError(exception);
			}
		}

		void reset() {
			requestCount.set(0);
			body.set(null);
			authorization.set(null);
			idempotencyKey.set(null);
			beforeResponse.set(() -> {});
			failure.set(null);
		}

		void beforeResponse(Runnable probe) {
			beforeResponse.set(probe);
		}

		String url() {
			return "http://127.0.0.1:" + server.getAddress().getPort() + "/receiver";
		}

		int requestCount() {
			return requestCount.get();
		}

		byte[] body() {
			return body.get();
		}

		String authorization() {
			return authorization.get();
		}

		String idempotencyKey() {
			return idempotencyKey.get();
		}

		Throwable failure() {
			return failure.get();
		}

		void stop() {
			server.stop(0);
		}
	}
}
