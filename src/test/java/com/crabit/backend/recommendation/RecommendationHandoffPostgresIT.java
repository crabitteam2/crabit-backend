package com.crabit.backend.recommendation;

import static com.crabit.backend.e2e.SeedFixtureCatalog.BLOCKED_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_STUDENT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FIXTURE_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.e2e.SeedFixtureCatalog;
import com.crabit.backend.e2e.SeedFixtureService;
import com.sun.net.httpserver.HttpServer;
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

@SpringBootTest(properties = {
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
	private static final UUID NONFRIEND_VISIBLE_WISH_ID = id("00000000-0000-0000-0000-000000009402");
	private static final UUID NONFRIEND_FRIENDS_WISH_ID = id("00000000-0000-0000-0000-000000009403");
	private static final UUID BLOCKED_WISH_ID = id("00000000-0000-0000-0000-000000009404");
	private static final UUID OTHER_ACADEMY_WISH_ID = id("00000000-0000-0000-0000-000000009405");
	private static final UUID PRIVATE_WISH_ID = id("00000000-0000-0000-0000-000000009406");
	private static final UUID DELETED_WISH_ID = id("00000000-0000-0000-0000-000000009407");
	private static final UUID ABANDONED_WISH_ID = id("00000000-0000-0000-0000-000000009408");
	private static final UUID CLOSED_ACCOUNT_WISH_ID = id("00000000-0000-0000-0000-000000009409");

	private static final PostgreSQLContainer DATABASE = database();
	private static final TestReceiver RECEIVER = TestReceiver.start();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private SeedFixtureService fixtures;

	@Autowired
	private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void dynamicProperties(DynamicPropertyRegistry properties) {
		String separator = DATABASE.getJdbcUrl().contains("?") ? "&" : "?";
		properties.add("spring.datasource.url",
				() -> DATABASE.getJdbcUrl() + separator
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
		AtomicInteger activeSnapshotTransactions = new AtomicInteger(-1);
		RECEIVER.beforeResponse(() -> activeSnapshotTransactions.set(jdbc.queryForObject("""
				SELECT count(*)
				FROM pg_stat_activity
				WHERE application_name = 'recommendation-handoff-it'
				  AND pid <> pg_backend_pid()
				  AND xact_start IS NOT NULL
				""", Integer.class)));

		performHandoff(OWNER_ACCOUNT_ID)
				.andExpect(status().isNoContent());

		assertThat(RECEIVER.requestCount()).isOne();
		assertThat(RECEIVER.authorization()).isEqualTo("Bearer receiver-secret");
		assertThat(RECEIVER.idempotencyKey()).isEqualTo(HANDOFF_ID.toString());
		assertThat(RECEIVER.failure()).isNull();
		assertThat(activeSnapshotTransactions).hasValue(0);
		JsonNode payload = objectMapper.readTree(RECEIVER.body());
		List<String> candidateWishIds = StreamSupport.stream(
				payload.get("candidates").spliterator(), false)
				.map(candidate -> candidate.at("/wish/wish_id").textValue())
				.toList();
		assertThat(candidateWishIds).containsExactlyInAnyOrder(
				FRIEND_VISIBLE_WISH_ID.toString(), NONFRIEND_VISIBLE_WISH_ID.toString());
	}

	@Test
	void departedViewerIsDeniedWithoutAnOutboundAttempt() throws Exception {
		jdbc.update("""
				UPDATE academy_membership
				SET left_at = ?
				WHERE student_id = ? AND academy_id = ?
				""", timestamp(FIXTURE_TIME.plusSeconds(1)), SeedFixtureCatalog.OWNER_ID,
				PRIMARY_ACADEMY_ID);

		performHandoff(OWNER_ACCOUNT_ID)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code")
						.value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));

		assertThat(RECEIVER.requestCount()).isZero();
	}

	@Test
	void incompleteSnapshotDataProducesNoOutboundAttempt() throws Exception {
		jdbc.update("UPDATE academy SET name = '   ' WHERE id = ?", PRIMARY_ACADEMY_ID);

		performHandoff(OWNER_ACCOUNT_ID)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error.code")
						.value("RECOMMENDATION_DATA_INCOMPLETE"));

		assertThat(RECEIVER.requestCount()).isZero();
	}

	@Test
	void handlerEquivalentPathsRequireTheDedicatedTriggerCredentialWithContextPaths()
			throws Exception {
		for (String[] target : new String[][] {
			{PATH + ";x=y", ""},
			{"/crabit" + PATH, "/crabit"},
			{"/crabit" + PATH + ";jsessionid=abc", "/crabit"}
		}) {
			for (String authorization : new String[] {
				null,
				"Bearer receiver-secret",
				"Bearer " + SeedFixtureCatalog.OWNER_TOKEN
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

	private org.springframework.test.web.servlet.ResultActions performHandoff(UUID accountId)
			throws Exception {
		return mockMvc.perform(post(PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer trigger-secret")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"handoff_id":"%s","card_balance_account_id":"%s"}
						""".formatted(HANDOFF_ID, accountId)));
	}

	private org.springframework.test.web.servlet.ResultActions performAuthenticationProbe(
			String requestUri, String contextPath, String authorization) throws Exception {
		MockHttpServletRequestBuilder request = post(requestUri)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}");
		if (!contextPath.isEmpty()) {
			request.contextPath(contextPath);
		}
		if (authorization != null) {
			request.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return mockMvc.perform(request);
	}

	private void insertVisibilityMatrix() {
		insertAccount(FRIEND_ACCOUNT_ID, FRIEND_ID, PRIMARY_ACADEMY_ID, null);
		insertAccount(NONFRIEND_ACCOUNT_ID, NONFRIEND_ID, PRIMARY_ACADEMY_ID, null);
		insertAccount(BLOCKED_ACCOUNT_ID, BLOCKED_ID, PRIMARY_ACADEMY_ID, null);
		insertAccount(OTHER_ACADEMY_ACCOUNT_ID, OTHER_ACADEMY_STUDENT_ID,
				OTHER_ACADEMY_ID, null);
		insertAccount(CLOSED_ACCOUNT_ID, FRIEND_ID, PRIMARY_ACADEMY_ID,
				FIXTURE_TIME.plusSeconds(30));

		insertWishAndCard(FRIEND_VISIBLE_WISH_ID, FRIEND_ACCOUNT_ID, PRIMARY_ACADEMY_ID,
				"IN_PROGRESS", "FRIENDS", null, null);
		insertWishAndCard(NONFRIEND_VISIBLE_WISH_ID, NONFRIEND_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID, "IN_PROGRESS", "ACADEMY", null, null);
		insertWishAndCard(NONFRIEND_FRIENDS_WISH_ID, NONFRIEND_ACCOUNT_ID,
				PRIMARY_ACADEMY_ID, "IN_PROGRESS", "FRIENDS", null, null);
		insertWishAndCard(BLOCKED_WISH_ID, BLOCKED_ACCOUNT_ID, PRIMARY_ACADEMY_ID,
				"IN_PROGRESS", "ACADEMY", null, null);
		insertWishAndCard(OTHER_ACADEMY_WISH_ID, OTHER_ACADEMY_ACCOUNT_ID, OTHER_ACADEMY_ID,
				"IN_PROGRESS", "ACADEMY", null, null);
		insertWish(PRIVATE_WISH_ID, FRIEND_ACCOUNT_ID, PRIMARY_ACADEMY_ID,
				"IN_PROGRESS", "PRIVATE", null, null);
		insertWishAndCard(DELETED_WISH_ID, FRIEND_ACCOUNT_ID, PRIMARY_ACADEMY_ID,
				"IN_PROGRESS", "ACADEMY", FIXTURE_TIME.plusSeconds(20), null);
		insertWishAndCard(ABANDONED_WISH_ID, FRIEND_ACCOUNT_ID, PRIMARY_ACADEMY_ID,
				"ABANDONED", "ACADEMY", null, FIXTURE_TIME.plusSeconds(20));
		insertWishAndCard(CLOSED_ACCOUNT_WISH_ID, CLOSED_ACCOUNT_ID, PRIMARY_ACADEMY_ID,
				"IN_PROGRESS", "ACADEMY", null, null);
	}

	private void insertAccount(
			UUID accountId, UUID studentId, UUID academyId, Instant closedAt) {
		jdbc.update("""
				INSERT INTO card_balance_account
				    (id, student_id, academy_id, opened_at, closed_at,
				     balance_lookup_version, version)
				VALUES (?, ?, ?, ?, ?, 0, 0)
				""", accountId, studentId, academyId, timestamp(FIXTURE_TIME),
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
		jdbc.update("""
				INSERT INTO shared_card (id, wish_id, kind, visibility, updated_at)
				VALUES (?, ?, 'PROGRESS', ?, ?)
				""", cardId(wishId), wishId, visibility, timestamp(FIXTURE_TIME));
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
		jdbc.update("""
				INSERT INTO wish
				    (id, account_id, academy_id, purpose, target_amount, wish_amount,
				     abandonment_amount, state,
				     visibility, created_at, updated_at, target_date, completed_at, abandoned_at,
				     deleted_at, deleted_purpose_snapshot, version)
				VALUES (?, ?, ?, ?, 1000, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, 0)
				""", wishId, accountId, academyId, "후보 " + wishId, wishAmount,
				abandonmentAmount, state, visibility, timestamp(FIXTURE_TIME), timestamp(FIXTURE_TIME),
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
		PostgreSQLContainer database = new PostgreSQLContainer(
				DockerImageName.parse("postgres:16-alpine"));
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
		private final AtomicReference<Runnable> beforeResponse =
				new AtomicReference<>(() -> { });
		private final AtomicReference<Throwable> failure = new AtomicReference<>();

		private TestReceiver(HttpServer server) {
			this.server = server;
		}

		static TestReceiver start() {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				TestReceiver receiver = new TestReceiver(server);
				server.createContext("/receiver", exchange -> {
					receiver.requestCount.incrementAndGet();
					receiver.body.set(exchange.getRequestBody().readAllBytes());
					receiver.authorization.set(
							exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
					receiver.idempotencyKey.set(
							exchange.getRequestHeaders().getFirst("Idempotency-Key"));
					try {
						receiver.beforeResponse.get().run();
						exchange.sendResponseHeaders(204, -1);
					}
					catch (Throwable throwable) {
						receiver.failure.set(throwable);
						exchange.sendResponseHeaders(500, -1);
					}
					finally {
						exchange.close();
					}
				});
				server.start();
				return receiver;
			}
			catch (IOException exception) {
				throw new ExceptionInInitializerError(exception);
			}
		}

		void reset() {
			requestCount.set(0);
			body.set(null);
			authorization.set(null);
			idempotencyKey.set(null);
			beforeResponse.set(() -> { });
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
