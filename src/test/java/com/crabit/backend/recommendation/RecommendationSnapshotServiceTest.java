package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.recommendation.RecommendationSnapshotRepository.AccountRow;
import com.crabit.backend.recommendation.RecommendationSnapshotRepository.SavingsRow;
import com.crabit.backend.recommendation.RecommendationSnapshotRepository.WishRow;
import com.crabit.backend.wish.SharedCardKind;
import com.crabit.backend.wish.SharedCardQueryRepository;
import com.crabit.backend.wish.WishState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RecommendationSnapshotServiceTest {

	private static final UUID HANDOFF_ID = id("00000000-0000-0000-0000-000000009001");
	private static final UUID ACADEMY_ID = id("00000000-0000-0000-0000-000000000101");
	private static final UUID VIEWER_ID = id("00000000-0000-0000-0000-000000000201");
	private static final UUID VIEWER_ACCOUNT_ID = id("00000000-0000-0000-0000-000000000301");
	private static final UUID VIEWER_WISH_ID = id("00000000-0000-0000-0000-000000000401");
	private static final UUID OWNER_ID = id("00000000-0000-0000-0000-000000000202");
	private static final UUID OWNER_ACCOUNT_ID = id("00000000-0000-0000-0000-000000000302");
	private static final UUID CANDIDATE_WISH_ID = id("00000000-0000-0000-0000-000000000402");
	private static final Instant SNAPSHOT_AT = Instant.parse("2026-08-31T05:10:00Z");
	private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void emitsTheExactSnakeCasePayloadWithExplicitNullsAndSignDerivedSummaries()
			throws Exception {
		FakeRepository repository = completeRepository();
		RecommendationSnapshotService service = new RecommendationSnapshotService(repository);

		RecommendationSnapshotService.SnapshotResult result =
				service.assemble(HANDOFF_ID, VIEWER_ACCOUNT_ID);
		JsonNode payload = objectMapper.readTree(
				objectMapper.writeValueAsBytes(result.payload()));

		assertThat(payload.propertyNames()).containsExactlyInAnyOrder(
				"schema_version", "synthetic_feature_version", "handoff_id", "snapshot_at",
				"viewer_wishes_truncated", "candidates_truncated", "academy", "viewer",
				"card_account", "viewer_wishes", "candidates");
		assertThat(payload.get("snapshot_at").textValue()).isEqualTo(SNAPSHOT_AT.toString());
		assertThat(payload.get("viewer").propertyNames())
				.containsExactlyInAnyOrder("user_id", "name", "age");
		assertThat(payload.at("/card_account/closed_at").isNull()).isTrue();
		assertThat(payload.at("/viewer_wishes/0/wish/target_date").isNull()).isTrue();
		assertThat(payload.at("/viewer_wishes/0/wish/closed_at").textValue())
				.isEqualTo("2026-02-01T00:00:00Z");
		assertThat(payload.at("/viewer_wishes/0/wish/abandonment_amount").isNull()).isTrue();
		assertThat(payload.at("/viewer_wishes/0/wish").propertyNames())
				.containsExactlyInAnyOrder(
						"wish_id", "academy_id", "account_id", "title", "target_amount",
						"target_date", "is_representative", "status", "created_at",
						"closed_at", "saved_amount", "abandonment_amount");
		assertThat(payload.at("/viewer_wishes/0/savings_summary/transaction_count").longValue())
				.isEqualTo(2);
		assertThat(payload.at("/viewer_wishes/0/savings_summary/total_inflow_amount").longValue())
				.isEqualTo(700);
		assertThat(payload.at("/viewer_wishes/0/savings_summary/total_outflow_amount").longValue())
				.isEqualTo(200);
		assertThat(payload.at("/candidates/0/wish").propertyNames())
				.doesNotContain("is_representative", "abandonment_amount")
				.containsExactlyInAnyOrder(
						"wish_id", "academy_id", "account_id", "title", "target_amount",
						"target_date", "status", "created_at", "closed_at", "saved_amount");
		assertThat(payload.at("/candidates/0/wish/closed_at").isNull()).isTrue();
		assertThat(payload.at("/candidates/0/savings_summary/last_transaction_at").isNull())
				.isTrue();
		assertThat(payload.at("/academy/address").textValue())
				.matches("SYNTHETIC_REGION_[0-9]{3}");
		assertThat(service.assemble(HANDOFF_ID, VIEWER_ACCOUNT_ID).payload().academy())
				.isEqualTo(result.payload().academy());
	}

	@Test
	void exposesExactAbandonmentHistoryOnlyOnAnAbandonedViewerWish() throws Exception {
		FakeRepository repository = completeRepository();
		WishRow wish = repository.viewerWishes.getFirst();
		repository.viewerWishes = List.of(new WishRow(
				wish.wishId(), wish.accountId(), wish.academyId(), wish.title(),
				wish.targetAmount(), 0, 470L, WishState.ABANDONED,
				wish.targetDate(), wish.createdAt(), null,
				Instant.parse("2026-02-01T00:00:00Z"), wish.representative()));

		JsonNode payload = objectMapper.readTree(objectMapper.writeValueAsBytes(
				new RecommendationSnapshotService(repository)
						.assemble(HANDOFF_ID, VIEWER_ACCOUNT_ID).payload()));

		assertThat(payload.at("/viewer_wishes/0/wish/saved_amount").longValue()).isZero();
		assertThat(payload.at("/viewer_wishes/0/wish/abandonment_amount").longValue())
				.isEqualTo(470);
		assertThat(payload.at("/candidates/0/wish").propertyNames())
				.doesNotContain("abandonment_amount");
	}

	@Test
	void keepsOnlyOneHundredRowsAndDerivesTruncationOnlyFromTheExtraRow() {
		FakeRepository repository = completeRepository();
		repository.viewerWishes = java.util.Collections.nCopies(101, repository.viewerWishes.getFirst());
		repository.candidates = java.util.Collections.nCopies(101, repository.candidates.getFirst());

		RecommendationSnapshotService.SnapshotResult result =
				new RecommendationSnapshotService(repository)
						.assemble(HANDOFF_ID, VIEWER_ACCOUNT_ID);

		assertThat(result.viewerWishCount()).isEqualTo(100);
		assertThat(result.candidateCount()).isEqualTo(100);
		assertThat(result.viewerWishesTruncated()).isTrue();
		assertThat(result.candidatesTruncated()).isTrue();
	}

	@Test
	void failsClosedForMissingAgeContradictoryLifecycleAndUnsafeAggregate() {
		FakeRepository invalidAge = completeRepository();
		invalidAge.account = new AccountRow(
				VIEWER_ACCOUNT_ID, VIEWER_ID, ACADEMY_ID, CREATED_AT,
				"열람자", 121, "합성 학원");
		assertIncomplete(invalidAge);

		FakeRepository contradictory = completeRepository();
		WishRow wish = contradictory.viewerWishes.getFirst();
		contradictory.viewerWishes = List.of(new WishRow(
				wish.wishId(), wish.accountId(), wish.academyId(), wish.title(),
				wish.targetAmount(), wish.savedAmount(), null, WishState.COMPLETED,
				wish.targetDate(), wish.createdAt(), null, null, wish.representative()));
		assertIncomplete(contradictory);

		FakeRepository missingAbandonmentAmount = completeRepository();
		missingAbandonmentAmount.viewerWishes = List.of(new WishRow(
				VIEWER_WISH_ID, VIEWER_ACCOUNT_ID, ACADEMY_ID, "포기 위시",
				1_000, 0, null, WishState.ABANDONED, null, CREATED_AT,
				null, Instant.parse("2026-02-01T00:00:00Z"), false));
		assertIncomplete(missingAbandonmentAmount);

		FakeRepository leakedActiveHistory = completeRepository();
		leakedActiveHistory.viewerWishes = List.of(new WishRow(
				VIEWER_WISH_ID, VIEWER_ACCOUNT_ID, ACADEMY_ID, "진행 위시",
				1_000, 500, 500L, WishState.IN_PROGRESS, null, CREATED_AT,
				null, null, false));
		assertIncomplete(leakedActiveHistory);

		FakeRepository unsafeSummary = completeRepository();
		unsafeSummary.summaries = Map.of(VIEWER_WISH_ID,
				new SavingsRow(1, 9_007_199_254_740_992L, 0, SNAPSHOT_AT));
		assertIncomplete(unsafeSummary);
	}

	@Test
	void bindsAssemblyToAReadOnlyRepeatableReadTransaction() throws Exception {
		Transactional transaction = RecommendationSnapshotService.class
				.getMethod("assemble", UUID.class, UUID.class)
				.getAnnotation(Transactional.class);

		assertThat(transaction).isNotNull();
		assertThat(transaction.readOnly()).isTrue();
		assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
	}

	private static void assertIncomplete(FakeRepository repository) {
		assertThatThrownBy(() -> new RecommendationSnapshotService(repository)
				.assemble(HANDOFF_ID, VIEWER_ACCOUNT_ID))
				.isInstanceOf(RecommendationHandoffException.class)
				.extracting(exception -> ((RecommendationHandoffException) exception).code())
				.isEqualTo(RecommendationHandoffException.Code.RECOMMENDATION_DATA_INCOMPLETE);
	}

	private static FakeRepository completeRepository() {
		FakeRepository repository = new FakeRepository();
		repository.account = new AccountRow(
				VIEWER_ACCOUNT_ID, VIEWER_ID, ACADEMY_ID, CREATED_AT,
				"열람자", 15, "합성 학원");
		repository.viewerWishes = List.of(new WishRow(
				VIEWER_WISH_ID, VIEWER_ACCOUNT_ID, ACADEMY_ID, "완료 위시",
				1_000, 0, null, WishState.COMPLETED, null, CREATED_AT,
				Instant.parse("2026-02-01T00:00:00Z"), null, false));
		repository.candidates = List.of(new SharedCardQueryRepository.Row(
				id("00000000-0000-0000-0000-000000000801"),
				SharedCardKind.PROGRESS,
				OWNER_ID,
				"친구",
				16,
				OWNER_ACCOUNT_ID,
				ACADEMY_ID,
				CREATED_AT,
				null,
				CANDIDATE_WISH_ID,
				"진행 위시",
				2_000,
				500,
				WishState.IN_PROGRESS,
				LocalDate.parse("2026-12-31"),
				CREATED_AT,
				null,
				null,
				SNAPSHOT_AT,
				false));
		repository.summaries = Map.of(
				VIEWER_WISH_ID, new SavingsRow(
						2, 700, 200, Instant.parse("2026-01-15T00:00:00Z")));
		return repository;
	}

	private static UUID id(String value) {
		return UUID.fromString(value);
	}

	private static final class FakeRepository implements RecommendationSnapshotRepository {

		private AccountRow account;
		private List<WishRow> viewerWishes = List.of();
		private List<SharedCardQueryRepository.Row> candidates = List.of();
		private Map<UUID, SavingsRow> summaries = Map.of();

		@Override
		public Instant transactionTimestamp() {
			return SNAPSHOT_AT;
		}

		@Override
		public Optional<AccountRow> findActiveAccount(UUID accountId) {
			return Optional.ofNullable(account);
		}

		@Override
		public List<WishRow> findViewerWishes(UUID accountId, int requestedRows) {
			assertThat(requestedRows).isEqualTo(101);
			return viewerWishes;
		}

		@Override
		public List<SharedCardQueryRepository.Row> findCandidates(
				UUID viewerId, UUID academyId, int requestedRows) {
			assertThat(viewerId).isEqualTo(VIEWER_ID);
			assertThat(academyId).isEqualTo(ACADEMY_ID);
			assertThat(requestedRows).isEqualTo(101);
			return candidates;
		}

		@Override
		public Map<UUID, SavingsRow> summarizeSavings(Collection<UUID> wishIds) {
			assertThat(wishIds).contains(VIEWER_WISH_ID, CANDIDATE_WISH_ID);
			return summaries;
		}
	}
}
