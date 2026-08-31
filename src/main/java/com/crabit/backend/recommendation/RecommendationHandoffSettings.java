package com.crabit.backend.recommendation;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
		name = "crabit.recommendation.handoff.enabled", havingValue = "true")
final class RecommendationHandoffSettings {

	private final URI receiverUrl;
	private final String triggerCredential;
	private final String receiverCredential;

	RecommendationHandoffSettings(
			@Value("${crabit.recommendation.handoff.receiver-url:}") String receiverUrl,
			@Value("${crabit.recommendation.handoff.trigger-credential:}") String triggerCredential,
			@Value("${crabit.recommendation.handoff.receiver-credential:}") String receiverCredential) {
		this.receiverUrl = validHttpUri(receiverUrl);
		this.triggerCredential = validCredential(triggerCredential, "trigger");
		this.receiverCredential = validCredential(receiverCredential, "receiver");
		if (this.triggerCredential.equals(this.receiverCredential)) {
			throw new IllegalArgumentException(
					"Recommendation trigger and receiver credentials must be distinct");
		}
	}

	URI receiverUrl() {
		return receiverUrl;
	}

	String triggerCredential() {
		return triggerCredential;
	}

	String receiverCredential() {
		return receiverCredential;
	}

	private static URI validHttpUri(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Recommendation receiver URL must not be blank");
		}
		try {
			URI uri = URI.create(value);
			String scheme = Objects.requireNonNullElse(uri.getScheme(), "")
					.toLowerCase(Locale.ROOT);
			if (!(scheme.equals("http") || scheme.equals("https"))
					|| uri.getHost() == null || uri.getHost().isBlank()
					|| uri.getUserInfo() != null || uri.getFragment() != null) {
				throw new IllegalArgumentException(
						"Recommendation receiver URL must be an absolute HTTP URL");
			}
			return uri;
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(
					"Recommendation receiver URL must be an absolute HTTP URL", exception);
		}
	}

	private static String validCredential(String value, String name) {
		if (value == null || value.isBlank()
				|| value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
			throw new IllegalArgumentException(
					"Recommendation " + name + " credential must not be blank");
		}
		return value;
	}
}
