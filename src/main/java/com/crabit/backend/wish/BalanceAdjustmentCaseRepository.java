package com.crabit.backend.wish;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BalanceAdjustmentCaseRepository
		extends JpaRepository<BalanceAdjustmentCase, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select adjustment from BalanceAdjustmentCase adjustment where adjustment.accountId = :accountId and adjustment.status = com.crabit.backend.wish.BalanceAdjustmentStatus.OPEN")
	List<BalanceAdjustmentCase> lockOpenByAccountId(@Param("accountId") UUID accountId);

	default Optional<BalanceAdjustmentCase> lockSingleOpenByAccountId(UUID accountId) {
		List<BalanceAdjustmentCase> openCases = lockOpenByAccountId(accountId);
		if (openCases.size() > 1) {
			throw new IllegalStateException("Account has multiple open adjustment cases");
		}
		return openCases.stream().findFirst();
	}
}
