package com.crabit.backend.wish;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceObservationRepository extends JpaRepository<BalanceObservation, UUID> {

	Optional<BalanceObservation>
			findFirstByAccountIdAndStatusAndAccountLookupVersionIsNotNullOrderByAccountLookupVersionDesc(
			UUID accountId, BalanceObservationStatus status);
}
