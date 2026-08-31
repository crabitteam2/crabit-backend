package com.crabit.backend.recommendation;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(
		name = "crabit.recommendation.handoff.enabled", havingValue = "true")
final class RecommendationReceiverClient {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
	private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

	private final RecommendationHandoffSettings settings;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;

	@Autowired
	RecommendationReceiverClient(
			RecommendationHandoffSettings settings, ObjectMapper objectMapper) {
		this(settings, objectMapper, HttpClient.newBuilder()
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
		}
		catch (JacksonException exception) {
			throw RecommendationHandoffException.incomplete();
		}
		HttpRequest request = HttpRequest.newBuilder(settings.receiverUrl())
				.timeout(RESPONSE_TIMEOUT)
				.header("Authorization", "Bearer " + settings.receiverCredential())
				.header("Content-Type", "application/json")
				.header("Idempotency-Key", payload.handoff_id().toString())
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
		try {
			HttpResponse<Void> response = httpClient.send(
					request, HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() < 200 || response.statusCode() > 299) {
				throw RecommendationHandoffException.receiverRejected();
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw RecommendationHandoffException.receiverUnavailable();
		}
		catch (IOException exception) {
			throw RecommendationHandoffException.receiverUnavailable();
		}
	}
}
