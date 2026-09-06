package com.crabit.backend.recommendation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "crabit.recommendation.handoff.enabled", havingValue = "true")
final class RecommendationReceiverClient {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
	private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

	private final RecommendationHandoffSettings settings;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;

	@Autowired
	RecommendationReceiverClient(
			RecommendationHandoffSettings settings, ObjectMapper objectMapper) {
		this(
				settings,
				objectMapper,
				HttpClient.newBuilder()
						.connectTimeout(CONNECT_TIMEOUT)
						.followRedirects(HttpClient.Redirect.NEVER)
						.build());
	}

	RecommendationReceiverClient(
			RecommendationHandoffSettings settings,
			ObjectMapper objectMapper,
			HttpClient httpClient) {
		this.settings = Objects.requireNonNull(settings, "settings");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
	}

	void send(RecommendationPayload payload) {
		byte[] body;
		try {
			body = objectMapper.writeValueAsBytes(payload);
		} catch (JacksonException exception) {
			throw RecommendationHandoffException.incomplete();
		}
		HttpRequest request =
				HttpRequest.newBuilder(settings.receiverUrl())
						.timeout(RESPONSE_TIMEOUT)
						.header("Authorization", "Bearer " + settings.receiverCredential())
						.header("Content-Type", "application/json")
						.header("Idempotency-Key", payload.handoff_id().toString())
						.POST(HttpRequest.BodyPublishers.ofByteArray(body))
						.build();
		var future = httpClient.sendAsync(request, info -> new BoundedBodySubscriber(4096));
		try {
			HttpResponse<byte[]> response =
					future.get(
							RESPONSE_TIMEOUT.toMillis(),
							java.util.concurrent.TimeUnit.MILLISECONDS);
			if (response.statusCode() != 200)
				throw RecommendationHandoffException.receiverRejected();
			try {
				var contentType =
						org.springframework.http.MediaType.parseMediaType(
								response.headers().firstValue("Content-Type").orElse(""));
				if (!"application".equalsIgnoreCase(contentType.getType())
						|| !"json".equalsIgnoreCase(contentType.getSubtype()))
					throw RecommendationHandoffException.receiverRejected();
				var root = RecommendationRequest.JSON.readTree(response.body());
				RecommendationRequest.fields(
						root,
						java.util.Set.of("schema_version", "handoff_id", "accepted"),
						java.util.Set.of());
				if (!root.get("schema_version").isIntegralNumber()
						|| !root.get("schema_version")
								.bigIntegerValue()
								.equals(java.math.BigInteger.valueOf(3))
						|| !root.get("accepted").isBoolean()
						|| !root.get("accepted").booleanValue()
						|| !payload.handoff_id()
								.equals(RecommendationRequest.uuid(root.get("handoff_id"))))
					throw RecommendationHandoffException.receiverRejected();
			} catch (RuntimeException ex) {
				throw RecommendationHandoffException.receiverRejected();
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			throw RecommendationHandoffException.receiverUnavailable();
		} catch (java.util.concurrent.TimeoutException ex) {
			future.cancel(true);
			throw RecommendationHandoffException.receiverUnavailable();
		} catch (java.util.concurrent.ExecutionException ex) {
			if (ex.getCause() instanceof RecommendationHandoffException)
				throw RecommendationHandoffException.receiverRejected();
			throw RecommendationHandoffException.receiverUnavailable();
		}
	}

	static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final int max;
		private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		private final java.util.concurrent.CompletableFuture<byte[]> body =
				new java.util.concurrent.CompletableFuture<>();
		private java.util.concurrent.Flow.Subscription subscription;

		BoundedBodySubscriber(int max) {
			this.max = max;
		}

		public java.util.concurrent.CompletionStage<byte[]> getBody() {
			return body;
		}

		public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(1);
		}

		public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
			for (var buffer : buffers) {
				if (buffer.remaining() > max - bytes.size()) {
					subscription.cancel();
					body.completeExceptionally(RecommendationHandoffException.receiverRejected());
					return;
				}
				byte[] chunk = new byte[buffer.remaining()];
				buffer.get(chunk);
				bytes.writeBytes(chunk);
			}
			subscription.request(1);
		}

		public void onError(Throwable error) {
			body.completeExceptionally(error);
		}

		public void onComplete() {
			body.complete(bytes.toByteArray());
		}
	}
}
