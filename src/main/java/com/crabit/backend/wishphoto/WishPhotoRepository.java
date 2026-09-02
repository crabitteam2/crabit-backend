package com.crabit.backend.wishphoto;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WishPhotoRepository extends JpaRepository<WishPhoto, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select photo from WishPhoto photo where photo.id = :id")
	Optional<WishPhoto> lockById(@Param("id") UUID id);

	Optional<WishPhoto> findByAttachedWishIdAndState(UUID wishId, WishPhotoState state);
	long countByOwnerStudentIdAndStateAndExpiresAtAfter(UUID ownerId, WishPhotoState state, Instant now);
}
