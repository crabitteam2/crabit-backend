package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.recommendation.FeedRankingModels.Request;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FeedRankingClientTest {
	private static final String CLASSIFIER = "wish-category-v1@sha256:" + "a".repeat(64);
	private final ObjectMapper json = new ObjectMapper();

	@Test
	void acceptsOnlyOneExactBoundResponseAndRejectsMismatchedOrUnknownIds() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicReference<String> mode = new AtomicReference<>("valid");
		AtomicInteger calls = new AtomicInteger();
		server.createContext("/internal/v1/feed-rankings", exchange -> {
			calls.incrementAndGet();
			byte[] requestBody = exchange.getRequestBody().readAllBytes();
			var request = json.readTree(requestBody);
			String digest;
			try {
				digest = "sha256:" + HexFormat.of().formatHex(
						MessageDigest.getInstance("SHA-256").digest(requestBody));
			} catch (java.security.GeneralSecurityException impossible) {
				throw new java.io.IOException(impossible);
			}
			String requestId = request.get("request_id").stringValue();
			String contextId = request.get("context_id").stringValue();
			List<String> ids = List.of(request.at("/candidates/1/card_id").stringValue(),
					request.at("/candidates/0/card_id").stringValue());
			switch (mode.get()) {
				case "context" -> contextId = UUID.randomUUID().toString();
				case "digest" -> digest = "sha256:" + "0".repeat(64);
				case "duplicate" -> ids = List.of(ids.getFirst(), ids.getFirst());
				case "unknown" -> ids = List.of(ids.getFirst(), UUID.randomUUID().toString());
				default -> { }
			}
			Object schemaVersion = mode.get().equals("overflow") ? 4_294_967_297L : 1;
			byte[] response = json.writeValueAsBytes(Map.of(
					"schema_version", schemaVersion, "request_id", requestId, "context_id", contextId,
					"input_digest", digest, "model_version", "feed-rules-v1",
					"ordered_card_ids", ids));
			if (mode.get().equals("utf16")) response = new String(response,
					java.nio.charset.StandardCharsets.UTF_8).getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
			if (mode.get().equals("delay")) {
				try { Thread.sleep(600); }
				catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
			}
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (var out = exchange.getResponseBody()) { out.write(response); }
		});
		server.start();
		try {
			var client = client(server);
			assertThat(client.rank(request())).get().satisfies(result ->
					assertThat(result.orderedCardIds()).containsExactly(CARD_TWO, CARD_ONE));
			for (String invalid : List.of("context", "digest", "duplicate", "unknown", "overflow", "utf16", "delay")) {
				mode.set(invalid);
				assertThat(client.rank(request())).isEmpty();
			}
			assertThat(calls).hasValue(8);
		} finally { server.stop(0); }
	}

	@Test
	void enabledSettingsRequireDedicatedExactEndpointAndPinnedClassifier() {
		assertThatThrownBy(() -> new FeedRankingSettings("", "secret", CLASSIFIER))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FeedRankingSettings(
				"https://feed.example.test/internal/v1/feed-rankings?x=1", "secret", CLASSIFIER))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FeedRankingSettings(
				"https://feed.example.test/internal/v1/feed-rankings", "secret", "floating"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requestSnapshotPreservesUnknownNullAndFreezesNestedCandidateData() {
		Map<String, Object> candidate = new java.util.LinkedHashMap<>();
		candidate.put("card_id", CARD_ONE);
		Map<String, Object> month = unobservedMonth();
		Request request = new Request(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), Instant.parse("2026-09-06T00:00:00Z"), "Asia/Seoul",
				"feed-features-v1", CLASSIFIER, month, List.of(candidate));
		assertThat(request.viewer_previous_month()).containsEntry("values", null);
		month.put("coverage", "COMPLETE"); candidate.put("card_id", CARD_TWO);
		assertThat(request.viewer_previous_month()).containsEntry("coverage", "UNOBSERVED");
		assertThat(request.candidates().getFirst()).containsEntry("card_id", CARD_ONE);
		assertThatThrownBy(() -> request.candidates().getFirst().put("card_id", CARD_TWO))
				.isInstanceOf(UnsupportedOperationException.class);

		Request complete = new Request(1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), Instant.parse("2026-09-06T00:00:00Z"), "Asia/Seoul",
				"feed-features-v1", CLASSIFIER, completeZeroMonth(), List.of(candidate));
		assertThat(complete.viewer_previous_month()).containsEntry("coverage", "COMPLETE");
		@SuppressWarnings("unchecked")
		Map<String, Object> completeValues =
				(Map<String, Object>) complete.viewer_previous_month().get("values");
		assertThat(completeValues)
				.containsEntry("deposit_count", 0).containsEntry("regularity_std", null)
				.containsEntry("pace_bias", null);
	}

	@Test
	void preparationExhaustionPreventsHttpAndWrongArtifactPreventsEnabledStartup() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger calls = new AtomicInteger();
		server.createContext("/internal/v1/feed-rankings", exchange -> {
			calls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close();
		});
		server.start();
		try {
			assertThat(client(server).rank(() -> {
				try { Thread.sleep(510); }
				catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
				return request();
			})).isEmpty();
			assertThat(calls).hasValue(0);
		} finally { server.stop(0); }
		var classifier = new FeedCategoryClassifier(json);
		assertThatThrownBy(() -> new FeedRankingSettings(
				"https://feed.example.test/internal/v1/feed-rankings", "secret", CLASSIFIER, classifier))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bundled artifact");
		assertThat(new FeedRankingSettings("https://feed.example.test/internal/v1/feed-rankings",
				"secret", classifier.version(), classifier).classifierVersion()).isEqualTo(classifier.version());
	}

	private FeedRankingClient client(HttpServer server) {
		var settings = new FeedRankingSettings(
				"http://127.0.0.1:" + server.getAddress().getPort() + "/internal/v1/feed-rankings",
				"feed-secret", CLASSIFIER);
		return new FeedRankingClient(settings, json, HttpClient.newBuilder()
				.connectTimeout(FeedRankingClient.BUDGET).followRedirects(HttpClient.Redirect.NEVER).build());
	}

	private static final UUID CARD_ONE = UUID.fromString("11111111-1111-4111-8111-111111111111");
	private static final UUID CARD_TWO = UUID.fromString("22222222-2222-4222-8222-222222222222");

	private static Request request() {
		return new Request(1, UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
				UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
				UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
				UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
				Instant.parse("2026-09-06T00:00:00Z"), "Asia/Seoul", "feed-features-v1",
				CLASSIFIER, unobservedMonth(),
				List.of(Map.of("card_id", CARD_ONE), Map.of("card_id", CARD_TWO)));
	}

	private static Map<String, Object> unobservedMonth() {
		Map<String, Object> month = new java.util.LinkedHashMap<>();
		month.put("month", "2026-08"); month.put("coverage", "UNOBSERVED");
		month.put("metrics_version", "core-metrics-v1"); month.put("values", null);
		return month;
	}

	private static Map<String, Object> completeZeroMonth() {
		Map<String, Object> values = new java.util.LinkedHashMap<>();
		values.put("deposit_count", 0); values.put("total_savings", 0); values.put("avg_amount", 0.0);
		values.put("regularity_std", null); values.put("pace_bias", null); values.put("abandon_count", 0);
		values.put("transfer_count", 0); values.put("visit_count", 0);
		Map<String, Object> month = new java.util.LinkedHashMap<>();
		month.put("month", "2026-08"); month.put("coverage", "COMPLETE");
		month.put("metrics_version", "core-metrics-v1"); month.put("values", values);
		return month;
	}
}
