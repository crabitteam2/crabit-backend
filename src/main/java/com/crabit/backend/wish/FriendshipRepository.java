package com.crabit.backend.wish;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FriendshipRepository extends JpaRepository<Friendship, UUID> {

	boolean existsByAcademyIdAndStudentLowIdAndStudentHighIdAndEndedAtIsNull(
			UUID academyId, UUID studentLowId, UUID studentHighId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select friendship from Friendship friendship where friendship.academyId = :academyId and friendship.studentLowId = :studentLowId and friendship.studentHighId = :studentHighId and friendship.endedAt is null")
	Optional<Friendship> lockCurrentByAcademyAndPair(
			@Param("academyId") UUID academyId,
			@Param("studentLowId") UUID studentLowId,
			@Param("studentHighId") UUID studentHighId);
}
