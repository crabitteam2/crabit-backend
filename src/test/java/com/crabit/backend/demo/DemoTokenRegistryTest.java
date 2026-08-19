package com.crabit.backend.demo;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.auth.CurrentPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DemoTokenRegistryTest {

	private static final List<String> TOKENS = List.of(
			"demo-owner-secret", "demo-friend-secret", "demo-nonfriend-secret",
			"demo-blocked-secret", "demo-other-academy-secret", "demo-staff-secret");

	@Test
	void resolvesExactlyTheSixStableDemoPersonas() {
		DemoTokenRegistry registry = registry(TOKENS);

		assertThat(registry.all()).hasSize(6);
		assertPrincipal(registry, 0, "00000000-0000-0000-0000-000000000201",
				CurrentPrincipal.Role.STUDENT, "00000000-0000-0000-0000-000000000101", "owner");
		assertPrincipal(registry, 1, "00000000-0000-0000-0000-000000000202",
				CurrentPrincipal.Role.STUDENT, "00000000-0000-0000-0000-000000000101",
				"same-academy-friend");
		assertPrincipal(registry, 2, "00000000-0000-0000-0000-000000000203",
				CurrentPrincipal.Role.STUDENT, "00000000-0000-0000-0000-000000000101",
				"same-academy-nonfriend");
		assertPrincipal(registry, 3, "00000000-0000-0000-0000-000000000204",
				CurrentPrincipal.Role.STUDENT, "00000000-0000-0000-0000-000000000101",
				"blocked-student");
		assertPrincipal(registry, 4, "00000000-0000-0000-0000-000000000205",
				CurrentPrincipal.Role.STUDENT, "00000000-0000-0000-0000-000000000102",
				"other-academy-student");
		assertPrincipal(registry, 5, "00000000-0000-0000-0000-000000000206",
				CurrentPrincipal.Role.STAFF, "00000000-0000-0000-0000-000000000101",
				"same-academy-staff");
		assertThat(registry.resolve("unknown-demo-token")).isEmpty();
	}

	@Test
	void failsClosedWithoutDisclosingMissingMalformedOrDuplicateCredentials() {
		assertThatThrownBy(() -> registry(replace(0, " ")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(DemoTokenRegistry.OWNER_ENV)
				.hasMessageNotContaining(TOKENS.get(1));

		String malformedSecret = "private-secret with-space";
		assertThatThrownBy(() -> registry(replace(0, malformedSecret)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(DemoTokenRegistry.OWNER_ENV)
				.hasMessageNotContaining(malformedSecret);

		String duplicateSecret = TOKENS.get(0);
		assertThatThrownBy(() -> registry(replace(1, duplicateSecret)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(DemoTokenRegistry.FRIEND_ENV)
				.hasMessageNotContaining(duplicateSecret);
	}

	@Test
	void refusesACommittedE2eCredentialWithoutEchoingIt() {
		assertThatThrownBy(() -> registry(replace(0, OWNER_TOKEN)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(DemoTokenRegistry.OWNER_ENV)
				.hasMessageContaining("must not reuse a committed E2E seed token")
				.hasMessageNotContaining(OWNER_TOKEN);
	}

	private static void assertPrincipal(
			DemoTokenRegistry registry,
			int tokenIndex,
			String subjectId,
			CurrentPrincipal.Role role,
			String academyId,
			String personaKey) {
		assertThat(registry.resolve(TOKENS.get(tokenIndex))).get()
				.extracting(CurrentPrincipal::subjectId, CurrentPrincipal::role,
						CurrentPrincipal::academyId, CurrentPrincipal::personaKey)
				.containsExactly(UUID.fromString(subjectId), role, UUID.fromString(academyId), personaKey);
	}

	private static List<String> replace(int index, String value) {
		java.util.ArrayList<String> replaced = new java.util.ArrayList<>(TOKENS);
		replaced.set(index, value);
		return replaced;
	}

	static DemoTokenRegistry registry(List<String> tokens) {
		return new DemoTokenRegistry(
				tokens.get(0), tokens.get(1), tokens.get(2),
				tokens.get(3), tokens.get(4), tokens.get(5));
	}

	static List<String> tokens() {
		return TOKENS;
	}
}
