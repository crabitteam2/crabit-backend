package com.crabit.backend.recommendation;

import static com.crabit.backend.recommendation.FeedRankingModels.*;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Makes exactly one bounded foreground call to the Python-only ranking route. */
@Component
@ConditionalOnProperty(name = "crabit.feed.ranking.enabled", havingValue = "true")
public final class FeedRankingClient {
	static final int MAX_REQUEST_BYTES = 262_144;
	static final int MAX_RESPONSE_BYTES = 65_536;
	static final Duration BUDGET = Duration.ofMillis(500);
	private final FeedRankingSettings settings; private final ObjectMapper json; private final HttpClient http;

	@Autowired
	FeedRankingClient(FeedRankingSettings settings, ObjectMapper json) {
		this(settings, json, HttpClient.newBuilder().connectTimeout(BUDGET)
				.followRedirects(HttpClient.Redirect.NEVER).build());
	}
	FeedRankingClient(FeedRankingSettings settings, ObjectMapper json, HttpClient http) {
		this.settings = settings; this.json = json; this.http = http;
	}

	/** Transport and protocol failures select latest-only; domain failures remain outside this client. */
	public Optional<Result> rank(java.util.function.Supplier<Request> preparation) {
		FeedRankingDeadline deadline = FeedRankingDeadline.start();
		Request input = preparation.get();
		return rank(input, deadline);
	}

	public Optional<Result> rank(Request input) {
		return rank(input, FeedRankingDeadline.start());
	}

	/** The caller starts this deadline before collecting candidates or metrics. */
	public Optional<Result> rank(Request input, FeedRankingDeadline deadline) {
		CompletableFuture<HttpResponse<byte[]>> future = null;
		try {
			if (deadline.expired()) return Optional.empty();
			byte[] body = json.writeValueAsBytes(input);
			if (body.length > MAX_REQUEST_BYTES) return Optional.empty();
			String digest = "sha256:" + HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(body));
			long remaining = deadline.remainingNanos();
			if (remaining <= 0) return Optional.empty();
			HttpRequest outbound = HttpRequest.newBuilder(settings.url()).timeout(Duration.ofNanos(remaining))
					.header("Authorization", "Bearer " + settings.credential())
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
			future = http.sendAsync(outbound, ignored -> new BoundedBodySubscriber(MAX_RESPONSE_BYTES));
			HttpResponse<byte[]> response = future.get(Math.max(1, deadline.remainingNanos()), TimeUnit.NANOSECONDS);
			if (response.statusCode() != 200 || !jsonContentType(response)) return Optional.empty();
			Optional<Result> result = validate(input, digest, response.body());
			return !deadline.expired() ? result : Optional.empty();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt(); if (future != null) future.cancel(true); return Optional.empty();
		} catch (Exception failure) {
			if (future != null) future.cancel(true); return Optional.empty();
		}
	}

	private Optional<Result> validate(Request request, String digest, byte[] bytes) {
		String utf8;
		try {
			utf8 = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (java.nio.charset.CharacterCodingException invalid) { return Optional.empty(); }
		JsonNode root = RecommendationRequest.JSON.readTree(utf8);
		RecommendationRequest.fields(root, Set.of("schema_version", "request_id", "context_id",
				"input_digest", "model_version", "ordered_card_ids"), Set.of());
		if (!root.get("schema_version").isIntegralNumber()
				|| !java.math.BigInteger.ONE.equals(root.get("schema_version").bigIntegerValue())
				|| !request.request_id().equals(RecommendationRequest.uuid(root.get("request_id")))
				|| !request.context_id().equals(RecommendationRequest.uuid(root.get("context_id")))
				|| !digest.equals(text(root, "input_digest"))
				|| !"feed-rules-v1".equals(text(root, "model_version"))
				|| !root.get("ordered_card_ids").isArray()) return Optional.empty();
		List<UUID> ordered = new java.util.ArrayList<>();
		for (JsonNode id : root.get("ordered_card_ids")) ordered.add(RecommendationRequest.uuid(id));
		if (ordered.size() != Math.min(20, request.candidates().size())
				|| new HashSet<>(ordered).size() != ordered.size()) return Optional.empty();
		Set<UUID> candidates = new HashSet<>();
		for (var candidate : request.candidates()) candidates.add(UUID.fromString(candidate.get("card_id").toString()));
		if (!candidates.containsAll(ordered)) return Optional.empty();
		return Optional.of(new Result(request.request_id(), request.context_id(), digest, ordered));
	}

	private static String text(JsonNode root, String key) {
		JsonNode value = root.get(key); return value != null && value.isTextual() ? value.stringValue() : null;
	}
	private static boolean jsonContentType(HttpResponse<?> response) {
		try {
			MediaType type = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElse(""));
			return "application".equalsIgnoreCase(type.getType()) && "json".equalsIgnoreCase(type.getSubtype());
		} catch (RuntimeException invalid) { return false; }
	}

	static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final int max; private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final CompletableFuture<byte[]> body = new CompletableFuture<>(); private Flow.Subscription subscription;
		BoundedBodySubscriber(int max) { this.max = max; }
		public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }
		public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
		public void onNext(List<ByteBuffer> buffers) {
			for (ByteBuffer buffer : buffers) {
				if (buffer.remaining() > max - bytes.size()) {
					subscription.cancel(); body.completeExceptionally(new IllegalArgumentException("response too large")); return;
				}
				byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
			}
			subscription.request(1);
		}
		public void onError(Throwable error) { body.completeExceptionally(error); }
		public void onComplete() { body.complete(bytes.toByteArray()); }
	}
}
