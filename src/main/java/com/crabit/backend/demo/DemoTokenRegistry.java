package com.crabit.backend.demo;

import static com.crabit.backend.e2e.SeedFixtureCatalog.BLOCKED_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.STAFF_TOKEN;

import com.crabit.backend.auth.CurrentPrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo & !e2e")
public final class DemoTokenRegistry {

	static final String OWNER_ENV = "CRABIT_DEMO_TOKEN_OWNER";
	static final String FRIEND_ENV = "CRABIT_DEMO_TOKEN_FRIEND";
	static final String NONFRIEND_ENV = "CRABIT_DEMO_TOKEN_NONFRIEND";
	static final String BLOCKED_ENV = "CRABIT_DEMO_TOKEN_BLOCKED";
	static final String OTHER_ACADEMY_ENV = "CRABIT_DEMO_TOKEN_OTHER_ACADEMY";
	static final String STAFF_ENV = "CRABIT_DEMO_TOKEN_STAFF";

	private static final UUID PRIMARY_ACADEMY_ID = id("00000000-0000-0000-0000-000000000101");
	private static final UUID OTHER_ACADEMY_ID = id("00000000-0000-0000-0000-000000000102");
	private static final Set<String> COMMITTED_E2E_TOKENS = Set.of(
			OWNER_TOKEN, FRIEND_TOKEN, NONFRIEND_TOKEN, BLOCKED_TOKEN,
			OTHER_ACADEMY_TOKEN, STAFF_TOKEN);

	private final Map<String, CurrentPrincipal> principalsByToken;

	public DemoTokenRegistry(
			@Value("${crabit.demo.token.owner:}") String ownerToken,
			@Value("${crabit.demo.token.friend:}") String friendToken,
			@Value("${crabit.demo.token.nonfriend:}") String nonfriendToken,
			@Value("${crabit.demo.token.blocked:}") String blockedToken,
			@Value("${crabit.demo.token.other-academy:}") String otherAcademyToken,
			@Value("${crabit.demo.token.staff:}") String staffToken) {
		LinkedHashMap<String, CurrentPrincipal> configured = new LinkedHashMap<>();
		register(configured, OWNER_ENV, ownerToken,
				student("00000000-0000-0000-0000-000000000201", "owner", PRIMARY_ACADEMY_ID));
		register(configured, FRIEND_ENV, friendToken,
				student("00000000-0000-0000-0000-000000000202",
						"same-academy-friend", PRIMARY_ACADEMY_ID));
		register(configured, NONFRIEND_ENV, nonfriendToken,
				student("00000000-0000-0000-0000-000000000203",
						"same-academy-nonfriend", PRIMARY_ACADEMY_ID));
		register(configured, BLOCKED_ENV, blockedToken,
				student("00000000-0000-0000-0000-000000000204",
						"blocked-student", PRIMARY_ACADEMY_ID));
		register(configured, OTHER_ACADEMY_ENV, otherAcademyToken,
				student("00000000-0000-0000-0000-000000000205",
						"other-academy-student", OTHER_ACADEMY_ID));
		register(configured, STAFF_ENV, staffToken,
				new CurrentPrincipal(
						id("00000000-0000-0000-0000-000000000206"),
						CurrentPrincipal.Role.STAFF,
						PRIMARY_ACADEMY_ID,
						"same-academy-staff"));
		this.principalsByToken = Map.copyOf(configured);
	}

	public Optional<CurrentPrincipal> resolve(String token) {
		return Optional.ofNullable(principalsByToken.get(token));
	}

	public Map<String, CurrentPrincipal> all() {
		return principalsByToken;
	}

	private static void register(
			Map<String, CurrentPrincipal> configured,
			String field,
			String token,
			CurrentPrincipal principal) {
		validate(field, token);
		if (configured.putIfAbsent(token, principal) != null) {
			throw invalid(field, "must not duplicate another demo token");
		}
	}

	private static void validate(String field, String token) {
		if (token == null || token.isBlank()) {
			throw invalid(field, "is required and must not be blank");
		}
		if (token.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
			throw invalid(field, "must be an opaque visible-ASCII credential without whitespace");
		}
		if (COMMITTED_E2E_TOKENS.contains(token)) {
			throw invalid(field, "must not reuse a committed E2E seed token");
		}
	}

	private static IllegalStateException invalid(String field, String reason) {
		return new IllegalStateException("Invalid " + field + ": " + reason);
	}

	private static CurrentPrincipal student(String id, String key, UUID academyId) {
		return new CurrentPrincipal(
				DemoTokenRegistry.id(id), CurrentPrincipal.Role.STUDENT, academyId, key);
	}

	private static UUID id(String value) {
		return UUID.fromString(value);
	}
}
