package com.crabit.backend.wish;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WishRepository extends JpaRepository<Wish, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select wish from Wish wish where wish.accountId = :accountId and wish.id in :wishIds order by wish.id")
	List<Wish> lockByAccountIdAndIds(
			@Param("accountId") UUID accountId, @Param("wishIds") Collection<UUID> wishIds);

	List<Wish> findByAccountIdAndDeletedAtIsNullAndStateIn(
			UUID accountId, Collection<WishState> states);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select wish from Wish wish
			where wish.accountId = :accountId
			  and wish.id = :wishId
			  and wish.deletedAt is null
			""")
	java.util.Optional<Wish> lockVisibleByAccountIdAndId(
			@Param("accountId") UUID accountId, @Param("wishId") UUID wishId);

	java.util.Optional<Wish> findByAccountIdAndIdAndDeletedAtIsNull(UUID accountId, UUID wishId);

	List<Wish> findByAccountIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
			UUID accountId, Pageable pageable);

	List<Wish> findByAccountIdAndDeletedAtIsNullAndStateInOrderByCreatedAtDescIdDesc(
			UUID accountId, Collection<WishState> states, Pageable pageable);

	@Query("""
			select wish from Wish wish
			where wish.accountId = :accountId
			  and wish.deletedAt is null
			  and (wish.createdAt < :cursorCreatedAt
			       or (wish.createdAt = :cursorCreatedAt and wish.id < :cursorId))
			order by wish.createdAt desc, wish.id desc
			""")
	List<Wish> findPageAfter(
			@Param("accountId") UUID accountId,
			@Param("cursorCreatedAt") Instant cursorCreatedAt,
			@Param("cursorId") UUID cursorId,
			Pageable pageable);

	@Query("""
			select wish from Wish wish
			where wish.accountId = :accountId
			  and wish.deletedAt is null
			  and wish.state in :states
			  and (wish.createdAt < :cursorCreatedAt
			       or (wish.createdAt = :cursorCreatedAt and wish.id < :cursorId))
			order by wish.createdAt desc, wish.id desc
			""")
	List<Wish> findPageAfterInStates(
			@Param("accountId") UUID accountId,
			@Param("states") Collection<WishState> states,
			@Param("cursorCreatedAt") Instant cursorCreatedAt,
			@Param("cursorId") UUID cursorId,
			Pageable pageable);
}
