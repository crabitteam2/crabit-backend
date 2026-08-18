package com.crabit.backend.auth;

import java.util.Objects;
import java.util.UUID;

public record CurrentPrincipal(UUID subjectId, Role role, UUID academyId, String personaKey) {

	public static final String REQUEST_ATTRIBUTE = CurrentPrincipal.class.getName();

	public CurrentPrincipal {
		Objects.requireNonNull(subjectId, "subjectId");
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(academyId, "academyId");
		Objects.requireNonNull(personaKey, "personaKey");
		if (personaKey.isBlank()) {
			throw new IllegalArgumentException("personaKey must not be blank");
		}
	}

	public enum Role {
		STUDENT,
		STAFF
	}
}
