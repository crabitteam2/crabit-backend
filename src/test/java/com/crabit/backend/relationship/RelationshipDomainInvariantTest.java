package com.crabit.backend.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.account.AcademyMembership;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelationshipDomainInvariantTest {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private final UUID academyId = UUID.randomUUID();

	@Test
	void friendshipIsAcademyScopedAndCannotGrantAccessInAnotherAcademy() {
		UUID firstStudentId = UUID.randomUUID();
		UUID secondStudentId = UUID.randomUUID();
		AcademyMembership first = new AcademyMembership(firstStudentId, academyId, NOW);
		AcademyMembership second = new AcademyMembership(secondStudentId, academyId, NOW);
		Friendship friendship = new Friendship(first, second, NOW);

		assertThat(friendship.matches(firstStudentId, secondStudentId, academyId)).isTrue();
		assertThat(friendship.matches(
				firstStudentId, secondStudentId, UUID.randomUUID())).isFalse();
	}

	@Test
	void friendshipRejectsMembershipsFromDifferentAcademiesOrEndedMemberships() {
		AcademyMembership first = new AcademyMembership(UUID.randomUUID(), academyId, NOW);
		AcademyMembership foreign = new AcademyMembership(UUID.randomUUID(), UUID.randomUUID(), NOW);
		assertThatThrownBy(() -> new Friendship(first, foreign, NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("academy");

		AcademyMembership ended = new AcademyMembership(UUID.randomUUID(), academyId, NOW);
		ended.leave(NOW.plusSeconds(1));
		assertThatThrownBy(() -> new Friendship(first, ended, NOW.plusSeconds(2)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("current");
	}
}
