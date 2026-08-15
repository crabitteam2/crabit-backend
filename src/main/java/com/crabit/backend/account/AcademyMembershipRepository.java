package com.crabit.backend.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademyMembershipRepository extends JpaRepository<AcademyMembership, UUID> {

	boolean existsByStudentIdAndAcademyIdAndLeftAtIsNull(UUID studentId, UUID academyId);

	Optional<AcademyMembership> findByStudentIdAndAcademyIdAndLeftAtIsNull(
			UUID studentId, UUID academyId);
}
