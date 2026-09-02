package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class RecommendationReceiverClientTest {

	private static final UUID HANDOFF_ID = UUID.fromString("00000000-0000-0000-0000-000000009001");
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void sendsOneExactAuthenticatedRequestAndRequiresExactVersionedAcknowledgment()
			throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger requestCount = new AtomicInteger();
		AtomicInteger responseStatus = new AtomicInteger(200);
		AtomicReference<byte[]> body = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<String> idempotencyKey = new AtomicReference<>();
		server.createContext(
				"/receiver",
				exchange -> {
					requestCount.incrementAndGet();
					body.set(exchange.getRequestBody().readAllBytes());
					authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
					idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
					byte[] ack =
							("{\"schema_version\":3,\"handoff_id\":\""
											+ HANDOFF_ID
											+ "\",\"accepted\":true}")
									.getBytes(java.nio.charset.StandardCharsets.UTF_8);
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					exchange.sendResponseHeaders(responseStatus.get(), ack.length);
					exchange.getResponseBody().write(ack);
					exchange.close();
				});
		server.start();
		try {
			RecommendationReceiverClient client =
					client("http://127.0.0.1:" + server.getAddress().getPort() + "/receiver");
			client.send(payload());

			assertThat(requestCount).hasValue(1);
			assertThat(authorization).hasValue("Bearer receiver-secret");
			assertThat(idempotencyKey).hasValue(HANDOFF_ID.toString());
			JsonNode json = objectMapper.readTree(body.get());
			assertThat(json.propertyNames())
					.containsExactlyInAnyOrder(
							"schema_version",
							"synthetic_feature_version",
							"handoff_id",
							"snapshot_at",
							"viewer_wishes_truncated",
							"candidates_truncated",
							"academy",
							"viewer",
							"card_account",
							"viewer_wishes",
							"candidates",
							"viewer_period_savings",
							"candidate_selection",
							"interest_evidence");
			assertThat(json.get("schema_version").intValue()).isEqualTo(3);
			assertThat(json.at("/card_account/closed_at").isNull()).isTrue();
			assertThat(json.at("/viewer_wishes/0/wish").propertyNames())
					.containsExactlyInAnyOrder(
							"wish_id",
							"academy_id",
							"account_id",
							"title",
							"target_amount",
							"start_date",
							"target_date",
							"is_representative",
							"status",
							"created_at",
							"closed_at",
							"saved_amount",
							"abandonment_amount");
			assertThat(json.at("/viewer_wishes/0/wish/start_date").textValue())
					.isEqualTo("2026-01-15");
			assertThat(json.at("/candidates/0/wish").propertyNames())
					.containsExactlyInAnyOrder(
							"wish_id",
							"academy_id",
							"account_id",
							"title",
							"target_amount",
							"start_date",
							"target_date",
							"status",
							"created_at",
							"closed_at",
							"saved_amount");
			assertThat(json.at("/candidates/0/wish").has("start_date")).isTrue();
			assertThat(json.at("/candidates/0/wish/start_date").isNull()).isTrue();

			responseStatus.set(299);
			assertThatThrownBy(() -> client.send(payload()))
					.isInstanceOf(RecommendationHandoffException.class);
			assertThat(requestCount).hasValue(2);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void boundsTotalResponseTimeWhileTheReceiverStallsItsBodyWithoutRetry() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
		server.setExecutor(executor);
		var count = new AtomicInteger();
		server.createContext(
				"/receiver",
				exchange -> {
					count.incrementAndGet();
					exchange.getRequestBody().readAllBytes();
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					exchange.sendResponseHeaders(200, 100);
					exchange.getResponseBody().write('{');
					exchange.getResponseBody().flush();
					try {
						Thread.sleep(15000);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					} finally {
						exchange.close();
					}
				});
		server.start();
		long started = System.nanoTime();
		try {
			assertThatThrownBy(
							() ->
									client(
													"http://127.0.0.1:"
															+ server.getAddress().getPort()
															+ "/receiver")
											.send(payload()))
					.isInstanceOf(RecommendationHandoffException.class)
					.extracting(ex -> ((RecommendationHandoffException) ex).code())
					.isEqualTo(
							RecommendationHandoffException.Code
									.RECOMMENDATION_RECEIVER_UNAVAILABLE);
			assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
					.isLessThan(java.time.Duration.ofSeconds(12));
			assertThat(count).hasValue(1);
		} finally {
			server.stop(0);
			executor.shutdownNow();
		}
	}

	@Test
	void validatesSharedAcknowledgmentsAndRejectsUnboundedAmbiguousBodies() throws Exception {
		var response = new AtomicReference<byte[]>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(
				"/receiver",
				exchange -> {
					exchange.getRequestBody().readAllBytes();
					byte[] bytes = response.get();
					exchange.getResponseHeaders()
							.set("Content-Type", "application/json; charset=utf-8");
					exchange.sendResponseHeaders(200, bytes.length);
					exchange.getResponseBody().write(bytes);
					exchange.close();
				});
		server.start();
		try {
			var client = client("http://127.0.0.1:" + server.getAddress().getPort() + "/receiver");
			var root = java.nio.file.Path.of("src/test/resources/recommendation");
			var cases =
					objectMapper.readTree(
							java.nio.file.Files.readAllBytes(root.resolve("cases.json")));
			for (var entry : cases.get("acks")) {
				response.set(
						java.nio.file.Files.readAllBytes(
								root.resolve(entry.get("file").textValue())));
				if (entry.get("valid").booleanValue()) client.send(payload());
				else
					assertThatThrownBy(() -> client.send(payload()))
							.isInstanceOf(RecommendationHandoffException.class);
			}
			for (String body :
					java.util.List.of(
							" ".repeat(4097),
							"{} {}",
							"{\"schema_version\":3,\"schema_version\":3,\"handoff_id\":\""
									+ HANDOFF_ID
									+ "\",\"accepted\":true}",
							"{\"schema_version\":18446744073709551619,\"handoff_id\":\""
									+ HANDOFF_ID
									+ "\",\"accepted\":true}")) {
				response.set(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				assertThatThrownBy(() -> client.send(payload()))
						.isInstanceOf(RecommendationHandoffException.class);
			}
		} finally {
			server.stop(0);
		}
	}

	@Test
	void mapsNonTwoHundredAndConnectionFailureWithoutRetrying() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger requestCount = new AtomicInteger();
		server.createContext(
				"/receiver",
				exchange -> {
					requestCount.incrementAndGet();
					exchange.sendResponseHeaders(503, -1);
					exchange.close();
				});
		server.start();
		try {
			RecommendationReceiverClient client =
					client("http://127.0.0.1:" + server.getAddress().getPort() + "/receiver");
			assertThatThrownBy(() -> client.send(payload()))
					.isInstanceOf(RecommendationHandoffException.class)
					.extracting(exception -> ((RecommendationHandoffException) exception).code())
					.isEqualTo(
							RecommendationHandoffException.Code.RECOMMENDATION_RECEIVER_REJECTED);
			assertThat(requestCount).hasValue(1);
		} finally {
			server.stop(0);
		}

		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		assertThatThrownBy(
						() ->
								client("http://127.0.0.1:" + closedPort + "/receiver")
										.send(payload()))
				.isInstanceOf(RecommendationHandoffException.class)
				.extracting(exception -> ((RecommendationHandoffException) exception).code())
				.isEqualTo(RecommendationHandoffException.Code.RECOMMENDATION_RECEIVER_UNAVAILABLE);
	}

	@Test
	void enabledSettingsFailClosedForMissingInvalidOrSharedConfiguration() {
		assertThatThrownBy(() -> new RecommendationHandoffSettings("", "trigger", "receiver"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
						() ->
								new RecommendationHandoffSettings(
										"file:///tmp/receiver", "trigger", "receiver"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
						() ->
								new RecommendationHandoffSettings(
										"https://receiver.example.test", "", "receiver"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
						() ->
								new RecommendationHandoffSettings(
										"https://receiver.example.test", "same", "same"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private RecommendationReceiverClient client(String endpoint) {
		RecommendationHandoffSettings settings =
				new RecommendationHandoffSettings(endpoint, "trigger-secret", "receiver-secret");
		return new RecommendationReceiverClient(
				settings,
				objectMapper,
				HttpClient.newBuilder()
						.connectTimeout(java.time.Duration.ofSeconds(1))
						.followRedirects(HttpClient.Redirect.NEVER)
						.build());
	}

	private static RecommendationPayload payload() {
		UUID academyId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
		UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000301");
		UUID viewerWishId = UUID.fromString("00000000-0000-0000-0000-000000000401");
		UUID candidateUserId = UUID.fromString("00000000-0000-0000-0000-000000000501");
		UUID candidateAccountId = UUID.fromString("00000000-0000-0000-0000-000000000601");
		UUID candidateWishId = UUID.fromString("00000000-0000-0000-0000-000000000701");
		return new RecommendationPayload(
				3,
				1,
				HANDOFF_ID,
				"2026-08-31T05:10:00Z",
				false,
				false,
				new AcademyPayload(
						academyId, "합성 학원", "SYNTHETIC_REGION_042", "중등", "어학", "100명 미만"),
				new PersonPayload(userId, "합성 학생", 14),
				new CardAccountPayload(accountId, userId, academyId, "2026-01-01T00:00:00Z", null),
				List.of(
						new ViewerWishItemPayload(
								new WishPayload(
										viewerWishId,
										academyId,
										accountId,
										"완료 위시",
										1_000,
										"2026-01-15",
										"2026-06-30",
										true,
										"COMPLETED",
										"2026-01-01T00:00:00Z",
										"2026-02-01T00:00:00Z",
										1_000,
										null),
								new SavingsSummaryPayload(2, 1_200, 200, "2026-02-01T00:00:00Z"))),
				List.of(
						new CandidatePayload(
								new PersonPayload(candidateUserId, "후보 학생", 15),
								new CardAccountPayload(
										candidateAccountId,
										candidateUserId,
										academyId,
										"2026-01-02T00:00:00Z",
										null),
								new CandidateWishPayload(
										candidateWishId,
										academyId,
										candidateAccountId,
										"후보 위시",
										2_000,
										null,
										"2026-12-31",
										"IN_PROGRESS",
										"2026-01-03T00:00:00Z",
										null,
										500),
								new SharedCardPayload(
										UUID.fromString("00000000-0000-0000-0000-000000000801"),
										candidateAccountId,
										candidateWishId,
										"FOLLOWERS",
										"2026-08-31T05:00:00Z"),
								new SavingsSummaryPayload(1, 500, 0, "2026-08-30T05:00:00Z"))),
				java.util.Map.of(),
				java.util.Map.of(),
				java.util.Map.of());
	}
}
