package com.crabit.backend.balance;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo & !e2e")
final class DemoBalanceProviderSettings {

	static final String URL_ENV = "CRABIT_DEMO_BALANCE_PROVIDER_URL";
	static final String TOKEN_ENV = "CRABIT_DEMO_BALANCE_PROVIDER_TOKEN";
	static final String LOOKUP_PATH = "/api/provider/balance-lookups";

	private final URI endpoint;
	private final String token;

	DemoBalanceProviderSettings(
			@Value("${crabit.demo.balance-provider.url:}") String endpoint,
			@Value("${crabit.demo.balance-provider.token:}") String token) {
		this.endpoint = endpoint(endpoint);
		this.token = token(token);
	}

	URI endpoint() {
		return endpoint;
	}

	String token() {
		return token;
	}

	private static URI endpoint(String configured) {
		if (configured == null || configured.isBlank()) {
			throw invalid(URL_ENV, "is required and must not be blank");
		}
		URI parsed;
		try {
			parsed = URI.create(configured);
		}
		catch (IllegalArgumentException exception) {
			throw invalid(URL_ENV, "must be an absolute HTTPS URI");
		}
		if (!parsed.isAbsolute()
				|| !"https".equalsIgnoreCase(parsed.getScheme())
				|| parsed.getHost() == null
				|| parsed.getHost().isBlank()
				|| parsed.getRawUserInfo() != null
				|| parsed.getRawQuery() != null
				|| parsed.getRawFragment() != null
				|| !LOOKUP_PATH.equals(parsed.getRawPath())) {
			throw invalid(URL_ENV,
					"must target the exact HTTPS /api/provider/balance-lookups endpoint");
		}
		return parsed;
	}

	private static String token(String configured) {
		if (configured == null
				|| configured.length() < 32
				|| configured.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
			throw invalid(TOKEN_ENV,
					"must be at least 32 visible-ASCII characters without whitespace");
		}
		return configured;
	}

	private static IllegalStateException invalid(String field, String reason) {
		return new IllegalStateException("Invalid " + field + ": " + reason);
	}
}
