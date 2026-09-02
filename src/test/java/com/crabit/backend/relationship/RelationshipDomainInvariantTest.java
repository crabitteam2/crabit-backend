package com.crabit.backend.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.account.AcademyMembership;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class RelationshipDomainInvariantTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private final UUID academyId = UUID.randomUUID();

    @Test
    void studentFollowIsAcademyScopedAndCannotGrantAccessInAnotherAcademy() {
        UUID firstStudentId = UUID.randomUUID();
        UUID secondStudentId = UUID.randomUUID();
        AcademyMembership first = new AcademyMembership(firstStudentId, academyId, NOW);
        AcademyMembership second = new AcademyMembership(secondStudentId, academyId, NOW);
        StudentFollow studentFollow = new StudentFollow(first, second, NOW);

        assertThat(studentFollow.matches(secondStudentId, firstStudentId, academyId)).isTrue();
        assertThat(studentFollow.matches(firstStudentId, secondStudentId, UUID.randomUUID()))
                .isFalse();
    }

    @Test
    void studentFollowRejectsMembershipsFromDifferentAcademiesOrEndedMemberships() {
        AcademyMembership first = new AcademyMembership(UUID.randomUUID(), academyId, NOW);
        AcademyMembership foreign =
                new AcademyMembership(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> new StudentFollow(first, foreign, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("academy");

        AcademyMembership ended = new AcademyMembership(UUID.randomUUID(), academyId, NOW);
        ended.leave(NOW.plusSeconds(1));
        assertThatThrownBy(() -> new StudentFollow(first, ended, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current");
    }
}
