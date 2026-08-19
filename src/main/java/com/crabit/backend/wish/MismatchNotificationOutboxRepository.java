package com.crabit.backend.wish;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MismatchNotificationOutboxRepository
		extends JpaRepository<MismatchNotificationOutbox, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select outbox from MismatchNotificationOutbox outbox "
			+ "where outbox.publishedAt is null order by outbox.createdAt, outbox.id")
	List<MismatchNotificationOutbox> lockUnpublished(Pageable pageable);
}
