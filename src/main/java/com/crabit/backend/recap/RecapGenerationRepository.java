package com.crabit.backend.recap;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecapGenerationRepository extends JpaRepository<RecapGeneration, UUID> {
	Optional<RecapGeneration> findFirstByAccountIdAndKindAndPeriodStartAndPeriodEndExclusiveAndCurrentVersionTrueOrderByGenerationVersionDesc(
			UUID accountId, RecapKind kind, LocalDate start, LocalDate end);
	Optional<RecapGeneration> findFirstByAccountIdAndKindAndPeriodStartAndPeriodEndExclusiveOrderByGenerationVersionDesc(
			UUID accountId, RecapKind kind, LocalDate start, LocalDate end);
	Optional<RecapGeneration> findByIdAndInputDigest(UUID id, String inputDigest);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from RecapGeneration g where g.id=:id and g.inputDigest=:inputDigest")
	Optional<RecapGeneration> lockByIdAndInputDigest(@Param("id") UUID id, @Param("inputDigest") String inputDigest);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from RecapGeneration g where g.accountId=:accountId and g.kind=:kind and g.periodStart=:start and g.periodEndExclusive=:end order by g.generationVersion desc")
	List<RecapGeneration> lockLogical(@Param("accountId") UUID accountId, @Param("kind") RecapKind kind,
			@Param("start") LocalDate start, @Param("end") LocalDate end);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from RecapGeneration g where (g.state='PENDING' or (g.state='FAILED' and g.nextAttemptAt<=:now) or (g.state='RUNNING' and g.startedAt<=:staleBefore)) order by g.createdAt")
	List<RecapGeneration> lockReady(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
