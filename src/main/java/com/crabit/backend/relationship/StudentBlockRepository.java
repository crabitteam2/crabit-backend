package com.crabit.backend.relationship;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentBlockRepository extends JpaRepository<StudentBlock, UUID> {

	boolean existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(UUID blockerId, UUID blockedId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select block from StudentBlock block where block.blockerId = :blockerId and block.blockedId = :blockedId")
	Optional<StudentBlock> lockByBlockerIdAndBlockedId(
			@Param("blockerId") UUID blockerId, @Param("blockedId") UUID blockedId);

	@Query("select block from StudentBlock block where block.blockerId = :blockerId and block.releasedAt is null order by block.blockedAt desc, block.blockedId desc")
	List<StudentBlock> findAllCurrentByBlockerId(@Param("blockerId") UUID blockerId);
}
