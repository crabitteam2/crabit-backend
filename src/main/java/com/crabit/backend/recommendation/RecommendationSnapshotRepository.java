package com.crabit.backend.recommendation;

import com.crabit.backend.wish.SharedCardQueryRepository;
import com.crabit.backend.wish.WishState;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

interface RecommendationSnapshotRepository {

	Instant transactionTimestamp();

	Optional<AccountRow> findActiveAccount(UUID accountId);

	List<WishRow> findViewerWishes(UUID accountId, int requestedRows);

	List<SharedCardQueryRepository.Row> findCandidates(
			UUID viewerId, UUID academyId, int requestedRows);

	Map<UUID, SavingsRow> summarizeSavings(Collection<UUID> wishIds);

	List<RecommendationPeriodSavings.Row> periodSavings(UUID accountId, Instant start, Instant end);

	List<SharedCardQueryRepository.Row> findCompletedCandidates(
			UUID viewerId, UUID academyId, Instant start, Instant end, int limit);

	List<SharedCardQueryRepository.Row> findInterestCandidates(
			UUID viewerId, UUID academyId, Collection<UUID> wishIds, int limit);

	Map<UUID, String> findOwnTitles(UUID accountId, Collection<UUID> wishIds);

	Map<UUID, String> findVisibleTitles(UUID viewerId, UUID academyId, Collection<UUID> wishIds);

	void validateRepresentative(UUID accountId);

	record AccountRow(
			UUID accountId,
			UUID studentId,
			UUID academyId,
			Instant openedAt,
			String studentName,
			int studentAge,
			String academyName) {}

	record WishRow(
			UUID wishId,
			UUID accountId,
			UUID academyId,
			String title,
			long targetAmount,
			long savedAmount,
			Long abandonmentAmount,
			WishState state,
			LocalDate startDate,
			LocalDate targetDate,
			Instant createdAt,
			Instant completedAt,
			Instant abandonedAt,
			boolean representative) {}

	record SavingsRow(
			long transactionCount,
			long totalInflowAmount,
			long totalOutflowAmount,
			Instant lastTransactionAt) {}
}
