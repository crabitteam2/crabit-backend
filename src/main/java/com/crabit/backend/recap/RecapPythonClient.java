package com.crabit.backend.recap;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "crabit.recap.generation.enabled", havingValue = "true")
final class RecapPythonClient {
	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	private final RecapServiceSettings settings; private final ObjectMapper json; private final HttpClient http;
	@Autowired
	RecapPythonClient(RecapServiceSettings settings, ObjectMapper json) {
		this(settings, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build());
	}
	RecapPythonClient(RecapServiceSettings settings, ObjectMapper json, HttpClient http) { this.settings=settings; this.json=json; this.http=http; }
	Result generate(RecapGenerationCoordinator.Claim claim) {
		byte[] body = claim.requestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
		if (body.length > 4_194_304) throw new RecapTransportException("REQUEST_TOO_LARGE", false);
		HttpRequest request = HttpRequest.newBuilder(settings.url()).timeout(TIMEOUT)
				.header("Authorization", "Bearer " + settings.credential()).header("Content-Type", "application/json")
				.header("Idempotency-Key", claim.id().toString()).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
		var future = http.sendAsync(request, info -> new BoundedSubscriber(1_048_576));
		try {
			HttpResponse<byte[]> response = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			if (response.statusCode() != 200) throw new RecapTransportException("HTTP_" + response.statusCode(), response.statusCode() >= 500);
			MediaType type = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElse(""));
			if (!type.isCompatibleWith(MediaType.APPLICATION_JSON)) throw new RecapTransportException("INVALID_CONTENT_TYPE", false);
			@SuppressWarnings("unchecked") Map<String,Object> root = json.readValue(response.body(), Map.class);
			validateEcho(root, claim);
			return new Result(json.writeValueAsString(root.get("view")), json.writeValueAsString(root.get("internal_metrics")));
		} catch (TimeoutException e) { future.cancel(true); throw new RecapTransportException("TIMEOUT", true); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); future.cancel(true); throw new RecapTransportException("INTERRUPTED", true); }
		catch (ExecutionException e) { throw mapExecutionFailure(e); }
		catch (JacksonException | IllegalArgumentException e) { throw new RecapTransportException("INVALID_RESPONSE", false); }
	}
	static RecapTransportException mapExecutionFailure(ExecutionException failure) {
		Throwable cause = failure.getCause();
		while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
		if (cause instanceof RecapTransportException transport) return transport;
		if (cause instanceof java.net.http.HttpTimeoutException) return new RecapTransportException("TIMEOUT", true);
		if (cause instanceof java.io.IOException) return new RecapTransportException("UNAVAILABLE", true);
		return new RecapTransportException("UNAVAILABLE", false);
	}
	private static void validateEcho(Map<String,Object> root, RecapGenerationCoordinator.Claim c) {
		if (!Objects.equals(String.valueOf(root.get("generation_id")), c.id().toString())
				|| !Objects.equals(root.get("input_digest"), c.inputDigest())
				|| !Objects.equals(String.valueOf(root.get("student_id")), c.studentId().toString())
				|| !Objects.equals(String.valueOf(root.get("card_balance_account_id")), c.accountId().toString())
				|| !Objects.equals(String.valueOf(root.get("academy_id")), c.academyId().toString())
				|| !Objects.equals(root.get("kind"), c.kind().name()) || !Objects.equals(root.get("schema_version"), 1)
				|| !Objects.equals(root.get("algorithm_version"), "recap-1") || root.get("view") == null || root.get("internal_metrics") == null)
			throw new RecapTransportException("IDENTITY_MISMATCH", false);
	}
	record Result(String viewJson, String internalMetricsJson) {}
	private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
		private final int max; private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
		private final CompletableFuture<byte[]> body=new CompletableFuture<>(); private Flow.Subscription subscription;
		BoundedSubscriber(int max){this.max=max;} public java.util.concurrent.CompletionStage<byte[]> getBody(){return body;}
		public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);} public void onNext(List<ByteBuffer> buffers){for(var b:buffers){if(b.remaining()>max-bytes.size()){subscription.cancel();body.completeExceptionally(new RecapTransportException("RESPONSE_TOO_LARGE",false));return;}byte[] c=new byte[b.remaining()];b.get(c);bytes.writeBytes(c);}subscription.request(1);} public void onError(Throwable e){body.completeExceptionally(e);} public void onComplete(){body.complete(bytes.toByteArray());}
	}
}
