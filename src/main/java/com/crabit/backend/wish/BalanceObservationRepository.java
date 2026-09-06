package com.crabit.backend.wish;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BalanceObservationRepository extends JpaRepository<BalanceObservation, UUID> {

	Optional<BalanceObservation>
			findFirstByAccountIdAndAccountLookupVersionIsNotNullOrderByAccountLookupVersionDesc(
			UUID accountId);

	Optional<BalanceObservation>
			findFirstByAccountIdAndStatusAndAccountLookupVersionIsNotNullOrderByAccountLookupVersionDesc(
			UUID accountId, BalanceObservationStatus status);

	/** An unversioned legacy chain still supplies the exact previous successful balance. */
	@Query(value = """
			SELECT observation.* FROM balance_observation observation
			WHERE observation.account_id = :accountId AND observation.status = 'SUCCEEDED'
			  AND observation.account_lookup_version IS NULL
			  AND NOT EXISTS (SELECT 1 FROM balance_observation successor
				  WHERE successor.previous_successful_observation_id = observation.id)
			ORDER BY observation.observed_at DESC, observation.id DESC
			LIMIT 1
			""", nativeQuery = true)
	Optional<BalanceObservation> findLegacySuccessfulChainTip(@Param("accountId") UUID accountId);
}
