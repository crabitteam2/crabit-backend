package com.crabit.backend.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AcademyMembershipRepository extends JpaRepository<AcademyMembership, UUID> {

	boolean existsByStudentIdAndAcademyIdAndLeftAtIsNull(UUID studentId, UUID academyId);

	Optional<AcademyMembership> findByStudentIdAndAcademyIdAndLeftAtIsNull(
			UUID studentId, UUID academyId);

	@Query("select membership from AcademyMembership membership where membership.academyId = :academyId and membership.leftAt is null")
	java.util.List<AcademyMembership> findAllCurrentByAcademyId(@Param("academyId") UUID academyId);
}
