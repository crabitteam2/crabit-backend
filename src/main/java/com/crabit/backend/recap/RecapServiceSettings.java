package com.crabit.backend.recap;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "crabit.recap.generation.enabled", havingValue = "true")
final class RecapServiceSettings {
	private final URI url; private final String credential;
	RecapServiceSettings(@Value("${crabit.recap.generation.url:}") String url,
			@Value("${crabit.recap.generation.credential:}") String credential) {
		try { this.url = URI.create(url); } catch (RuntimeException e) { throw new IllegalArgumentException("Recap service URL is invalid", e); }
		String scheme = this.url.getScheme() == null ? "" : this.url.getScheme().toLowerCase(Locale.ROOT);
		if (!(scheme.equals("http") || scheme.equals("https")) || this.url.getHost() == null
				|| this.url.getUserInfo() != null || this.url.getFragment() != null)
			throw new IllegalArgumentException("Recap service URL must be an absolute HTTP URL");
		if (credential == null || credential.isBlank() || credential.indexOf('\r') >= 0 || credential.indexOf('\n') >= 0)
			throw new IllegalArgumentException("Recap service credential must not be blank");
		this.credential = credential;
	}
	URI url() { return url; } String credential() { return credential; }
}
