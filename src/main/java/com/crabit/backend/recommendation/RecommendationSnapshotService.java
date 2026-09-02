package com.crabit.backend.recommendation;

import com.crabit.backend.recommendation.RecommendationSnapshotRepository.AccountRow;
import com.crabit.backend.recommendation.RecommendationSnapshotRepository.SavingsRow;
import com.crabit.backend.recommendation.RecommendationSnapshotRepository.WishRow;
import com.crabit.backend.wish.KrwAmount;
import com.crabit.backend.wish.SharedCardQueryRepository;
import com.crabit.backend.wish.WishState;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "crabit.recommendation.handoff.enabled", havingValue = "true")
class RecommendationSnapshotService {

	private static final int LIMIT = 100;
	private static final int QUERY_LIMIT = LIMIT + 1;
	private static final List<String> TARGET_GROUPS = List.of("유아", "초등", "중등", "고등", "일반");
	private static final List<String> CATEGORIES =
			List.of("어학", "입시 종합", "국어논술독서", "유아 초등 종합", "기타");
	private static final List<String> SCALES =
			List.of("30명 미만", "100명 미만", "200명 미만", "300명 미만", "500명 미만", "500명 이상");

	private final RecommendationSnapshotRepository repository;

	RecommendationSnapshotService(RecommendationSnapshotRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public SnapshotResult assemble(UUID handoffId, UUID accountId) {
		return assemble(new RecommendationRequest(handoffId, accountId, null, null));
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public SnapshotResult assemble(RecommendationRequest request) {
		UUID handoffId = request.handoffId(), accountId = request.accountId();
		Objects.requireNonNull(handoffId, "handoffId");
		Objects.requireNonNull(accountId, "accountId");
		Instant snapshotAt = requireInstant(repository.transactionTimestamp());
		AccountRow account =
				repository
						.findActiveAccount(accountId)
						.orElseThrow(RecommendationHandoffException::accountNotFound);
		validateAccount(account, accountId);
		requireNotFuture(account.openedAt(), snapshotAt);
		var period = request.resolve(snapshotAt);
		repository.validateRepresentative(accountId);
		Instant observedStart =
				period.startAt().isAfter(account.openedAt())
						? period.startAt()
						: account.openedAt();
		Instant observedEnd = period.endAt().isBefore(snapshotAt) ? period.endAt() : snapshotAt;
		var periodPayload =
				RecommendationPeriodSavings.assemble(
						request,
						account,
						snapshotAt,
						repository.periodSavings(accountId, observedStart, observedEnd));

		List<WishRow> viewerCandidates = repository.findViewerWishes(accountId, QUERY_LIMIT);
		boolean viewerTruncated = viewerCandidates.size() > LIMIT;
		List<WishRow> viewerWishes = retain(viewerCandidates);
		var selection =
				RecommendationCandidateSelection.select(repository, request, account, snapshotAt);
		boolean candidatesTruncated = selection.truncated();
		List<SharedCardQueryRepository.Row> candidates = selection.rows();

		Set<UUID> wishIds = new LinkedHashSet<>();
		viewerWishes.forEach(wish -> wishIds.add(wish.wishId()));
		candidates.forEach(candidate -> wishIds.add(candidate.wishId()));
		Map<UUID, SavingsRow> summaries = repository.summarizeSavings(wishIds);
		for (WishRow wish : viewerWishes) {
			if (!wish.accountId().equals(accountId)
					|| !wish.academyId().equals(account.academyId())) {
				throw RecommendationHandoffException.incomplete();
			}
			requireNotFuture(wish.createdAt(), snapshotAt);
			requireNotFuture(wish.completedAt(), snapshotAt);
			requireNotFuture(wish.abandonedAt(), snapshotAt);
		}
		for (SharedCardQueryRepository.Row candidate : candidates) {
			requireNotFuture(candidate.accountOpenedAt(), snapshotAt);
			requireNotFuture(candidate.createdAt(), snapshotAt);
			requireNotFuture(candidate.completedAt(), snapshotAt);
			requireNotFuture(candidate.abandonedAt(), snapshotAt);
			requireNotFuture(candidate.contentUpdatedAt(), snapshotAt);
		}
		summaries
				.values()
				.forEach(summary -> requireNotFuture(summary.lastTransactionAt(), snapshotAt));

		List<ViewerWishItemPayload> viewerPayload =
				viewerWishes.stream()
						.map(wish -> viewerWish(wish, summary(wish.wishId(), summaries)))
						.toList();
		List<CandidatePayload> candidatePayload =
				candidates.stream()
						.map(
								candidate ->
										candidate(
												candidate,
												account.academyId(),
												summary(candidate.wishId(), summaries)))
						.toList();
		RecommendationPayload payload =
				new RecommendationPayload(
						3,
						1,
						handoffId,
						snapshotAt.toString(),
						viewerTruncated,
						candidatesTruncated,
						academy(account),
						new PersonPayload(
								account.studentId(),
								requireText(account.studentName()),
								validAge(account.studentAge())),
						new CardAccountPayload(
								account.accountId(),
								account.studentId(),
								account.academyId(),
								account.openedAt().toString(),
								null),
						viewerPayload,
						candidatePayload,
						periodPayload,
						selection.selection(),
						selection.interest());
		return new SnapshotResult(
				payload,
				viewerPayload.size(),
				candidatePayload.size(),
				viewerTruncated,
				candidatesTruncated);
	}

	private static void requireNotFuture(Instant timestamp, Instant snapshotAt) {
		if (timestamp != null && timestamp.isAfter(snapshotAt)) {
			throw RecommendationHandoffException.incomplete();
		}
	}

	private static AcademyPayload academy(AccountRow account) {
		byte[] digest;
		try {
			digest =
					MessageDigest.getInstance("SHA-256")
							.digest(
									("academy-feature-v1:" + account.academyId())
											.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
		int address = Byte.toUnsignedInt(digest[3]) % 100 + 1;
		return new AcademyPayload(
				account.academyId(),
				requireText(account.academyName()),
				"SYNTHETIC_REGION_%03d".formatted(address),
				TARGET_GROUPS.get(Byte.toUnsignedInt(digest[0]) % TARGET_GROUPS.size()),
				CATEGORIES.get(Byte.toUnsignedInt(digest[1]) % CATEGORIES.size()),
				SCALES.get(Byte.toUnsignedInt(digest[2]) % SCALES.size()));
	}

	private static ViewerWishItemPayload viewerWish(WishRow source, SavingsSummaryPayload summary) {
		validateWish(
				source.wishId(),
				source.accountId(),
				source.academyId(),
				source.title(),
				source.targetAmount(),
				source.savedAmount(),
				source.abandonmentAmount(),
				source.state(),
				source.startDate(),
				source.targetDate(),
				source.createdAt(),
				source.completedAt(),
				source.abandonedAt());
		return new ViewerWishItemPayload(
				new WishPayload(
						source.wishId(),
						source.academyId(),
						source.accountId(),
						source.title(),
						source.targetAmount(),
						source.startDate() == null ? null : source.startDate().toString(),
						source.targetDate() == null ? null : source.targetDate().toString(),
						source.representative(),
						source.state().name(),
						source.createdAt().toString(),
						closedAt(
								source.state(),
								source.createdAt(),
								source.completedAt(),
								source.abandonedAt()),
						source.savedAmount(),
						source.abandonmentAmount()),
				summary);
	}

	private static CandidatePayload candidate(
			SharedCardQueryRepository.Row source,
			UUID snapshotAcademyId,
			SavingsSummaryPayload summary) {
		validateWish(
				source.wishId(),
				source.accountId(),
				source.academyId(),
				source.purpose(),
				source.targetAmount(),
				source.wishAmount(),
				null,
				source.state(),
				source.startDate(),
				source.targetDate(),
				source.createdAt(),
				source.completedAt(),
				source.abandonedAt());
		if (!source.academyId().equals(snapshotAcademyId)
				|| source.accountClosedAt() != null
				|| source.state() == WishState.ABANDONED) {
			throw RecommendationHandoffException.incomplete();
		}
		return new CandidatePayload(
				new PersonPayload(
						requireUuid(source.ownerId()),
						requireText(source.ownerNickname()),
						validAge(source.ownerAge())),
				new CardAccountPayload(
						source.accountId(),
						source.ownerId(),
						source.academyId(),
						requireInstant(source.accountOpenedAt()).toString(),
						null),
				new CandidateWishPayload(
						source.wishId(),
						source.academyId(),
						source.accountId(),
						source.purpose(),
						source.targetAmount(),
						source.startDate() == null ? null : source.startDate().toString(),
						source.targetDate() == null ? null : source.targetDate().toString(),
						source.state().name(),
						source.createdAt().toString(),
						closedAt(
								source.state(),
								source.createdAt(),
								source.completedAt(),
								source.abandonedAt()),
						source.wishAmount()),
				new SharedCardPayload(
						requireUuid(source.sharedCardId()),
						source.accountId(),
						source.wishId(),
						Objects.requireNonNull(source.kind(), "kind").name(),
						requireInstant(source.contentUpdatedAt()).toString()),
				summary);
	}

	private static SavingsSummaryPayload summary(UUID wishId, Map<UUID, SavingsRow> summaries) {
		SavingsRow source = summaries.get(wishId);
		if (source == null) {
			return SavingsSummaryPayload.empty();
		}
		if (!safeNonNegative(source.transactionCount())
				|| !safeNonNegative(source.totalInflowAmount())
				|| !safeNonNegative(source.totalOutflowAmount())
				|| (source.transactionCount() == 0) != (source.lastTransactionAt() == null)
				|| source.transactionCount() == 0
						&& (source.totalInflowAmount() != 0 || source.totalOutflowAmount() != 0)) {
			throw RecommendationHandoffException.incomplete();
		}
		return new SavingsSummaryPayload(
				source.transactionCount(),
				source.totalInflowAmount(),
				source.totalOutflowAmount(),
				source.lastTransactionAt() == null ? null : source.lastTransactionAt().toString());
	}

	private static void validateAccount(AccountRow account, UUID requestedAccountId) {
		if (!requestedAccountId.equals(account.accountId())) {
			throw RecommendationHandoffException.incomplete();
		}
		requireUuid(account.studentId());
		requireUuid(account.academyId());
		requireInstant(account.openedAt());
		requireText(account.studentName());
		requireText(account.academyName());
		validAge(account.studentAge());
	}

	private static void validateWish(
			UUID wishId,
			UUID accountId,
			UUID academyId,
			String title,
			long targetAmount,
			long savedAmount,
			Long abandonmentAmount,
			WishState state,
			java.time.LocalDate startDate,
			java.time.LocalDate targetDate,
			Instant createdAt,
			Instant completedAt,
			Instant abandonedAt) {
		requireUuid(wishId);
		requireUuid(accountId);
		requireUuid(academyId);
		requireText(title);
		if (targetAmount < 1
				|| targetAmount > KrwAmount.MAX_SAFE_WON
				|| savedAmount < 0
				|| savedAmount > targetAmount
				|| state == null
				|| createdAt == null) {
			throw RecommendationHandoffException.incomplete();
		}
		boolean validAbandonmentAmount =
				state == WishState.ABANDONED
						? abandonmentAmount != null
								&& abandonmentAmount >= 0
								&& abandonmentAmount <= targetAmount
								&& savedAmount == 0
						: abandonmentAmount == null;
		if (!validAbandonmentAmount
				|| startDate != null && targetDate != null && startDate.isAfter(targetDate)) {
			throw RecommendationHandoffException.incomplete();
		}
		closedAt(state, createdAt, completedAt, abandonedAt);
	}

	private static String closedAt(
			WishState state, Instant createdAt, Instant completedAt, Instant abandonedAt) {
		Instant terminal =
				switch (state) {
					case IN_PROGRESS, AMOUNT_REACHED -> {
						if (completedAt != null || abandonedAt != null) {
							throw RecommendationHandoffException.incomplete();
						}
						yield null;
					}
					case COMPLETED -> {
						if (completedAt == null || abandonedAt != null) {
							throw RecommendationHandoffException.incomplete();
						}
						yield completedAt;
					}
					case ABANDONED -> {
						if (completedAt != null || abandonedAt == null) {
							throw RecommendationHandoffException.incomplete();
						}
						yield abandonedAt;
					}
				};
		if (terminal != null && terminal.isBefore(createdAt)) {
			throw RecommendationHandoffException.incomplete();
		}
		return terminal == null ? null : terminal.toString();
	}

	private static int validAge(int age) {
		if (age < 0 || age > 120) {
			throw RecommendationHandoffException.incomplete();
		}
		return age;
	}

	private static boolean safeNonNegative(long amount) {
		return amount >= 0 && amount <= KrwAmount.MAX_SAFE_WON;
	}

	private static String requireText(String value) {
		if (value == null || value.isBlank()) {
			throw RecommendationHandoffException.incomplete();
		}
		return value;
	}

	private static UUID requireUuid(UUID value) {
		if (value == null) {
			throw RecommendationHandoffException.incomplete();
		}
		return value;
	}

	private static Instant requireInstant(Instant value) {
		if (value == null) {
			throw RecommendationHandoffException.incomplete();
		}
		return value;
	}

	private static <T> List<T> retain(List<T> rows) {
		if (rows == null || rows.size() > QUERY_LIMIT) {
			throw RecommendationHandoffException.incomplete();
		}
		return List.copyOf(rows.subList(0, Math.min(LIMIT, rows.size())));
	}

	record SnapshotResult(
			RecommendationPayload payload,
			int viewerWishCount,
			int candidateCount,
			boolean viewerWishesTruncated,
			boolean candidatesTruncated) {}
}
