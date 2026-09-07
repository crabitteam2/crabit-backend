package com.crabit.backend.recommendation;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "crabit.feed.ranking.enabled", havingValue = "true")
final class FeedRankingSettings {
	private final URI url;
	private final String credential;
	private final String classifierVersion;

	@org.springframework.beans.factory.annotation.Autowired
	FeedRankingSettings(
			@Value("${crabit.feed.ranking.url:}") String url,
			@Value("${crabit.feed.ranking.credential:}") String credential,
			@Value("${crabit.feed.ranking.classifier-version:}") String classifierVersion,
			FeedCategoryClassifier classifier) {
		this(url, credential, classifierVersion);
		if (!classifier.version().equals(classifierVersion))
			throw new IllegalArgumentException("Feed classifier version must match the bundled artifact");
	}

	FeedRankingSettings(
			@Value("${crabit.feed.ranking.url:}") String url,
			@Value("${crabit.feed.ranking.credential:}") String credential,
			@Value("${crabit.feed.ranking.classifier-version:}") String classifierVersion) {
		this.url = validUrl(url);
		this.credential = validCredential(credential);
		if (classifierVersion == null
				|| !classifierVersion.matches("[a-z0-9][a-z0-9._-]*@sha256:[a-f0-9]{64}"))
			throw new IllegalArgumentException("Feed classifier version must include its SHA-256 digest");
		this.classifierVersion = classifierVersion;
	}

	URI url() { return url; }
	String credential() { return credential; }
	String classifierVersion() { return classifierVersion; }

	private static URI validUrl(String value) {
		try {
			URI uri = URI.create(value == null ? "" : value);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
					|| uri.getUserInfo() != null || uri.getFragment() != null
					|| !"/internal/v1/feed-rankings".equals(uri.getPath()) || uri.getQuery() != null)
				throw new IllegalArgumentException();
			return uri;
		} catch (RuntimeException invalid) {
			throw new IllegalArgumentException("Feed ranking URL must be the absolute Python endpoint", invalid);
		}
	}

	private static String validCredential(String value) {
		if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
			throw new IllegalArgumentException("Feed ranking credential must not be blank");
		return value;
	}
}
