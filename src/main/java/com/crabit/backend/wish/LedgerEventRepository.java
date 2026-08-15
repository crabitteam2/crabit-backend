package com.crabit.backend.wish;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEventRepository extends JpaRepository<LedgerEvent, UUID> {

	boolean existsByDepositBalanceObservationId(UUID depositBalanceObservationId);
}
