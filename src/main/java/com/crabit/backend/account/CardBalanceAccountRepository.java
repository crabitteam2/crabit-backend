package com.crabit.backend.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardBalanceAccountRepository extends JpaRepository<CardBalanceAccount, UUID> {

	Optional<CardBalanceAccount> findByIdAndStudentId(UUID id, UUID studentId);

	List<CardBalanceAccount> findByClosedAtIsNullOrderByIdAsc();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from CardBalanceAccount account where account.id = :accountId")
	Optional<CardBalanceAccount> lockById(@Param("accountId") UUID accountId);
}
