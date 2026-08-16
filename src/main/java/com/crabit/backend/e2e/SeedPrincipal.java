package com.crabit.backend.e2e;

import java.util.UUID;

public record SeedPrincipal(UUID subjectId, Role role, UUID academyId, String personaKey) {

	public static final String REQUEST_ATTRIBUTE = SeedPrincipal.class.getName();

	public enum Role {
		STUDENT,
		STAFF
	}
}
