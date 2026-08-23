package com.crabit.backend.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crabit.backend.wish.KrwAmount;
import java.io.ByteArrayOutputStream;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DemoHttpCardBalanceProviderTest {
	// Response fixtures mirror Demo Scenario Console revision
	// e9752ca81c7ec18c00e5f1407a86859b51e016e3.

	private static final String ENDPOINT =
			"https://console.example.test/api/provider/balance-lookups";
	private static final String TOKEN = "demo-provider-machine-token-123456789";
	private static final UUID LOOKUP_ID =
			UUID.fromString("00000000-0000-0000-0000-000000004001");
	private static final UUID ACCOUNT_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000301");

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void sendsTheAuthenticatedContractRequestAndMapsSuccess() throws Exception {
		RecordingHttpClient client = new RecordingHttpClient();
		client.enqueue(response(200, "application/json; charset=utf-8", """
				{"lookupId":"%s","outcome":"SUCCESS","balanceKrw":125000,"replayed":false}
				""".formatted(LOOKUP_ID)));
		DemoHttpCardBalanceProvider provider = provider(client, () -> LOOKUP_ID);

		CardBalanceProviderResult result = provider.lookup(ACCOUNT_ID);

		assertThat(result).isEqualTo(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(125_000)));
		assertThat(client.requests).hasSize(1);
		HttpRequest request = client.requests.getFirst();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.uri()).isEqualTo(URI.create(ENDPOINT));
		assertThat(request.headers().allValues("Authorization"))
				.containsExactly("Bearer " + TOKEN);
		assertThat(request.headers().firstValue("Content-Type"))
				.contains("application/json");
		assertThat(request.headers().firstValue("Accept"))
				.contains("application/json");
		assertThat(request.timeout()).contains(Duration.ofSeconds(3));
		JsonNode body = objectMapper.readTree(requestBody(request));
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("lookupId", "accountId");
		assertThat(body.get("lookupId").textValue()).isEqualTo(LOOKUP_ID.toString());
		assertThat(body.get("accountId").textValue()).isEqualTo(ACCOUNT_ID.toString());
	}

	@Test
	void mapsAValidProviderFailureWithoutRetrying() {
		RecordingHttpClient client = new RecordingHttpClient();
		client.enqueue(response(200, "application/problem+json", """
				{"lookupId":"%s","outcome":"FAILURE","replayed":true}
				""".formatted(LOOKUP_ID)));

		assertThat(provider(client, () -> LOOKUP_ID).lookup(ACCOUNT_ID))
				.isEqualTo(CardBalanceProviderResult.failure());
		assertThat(client.requests).hasSize(1);
	}

	@Test
	void retriesTransientStatusesWithOneLookupIdAndUsesANewIdForTheNextLookup()
			throws Exception {
		UUID nextLookupId = UUID.fromString("00000000-0000-0000-0000-000000004002");
		java.util.ArrayDeque<UUID> lookupIds = new java.util.ArrayDeque<>(
				List.of(LOOKUP_ID, nextLookupId));
		RecordingHttpClient client = new RecordingHttpClient();
		client.enqueue(response(503, "text/plain", "ignored provider detail"));
		client.enqueue(success(LOOKUP_ID, 125_000));
		client.enqueue(success(nextLookupId, 126_000));
		DemoHttpCardBalanceProvider provider = provider(client, lookupIds::remove);

		assertThat(provider.lookup(ACCOUNT_ID)).isEqualTo(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(125_000)));
		assertThat(provider.lookup(ACCOUNT_ID)).isEqualTo(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(126_000)));

		assertThat(client.requests).hasSize(3);
		JsonNode first = objectMapper.readTree(requestBody(client.requests.get(0)));
		JsonNode retry = objectMapper.readTree(requestBody(client.requests.get(1)));
		JsonNode later = objectMapper.readTree(requestBody(client.requests.get(2)));
		assertThat(retry).isEqualTo(first);
		assertThat(first.get("lookupId").textValue()).isEqualTo(LOOKUP_ID.toString());
		assertThat(later.get("lookupId").textValue()).isEqualTo(nextLookupId.toString());
		assertThat(first.get("accountId").textValue()).isEqualTo(ACCOUNT_ID.toString());
		assertThat(later.get("accountId").textValue()).isEqualTo(ACCOUNT_ID.toString());
	}

	@Test
	void failsClosedWithoutRetryForInvalidSuccessEnvelopes() {
		List<InvalidResponse> invalidResponses = List.of(
				new InvalidResponse("malformed JSON", "application/json", "{"),
				new InvalidResponse("wrong lookupId", "application/json", """
						{"lookupId":"00000000-0000-0000-0000-000000004099","outcome":"SUCCESS","balanceKrw":1,"replayed":false}
						"""),
				new InvalidResponse("wrong account field", "application/json", """
						{"lookupId":"%s","accountId":"00000000-0000-0000-0000-000000000399","outcome":"SUCCESS","balanceKrw":1,"replayed":false}
						""".formatted(LOOKUP_ID)),
				new InvalidResponse("negative balance", "application/json", """
						{"lookupId":"%s","outcome":"SUCCESS","balanceKrw":-1,"replayed":false}
						""".formatted(LOOKUP_ID)),
				new InvalidResponse("unsafe balance", "application/json", """
						{"lookupId":"%s","outcome":"SUCCESS","balanceKrw":9007199254740992,"replayed":false}
						""".formatted(LOOKUP_ID)),
				new InvalidResponse("fractional balance", "application/json", """
						{"lookupId":"%s","outcome":"SUCCESS","balanceKrw":1.5,"replayed":false}
						""".formatted(LOOKUP_ID)),
				new InvalidResponse("missing replayed", "application/json", """
						{"lookupId":"%s","outcome":"SUCCESS","balanceKrw":1}
						""".formatted(LOOKUP_ID)),
				new InvalidResponse("non-boolean replayed", "application/json", """
						{"lookupId":"%s","outcome":"SUCCESS","balanceKrw":1,"replayed":"false"}
						""".formatted(LOOKUP_ID)),
				new InvalidResponse("extra field", "application/json", """
						{"lookupId":"%s","outcome":"SUCCESS","balanceKrw":1,"replayed":false,"detail":"private"}
						""".formatted(LOOKUP_ID)),
				new InvalidResponse("unknown outcome", "application/json", """
						{"lookupId":"%s","outcome":"PENDING","replayed":false}
						""".formatted(LOOKUP_ID)),
				new InvalidResponse("failure carrying balance", "application/json", """
						{"lookupId":"%s","outcome":"FAILURE","balanceKrw":1,"replayed":false}
						""".formatted(LOOKUP_ID)),
				new InvalidResponse("wrong media type", "text/plain", """
						{"lookupId":"%s","outcome":"SUCCESS","balanceKrw":1,"replayed":false}
						""".formatted(LOOKUP_ID)),
				new InvalidResponse("oversized body", "application/json", "x".repeat(16_385)));

		for (InvalidResponse invalid : invalidResponses) {
			RecordingHttpClient client = new RecordingHttpClient();
			client.enqueue(response(200, invalid.contentType(), invalid.body()));
			client.enqueue(success(LOOKUP_ID, 999));

			assertThat(provider(client, () -> LOOKUP_ID).lookup(ACCOUNT_ID))
					.as(invalid.description())
					.isEqualTo(CardBalanceProviderResult.failure());
			assertThat(client.requests).as(invalid.description()).hasSize(1);
		}
	}

	@Test
	void retriesOnlyTheRegisteredTransientStatuses() {
		for (int status : List.of(429, 502, 503, 504)) {
			RecordingHttpClient client = new RecordingHttpClient();
			client.enqueue(response(status, "text/plain", "private detail"));
			client.enqueue(response(status, "text/plain", "private detail"));

			assertThat(provider(client, () -> LOOKUP_ID).lookup(ACCOUNT_ID))
					.as("HTTP %s", status)
					.isEqualTo(CardBalanceProviderResult.failure());
			assertThat(client.requests).as("HTTP %s", status).hasSize(2);
		}

		for (int status : List.of(400, 401, 404, 500)) {
			RecordingHttpClient client = new RecordingHttpClient();
			client.enqueue(response(status, "text/plain", "private detail"));
			client.enqueue(success(LOOKUP_ID, 999));

			assertThat(provider(client, () -> LOOKUP_ID).lookup(ACCOUNT_ID))
					.as("HTTP %s", status)
					.isEqualTo(CardBalanceProviderResult.failure());
			assertThat(client.requests).as("HTTP %s", status).hasSize(1);
		}
	}

	@Test
	void retriesTimeoutAndConnectionFailureThenFailsAfterExhaustion() {
		for (java.io.IOException failure : List.of(
				new HttpTimeoutException("timed out"),
				new ConnectException("connection refused"))) {
			RecordingHttpClient client = new RecordingHttpClient();
			client.enqueue(failure);
			client.enqueue(failure);

			assertThat(provider(client, () -> LOOKUP_ID).lookup(ACCOUNT_ID))
					.isEqualTo(CardBalanceProviderResult.failure());
			assertThat(client.requests).hasSize(2);
		}
	}

	@Test
	void retriesAnAmbiguousTransportFailureWithTheSameContractRequest() throws Exception {
		RecordingHttpClient client = new RecordingHttpClient();
		client.enqueue(new HttpTimeoutException("timed out after sending"));
		client.enqueue(success(LOOKUP_ID, 125_000));

		assertThat(provider(client, () -> LOOKUP_ID).lookup(ACCOUNT_ID)).isEqualTo(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(125_000)));
		assertThat(client.requests).hasSize(2);
		assertThat(objectMapper.readTree(requestBody(client.requests.get(1))))
				.isEqualTo(objectMapper.readTree(requestBody(client.requests.get(0))));
	}

	@Test
	void interruptionStopsRetryAndRestoresTheInterruptFlag() {
		RecordingHttpClient client = new RecordingHttpClient();
		client.enqueue(new InterruptedException("interrupted"));
		try {
			assertThat(provider(client, () -> LOOKUP_ID).lookup(ACCOUNT_ID))
					.isEqualTo(CardBalanceProviderResult.failure());
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			assertThat(client.requests).hasSize(1);
		}
		finally {
			Thread.interrupted();
		}
	}

	@Test
	void settingsRejectUnsafeEndpointsAndCredentialsWithoutEchoingSecrets() {
		for (String endpoint : List.of(
				"", "http://console.example.test/api/provider/balance-lookups",
				"https://user@console.example.test/api/provider/balance-lookups",
				"https://console.example.test/api/provider/balance-lookups/",
				"https://console.example.test/api/provider/balance-lookups?tenant=demo",
				"https://console.example.test/api/provider/balance-lookups#fragment")) {
			assertThatThrownBy(() -> new DemoBalanceProviderSettings(endpoint, TOKEN))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(DemoBalanceProviderSettings.URL_ENV)
					.satisfies(exception -> {
						if (!endpoint.isEmpty()) {
							assertThat(exception.getMessage()).doesNotContain(endpoint);
						}
					});
		}

		for (String secret : List.of("", "too-short", "x".repeat(31),
				"private-provider-token-with-space 123", "x".repeat(31) + "\u007f")) {
			assertThatThrownBy(() -> new DemoBalanceProviderSettings(ENDPOINT, secret))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(DemoBalanceProviderSettings.TOKEN_ENV)
					.satisfies(exception -> {
						if (!secret.isEmpty()) {
							assertThat(exception.getMessage()).doesNotContain(secret);
						}
					});
		}
	}

	private DemoHttpCardBalanceProvider provider(
			HttpClient client, java.util.function.Supplier<UUID> lookupIds) {
		return new DemoHttpCardBalanceProvider(
				new DemoBalanceProviderSettings(ENDPOINT, TOKEN),
				objectMapper,
				client,
				lookupIds,
				duration -> { });
	}

	private static HttpResponse<byte[]> success(UUID lookupId, long balance) {
		return response(200, "application/json", """
				{"lookupId":"%s","outcome":"SUCCESS","balanceKrw":%d,"replayed":false}
				""".formatted(lookupId, balance));
	}

	@SuppressWarnings("unchecked")
	private static HttpResponse<byte[]> response(int status, String contentType, String body) {
		HttpResponse<byte[]> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.headers()).thenReturn(HttpHeaders.of(
				Map.of("Content-Type", List.of(contentType)), (name, value) -> true));
		when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
		return response;
	}

	private static byte[] requestBody(HttpRequest request) throws Exception {
		HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
		CompletableFuture<byte[]> body = new CompletableFuture<>();
		publisher.subscribe(new Flow.Subscriber<>() {
			private final ByteArrayOutputStream output = new ByteArrayOutputStream();

			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(ByteBuffer item) {
				byte[] bytes = new byte[item.remaining()];
				item.get(bytes);
				output.writeBytes(bytes);
			}

			@Override
			public void onError(Throwable throwable) {
				body.completeExceptionally(throwable);
			}

			@Override
			public void onComplete() {
				body.complete(output.toByteArray());
			}
		});
		return body.get();
	}

	private static final class RecordingHttpClient extends HttpClient {

		private final java.util.ArrayDeque<Object> responses =
				new java.util.ArrayDeque<>();
		private final java.util.ArrayList<HttpRequest> requests = new java.util.ArrayList<>();

		void enqueue(HttpResponse<byte[]> response) {
			responses.add(response);
		}

		void enqueue(Exception exception) {
			responses.add(exception);
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> HttpResponse<T> send(
				HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
				throws java.io.IOException, InterruptedException {
			requests.add(request);
			Object next = responses.remove();
			if (next instanceof java.io.IOException exception) {
				throw exception;
			}
			if (next instanceof InterruptedException exception) {
				throw exception;
			}
			HttpResponse<byte[]> response = (HttpResponse<byte[]>) next;
			HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(
					new HttpResponse.ResponseInfo() {
						@Override
						public int statusCode() {
							return response.statusCode();
						}

						@Override
						public HttpHeaders headers() {
							return response.headers();
						}

						@Override
						public Version version() {
							return Version.HTTP_2;
						}
					});
			subscriber.onSubscribe(new Flow.Subscription() {
				@Override
				public void request(long count) {
				}

				@Override
				public void cancel() {
				}
			});
			subscriber.onNext(List.of(ByteBuffer.wrap(response.body())));
			subscriber.onComplete();
			try {
				subscriber.getBody().toCompletableFuture().join();
			}
			catch (java.util.concurrent.CompletionException exception) {
				if (exception.getCause() instanceof java.io.IOException ioException) {
					throw ioException;
				}
				throw exception;
			}
			return (HttpResponse<T>) response;
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(
				HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(
				HttpRequest request,
				HttpResponse.BodyHandler<T> responseBodyHandler,
				HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<CookieHandler> cookieHandler() {
			return Optional.empty();
		}

		@Override
		public Optional<Duration> connectTimeout() {
			return Optional.of(Duration.ofSeconds(2));
		}

		@Override
		public Redirect followRedirects() {
			return Redirect.NEVER;
		}

		@Override
		public Optional<ProxySelector> proxy() {
			return Optional.empty();
		}

		@Override
		public SSLContext sslContext() {
			return null;
		}

		@Override
		public SSLParameters sslParameters() {
			return new SSLParameters();
		}

		@Override
		public Optional<Authenticator> authenticator() {
			return Optional.empty();
		}

		@Override
		public Version version() {
			return Version.HTTP_2;
		}

		@Override
		public Optional<Executor> executor() {
			return Optional.empty();
		}
	}

	private record InvalidResponse(String description, String contentType, String body) {
	}
}
