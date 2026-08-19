package com.crabit.backend.wish;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface LedgerEventRepository
		extends Repository<LedgerEvent, UUID>, LedgerEventAppender {

	boolean existsByDepositBalanceObservationId(UUID depositBalanceObservationId);

	Optional<LedgerEvent> findById(UUID id);

	List<LedgerEvent> findAll();
}
