package com.crabit.backend.wish;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentBlockRepository extends JpaRepository<StudentBlock, UUID> {

	boolean existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(UUID blockerId, UUID blockedId);
}
