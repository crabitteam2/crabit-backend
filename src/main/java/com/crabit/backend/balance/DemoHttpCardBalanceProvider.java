package com.crabit.backend.balance;

import com.crabit.backend.wish.KrwAmount;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("demo & !e2e")
public final class DemoHttpCardBalanceProvider implements CardBalanceProvider {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration RETRY_BACKOFF = Duration.ofMillis(100);
	private static final int MAX_ATTEMPTS = 2;
	private static final int MAX_RESPONSE_BYTES = 16_384;
	private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);
	private static final BigInteger MAX_SAFE_BALANCE =
			BigInteger.valueOf(KrwAmount.MAX_SAFE_WON);

	private final DemoBalanceProviderSettings settings;
	private final ObjectMapper objectMapper;
	private final HttpClient client;
	private final Supplier<UUID> lookupIds;
	private final Sleeper sleeper;

	@Autowired
	public DemoHttpCardBalanceProvider(
			DemoBalanceProviderSettings settings, ObjectMapper objectMapper) {
		this(settings, objectMapper,
				HttpClient.newBuilder()
						.connectTimeout(CONNECT_TIMEOUT)
						.followRedirects(HttpClient.Redirect.NEVER)
						.build(),
				UUID::randomUUID,
				duration -> Thread.sleep(duration.toMillis()));
	}

	DemoHttpCardBalanceProvider(
			DemoBalanceProviderSettings settings,
			ObjectMapper objectMapper,
			HttpClient client,
			Supplier<UUID> lookupIds,
			Sleeper sleeper) {
		this.settings = java.util.Objects.requireNonNull(settings, "settings");
		this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
		this.client = java.util.Objects.requireNonNull(client, "client");
		this.lookupIds = java.util.Objects.requireNonNull(lookupIds, "lookupIds");
		this.sleeper = java.util.Objects.requireNonNull(sleeper, "sleeper");
	}

	@Override
	public CardBalanceProviderResult lookup(UUID accountId) {
		UUID account = java.util.Objects.requireNonNull(accountId, "accountId");
		UUID lookupId = java.util.Objects.requireNonNull(
				lookupIds.get(), "lookupId supplier returned null");
		HttpRequest request = request(lookupId, account);

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				HttpResponse<byte[]> response = client.send(request, this::responseSubscriber);
				if (response.statusCode() == 200) {
					return parse(response, lookupId);
				}
				if (!RETRYABLE_STATUSES.contains(response.statusCode())
						|| attempt == MAX_ATTEMPTS) {
					return CardBalanceProviderResult.failure();
				}
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return CardBalanceProviderResult.failure();
			}
			catch (IOException exception) {
				if (isResponseTooLarge(exception) || attempt == MAX_ATTEMPTS) {
					return CardBalanceProviderResult.failure();
				}
			}

			try {
				sleeper.sleep(RETRY_BACKOFF);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return CardBalanceProviderResult.failure();
			}
		}
		return CardBalanceProviderResult.failure();
	}

	private HttpRequest request(UUID lookupId, UUID accountId) {
		byte[] body = ("{\"lookupId\":\"" + lookupId
				+ "\",\"accountId\":\"" + accountId + "\"}")
				.getBytes(StandardCharsets.UTF_8);
		return HttpRequest.newBuilder(settings.endpoint())
				.timeout(REQUEST_TIMEOUT)
				.header("Authorization", "Bearer " + settings.token())
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
	}

	private HttpResponse.BodySubscriber<byte[]> responseSubscriber(
			HttpResponse.ResponseInfo response) {
		if (response.statusCode() != 200) {
			return HttpResponse.BodySubscribers.mapping(
					HttpResponse.BodySubscribers.discarding(), ignored -> new byte[0]);
		}
		return new BoundedBodySubscriber(MAX_RESPONSE_BYTES);
	}

	private CardBalanceProviderResult parse(HttpResponse<byte[]> response, UUID lookupId) {
		byte[] body = response.body();
		if (body == null || body.length == 0 || body.length > MAX_RESPONSE_BYTES
				|| !isJson(response)) {
			return CardBalanceProviderResult.failure();
		}

		JsonNode root;
		try {
			root = objectMapper.readTree(body);
		}
		catch (JacksonException exception) {
			return CardBalanceProviderResult.failure();
		}
		if (root == null || !root.isObject()
				|| !text(root.get("lookupId"), lookupId.toString())
				|| root.get("outcome") == null
				|| !root.get("outcome").isTextual()
				|| root.get("replayed") == null
				|| !root.get("replayed").isBoolean()) {
			return CardBalanceProviderResult.failure();
		}

		String outcome = root.get("outcome").textValue();
		Set<String> fields = Set.copyOf(root.propertyNames());
		if ("FAILURE".equals(outcome)
				&& fields.equals(Set.of("lookupId", "outcome", "replayed"))) {
			return CardBalanceProviderResult.failure();
		}
		if (!"SUCCESS".equals(outcome)
				|| !fields.equals(Set.of("lookupId", "outcome", "balanceKrw", "replayed"))) {
			return CardBalanceProviderResult.failure();
		}
		JsonNode balance = root.get("balanceKrw");
		if (balance == null || !balance.isIntegralNumber()) {
			return CardBalanceProviderResult.failure();
		}
		BigInteger value = balance.bigIntegerValue();
		if (value.signum() < 0 || value.compareTo(MAX_SAFE_BALANCE) > 0) {
			return CardBalanceProviderResult.failure();
		}
		return new CardBalanceProviderResult.Success(
				KrwAmount.nonNegative(value.longValueExact()));
	}

	private static boolean text(JsonNode node, String expected) {
		return node != null && node.isTextual() && expected.equals(node.textValue());
	}

	private static boolean isJson(HttpResponse<?> response) {
		return response.headers().firstValue("Content-Type").map(value -> {
			try {
				MediaType mediaType = MediaType.parseMediaType(value);
				return "application".equalsIgnoreCase(mediaType.getType())
						&& ("json".equalsIgnoreCase(mediaType.getSubtype())
						|| mediaType.getSubtype().toLowerCase(java.util.Locale.ROOT).endsWith("+json"));
			}
			catch (IllegalArgumentException exception) {
				return false;
			}
		}).orElse(false);
	}

	private static boolean isResponseTooLarge(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof ResponseTooLargeException) {
				return true;
			}
		}
		return false;
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(Duration duration) throws InterruptedException;
	}

	private static final class BoundedBodySubscriber
			implements HttpResponse.BodySubscriber<byte[]> {

		private final int maximumBytes;
		private final CompletableFuture<byte[]> body = new CompletableFuture<>();
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();
		private Flow.Subscription subscription;

		private BoundedBodySubscriber(int maximumBytes) {
			this.maximumBytes = maximumBytes;
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return body;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(1);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			for (ByteBuffer item : items) {
				if (item.remaining() > maximumBytes - output.size()) {
					subscription.cancel();
					body.completeExceptionally(new ResponseTooLargeException());
					return;
				}
				byte[] bytes = new byte[item.remaining()];
				item.get(bytes);
				output.writeBytes(bytes);
			}
			subscription.request(1);
		}

		@Override
		public void onError(Throwable throwable) {
			body.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			body.complete(output.toByteArray());
		}
	}

	private static final class ResponseTooLargeException extends IOException {
		private ResponseTooLargeException() {
			super("Provider response exceeded the configured size limit");
		}
	}
}
