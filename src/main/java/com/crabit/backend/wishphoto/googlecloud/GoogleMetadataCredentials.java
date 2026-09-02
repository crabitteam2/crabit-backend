package com.crabit.backend.wishphoto.googlecloud;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Fixed metadata endpoint only; never invokes ADC or loads a user/key file. */
final class GoogleMetadataCredentials extends GoogleCredentials implements AutoCloseable {
	interface Reader extends AutoCloseable {
		String read(String path) throws IOException;
		@Override default void close() { }
	}
	private final Reader reader;
	GoogleMetadataCredentials(GoogleCloudPhotoSettings settings) throws IOException {
		this(settings, new HttpReader());
	}
	GoogleMetadataCredentials(GoogleCloudPhotoSettings settings, Reader reader) throws IOException {
		this.reader = reader;
		try {
		if (System.getenv("GOOGLE_APPLICATION_CREDENTIALS") != null
				|| System.getenv("GCE_METADATA_HOST") != null || System.getenv("GCE_METADATA_IP") != null) {
			throw new IOException("Unsupported Wish photo credential source");
		}
		if (!settings.projectId().equals(read("project/project-id"))
				|| !settings.projectNumber().equals(read("project/numeric-project-id"))
				|| !settings.serviceAccount().equals(read("instance/service-accounts/default/email"))) {
			throw new IOException("Wish photo attached identity mismatch");
		}
		} catch (IOException | RuntimeException exception) { reader.close(); throw new IOException("Wish photo attached identity unavailable"); }
	}
	@Override public AccessToken refreshAccessToken() throws IOException {
		try {
			var json = JsonParser.parseString(read("instance/service-accounts/default/token")).getAsJsonObject();
			long seconds = json.get("expires_in").getAsLong();
			String token = json.get("access_token").getAsString();
			if (seconds <= 0 || seconds > 3600 || token.isBlank()) throw new IOException();
			return new AccessToken(token, Date.from(Instant.now().plusSeconds(seconds)));
		} catch (RuntimeException exception) { throw new IOException("Wish photo metadata token unavailable"); }
	}
	private String read(String path) throws IOException {
		return reader.read(path);
	}
	@Override public void close() { reader.close(); }
	private static final class HttpReader implements Reader {
		private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
				.followRedirects(HttpClient.Redirect.NEVER).build();
		@Override public void close() { http.close(); }
		@Override public String read(String path) throws IOException {
		try {
			var request = HttpRequest.newBuilder(URI.create("http://metadata.google.internal/computeMetadata/v1/" + path))
					.header("Metadata-Flavor", "Google").timeout(Duration.ofSeconds(2)).GET().build();
			var response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200 || response.body().length() > 16384
					|| !response.headers().firstValue("Metadata-Flavor").orElse("").equals("Google")) throw new IOException();
			return response.body().strip();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt(); throw new IOException("Wish photo metadata unavailable");
		} catch (IOException | RuntimeException exception) { throw new IOException("Wish photo metadata unavailable"); }
	}
  }
}
