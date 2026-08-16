package com.crabit.backend.e2e;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
public final class SeedFixtureCatalog {

	public static final UUID PRIMARY_ACADEMY_ID = id("00000000-0000-0000-0000-000000000101");
	public static final UUID OTHER_ACADEMY_ID = id("00000000-0000-0000-0000-000000000102");
	public static final UUID OWNER_ID = id("00000000-0000-0000-0000-000000000201");
	public static final UUID FRIEND_ID = id("00000000-0000-0000-0000-000000000202");
	public static final UUID NONFRIEND_ID = id("00000000-0000-0000-0000-000000000203");
	public static final UUID BLOCKED_ID = id("00000000-0000-0000-0000-000000000204");
	public static final UUID OTHER_ACADEMY_STUDENT_ID = id("00000000-0000-0000-0000-000000000205");
	public static final UUID STAFF_ID = id("00000000-0000-0000-0000-000000000206");
	public static final UUID OWNER_ACCOUNT_ID = id("00000000-0000-0000-0000-000000000301");
	public static final UUID LAPTOP_WISH_ID = id("00000000-0000-0000-0000-000000000401");
	public static final UUID CAMP_WISH_ID = id("00000000-0000-0000-0000-000000000402");

	public static final String OWNER_TOKEN = "seed-owner-token";
	public static final String FRIEND_TOKEN = "seed-friend-token";
	public static final String NONFRIEND_TOKEN = "seed-nonfriend-token";
	public static final String BLOCKED_TOKEN = "seed-blocked-token";
	public static final String OTHER_ACADEMY_TOKEN = "seed-other-academy-token";
	public static final String STAFF_TOKEN = "seed-staff-token";

	public static final Instant FIXTURE_TIME = Instant.parse("2026-08-16T00:00:00Z");

	public List<Persona> personas() {
		return List.of(
				new Persona(OWNER_ID, "owner", "오너", SeedPrincipal.Role.STUDENT,
						PRIMARY_ACADEMY_ID, OWNER_TOKEN, true),
				new Persona(FRIEND_ID, "same-academy-friend", "친구", SeedPrincipal.Role.STUDENT,
						PRIMARY_ACADEMY_ID, FRIEND_TOKEN, true),
				new Persona(NONFRIEND_ID, "same-academy-nonfriend", "같은 학원 학생", SeedPrincipal.Role.STUDENT,
						PRIMARY_ACADEMY_ID, NONFRIEND_TOKEN, true),
				new Persona(BLOCKED_ID, "blocked-student", "차단 학생", SeedPrincipal.Role.STUDENT,
						PRIMARY_ACADEMY_ID, BLOCKED_TOKEN, true),
				new Persona(OTHER_ACADEMY_STUDENT_ID, "other-academy-student", "다른 학원 학생",
						SeedPrincipal.Role.STUDENT, OTHER_ACADEMY_ID, OTHER_ACADEMY_TOKEN, true),
				new Persona(STAFF_ID, "same-academy-staff", "같은 학원 선생님", SeedPrincipal.Role.STAFF,
						PRIMARY_ACADEMY_ID, STAFF_TOKEN, false));
	}

	public List<WishFixture> wishes() {
		return List.of(
				new WishFixture(LAPTOP_WISH_ID, "노트북", 1_500_000L, 250_000L,
						"IN_PROGRESS", "FRIENDS", LocalDate.of(2026, 12, 31)),
				new WishFixture(CAMP_WISH_ID, "여름 캠프", 500_000L, 500_000L,
						"AMOUNT_REACHED", "ACADEMY", LocalDate.of(2026, 9, 1)));
	}

	private static UUID id(String value) {
		return UUID.fromString(value);
	}

	public record Persona(
			UUID id,
			String key,
			String displayName,
			SeedPrincipal.Role role,
			UUID academyId,
			String token,
			boolean persistedStudent) {
	}

	public record WishFixture(
			UUID id,
			String purpose,
			long targetAmount,
			long wishAmount,
			String state,
			String visibility,
			LocalDate targetDate) {
	}
}
