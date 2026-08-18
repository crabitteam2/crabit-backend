package com.crabit.backend.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrentPrincipalTest {

	@Test
	void rejectsAnIncompleteAuthenticatedIdentity() {
		UUID subjectId = UUID.randomUUID();
		UUID academyId = UUID.randomUUID();

		assertThatThrownBy(() -> new CurrentPrincipal(
				null, CurrentPrincipal.Role.STUDENT, academyId, "owner"))
				.isInstanceOf(NullPointerException.class)
				.hasMessage("subjectId");
		assertThatThrownBy(() -> new CurrentPrincipal(
				subjectId, null, academyId, "owner"))
				.isInstanceOf(NullPointerException.class)
				.hasMessage("role");
		assertThatThrownBy(() -> new CurrentPrincipal(
				subjectId, CurrentPrincipal.Role.STUDENT, null, "owner"))
				.isInstanceOf(NullPointerException.class)
				.hasMessage("academyId");
		assertThatThrownBy(() -> new CurrentPrincipal(
				subjectId, CurrentPrincipal.Role.STUDENT, academyId, " "))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("personaKey must not be blank");
	}
}
