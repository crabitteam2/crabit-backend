package com.crabit.backend.relationship;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FriendRequestRepository extends JpaRepository<FriendRequest, UUID> {

	@Query("select request.academyId as academyId, request.senderId as senderId, "
			+ "request.receiverId as receiverId from FriendRequest request where request.id = :id")
	Optional<Identity> findIdentityById(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from FriendRequest request where request.academyId = :academyId and request.studentLowId = :low and request.studentHighId = :high and request.status = com.crabit.backend.relationship.FriendRequestStatus.PENDING")
	Optional<FriendRequest> lockPendingByAcademyAndPair(
			@Param("academyId") UUID academyId, @Param("low") UUID low, @Param("high") UUID high);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from FriendRequest request where request.id = :id")
	Optional<FriendRequest> lockById(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from FriendRequest request where request.studentLowId = :low and request.studentHighId = :high and request.status = com.crabit.backend.relationship.FriendRequestStatus.PENDING order by request.academyId, request.id")
	List<FriendRequest> lockAllPendingByPair(@Param("low") UUID low, @Param("high") UUID high);

	interface Identity {
		UUID getAcademyId();
		UUID getSenderId();
		UUID getReceiverId();
	}
}
