package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RecapPythonClientTest {
	@Test void preservesNonretryableProtocolFailuresWrappedByTheHttpFuture() {
		var mapped = RecapPythonClient.mapExecutionFailure(new ExecutionException(
				new CompletionException(new RecapTransportException("RESPONSE_TOO_LARGE", false))));
		assertThat(mapped.code()).isEqualTo("RESPONSE_TOO_LARGE");
		assertThat(mapped.retryable()).isFalse();
	}

	@Test void retriesOnlyTransportIoFailures() {
		assertThat(RecapPythonClient.mapExecutionFailure(new ExecutionException(new IOException("reset"))).retryable()).isTrue();
		assertThat(RecapPythonClient.mapExecutionFailure(new ExecutionException(new IllegalStateException("bug"))).retryable()).isFalse();
	}

	@Test void realHttpTransportKeepsAnOversizedResponseNonretryable() throws IOException {
		byte[] oversized = new byte[1_048_577];
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/generate", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, oversized.length);
			try (var body = exchange.getResponseBody()) { body.write(oversized); }
		});
		server.start();
		try {
			var client = new RecapPythonClient(new RecapServiceSettings(
					"http://127.0.0.1:" + server.getAddress().getPort() + "/generate", "test-token"), new ObjectMapper());
			var claim = new RecapGenerationCoordinator.Claim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
					UUID.randomUUID(), RecapKind.WEEKLY, "sha256:x", "{}", 1);
			Throwable thrown = catchThrowable(() -> client.generate(claim));
			assertThat(thrown).isInstanceOf(RecapTransportException.class);
			assertThat(((RecapTransportException) thrown).code()).isEqualTo("RESPONSE_TOO_LARGE");
			assertThat(((RecapTransportException) thrown).retryable()).isFalse();
		} finally { server.stop(0); }
	}
}
