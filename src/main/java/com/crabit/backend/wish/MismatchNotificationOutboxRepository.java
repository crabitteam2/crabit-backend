package com.crabit.backend.wish;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MismatchNotificationOutboxRepository
		extends JpaRepository<MismatchNotificationOutbox, UUID> {
}
