package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiExamplesTest {

	private static final Map<String, String> REQUIRED_EXAMPLE_SUMMARIES = Map.ofEntries(
			Map.entry("WeeklyRecapSucceeded", "현재 공개 가능한 성공 story가 있는 주간 리캡"),
			Map.entry("WeeklyRecapZeroActivity", "활동이 0이어도 성공인 주간 리캡"),
			Map.entry("WeeklyRecapNotGenerated", "생성 이력이 없는 주간 리캡 상태"),
			Map.entry("WeeklyRecapGenerating", "사용 가능한 이전 성공이 없는 생성 중 주간 리캡"),
			Map.entry("WeeklyRecapFailed", "내부 오류를 숨긴 실패 주간 리캡 상태"),
			Map.entry("WeeklyRecapStoryOmitted", "현재 접근할 수 없는 저장 story를 생략한 주간 리캡"),
			Map.entry("MonthlyRecapSucceeded", "nullable peer 순위와 예상일을 보존한 월간 리캡"),
			Map.entry("MonthlyRecapNotEligible", "유효 입금 세 건 미만인 월간 부적격 상태"),
			Map.entry("RecapQueryUnavailable", "저장된 리캡 조회 일시 사용 불가"),
			Map.entry("WishPhotoUploaded", "처리와 비공개 저장을 완료한 Pending 위시 사진"),
			Map.entry("WishPhotoActiveReplay", "보존 중인 활성 사진의 같은 콘텐츠 재생"),
			Map.entry("CreateWishWithPhoto", "Pending 사진을 원자적으로 첨부하는 위시 생성"),
			Map.entry("PatchWishReplacePhoto", "위시 사진 원자 교체"),
			Map.entry("PatchWishRemovePhoto", "위시 사진 제거"),
			Map.entry("PhotoContentRejected", "콘텐츠 안전성 정책으로 거부된 사진"),
			Map.entry("PhotoProcessingUnavailable", "사진 처리 의존성 일시 사용 불가"),
			Map.entry("WishPhotoExpiredReplay", "보존 중인 폐기 사진의 같은 콘텐츠 재생 충돌"),
			Map.entry("WishPhotoIdempotencyKeyReused", "보존 중인 key의 다른 콘텐츠 재사용 충돌"),
			Map.entry("PhotoDeliveryUnavailableOnReplay", "활성 사진 재생 URL 발급 일시 사용 불가"),
			Map.entry("WishMutationActivePhotoReplay", "원래 ACTIVE_PHOTO identity를 유지하는 Wish 변경 재생"),
			Map.entry("WishMutationNoPhotoReplayAfterLaterAttachment", "나중 attachment를 무시하는 NO_PHOTO Wish 변경 재생"),
			Map.entry("WishMutationPhotoRevokedReplay", "PHOTO_REVOKED인 Wish 변경 재생 충돌"),
			Map.entry("WishTransferPhotoRevokedReplay", "이체 한쪽이 PHOTO_REVOKED인 전체 재생 충돌"),
			Map.entry("StudentRelationshipSearchPage", "독립적인 양방향 상태를 포함한 학생 검색 결과"),
			Map.entry("EmptyStudentRelationshipPage", "학생 검색 빈 페이지"),
			Map.entry("FollowerSearchPage", "팔로워 검색 결과와 전체 관계 수"),
			Map.entry("EmptyFollowSearchPage", "검색 결과 없이 유지되는 전체 관계 수"),
			Map.entry("FollowingPageExample", "단방향 및 상호 팔로잉 목록"),
			Map.entry("ZeroFollowPage", "관계가 없는 팔로우 목록"),
			Map.entry("WishFollowersVisibility", "팔로워 공개로 변경한 위시 응답"),
			Map.entry("PatchWishFollowers", "위시를 팔로워에게 공개하는 변경 요청"),
			Map.entry("FollowersSharedProgress", "현재 viewer에서 owner로 팔로우한 학생의 진행 공유 카드"),
			Map.entry("StudentBlockCreated", "학생 차단 생성"),
			Map.entry("StudentBlockPageExample", "활성 학생 차단 페이지"),
			Map.entry("EmptyStudentBlockPage", "활성 학생 블록 빈 페이지"),
			Map.entry("MalformedStudentRelationshipUuid", "학생 관계 잘못된 UUID"),
			Map.entry("MalformedStudentRelationshipNickname", "학생 관계 잘못된 닉네임"),
			Map.entry("MalformedStudentRelationshipLimit", "학생 관계 잘못된 limit"),
			Map.entry("MalformedStudentRelationshipCursor", "학생 관계 잘못된 커서"),
			Map.entry("AuthRequiredStudentRelationship", "학생 관계 인증 필요"),
			Map.entry("ForbiddenStudentRelationship", "학생 관계 접근 금지"),
			Map.entry("AcademyNotFoundStudentRelationship", "학생 관계 학원 없음"),
			Map.entry("StudentNotFoundCrossAcademy", "학생 관계 다른 학원 학생 숨김"),
			Map.entry("StudentNotFoundBlocked", "학생 관계 양방향 차단 학생 숨김"),
			Map.entry("StudentBlockNotFoundStudentRelationship", "학생 관계 학생 차단 없음"),
			Map.entry("SelfRelationshipConflict", "학생 관계 자기 자신 관계 충돌"),
			Map.entry("StudentBlockAlreadyActiveConflict", "학생 관계 이미 활성인 학생 차단 충돌"),
			Map.entry("UnknownBalancePage", "UNKNOWN 잔액 페이지"),
			Map.entry("FailedRefreshKnownBalance", "후속 조회 실패 후 유지된 KNOWN 잔액"),
			Map.entry("EmptyWishPage", "빈 위시 페이지"),
			Map.entry("WishCreatedPrivateZero", "적립금 0인 PRIVATE 위시 생성"),
			Map.entry("IdempotentReplay", "멱등 재생"),
			Map.entry("WishAbandonedFunded", "자금이 있던 위시 포기"),
			Map.entry("WishAbandonedZeroFunded", "적립금 0인 위시 포기"),
			Map.entry("DeletedAbandonedWish", "포기 후 논리 삭제된 위시"),
			Map.entry("AbandonmentIdempotentReplay", "포기 응답 멱등 재생"),
			Map.entry("RepresentativeWishDuringBalanceMismatch", "잔액 불일치 중의 대표 위시"),
			Map.entry("RepresentativeWishSelected", "대표 위시 선택"),
			Map.entry("RepresentativeWishSameSelectionNoop", "현재 대표 위시 재선택 무변경 처리"),
			Map.entry("TerminalRepresentativeSelectionConflict", "종결 상태 위시 대표 선택 충돌"),
			Map.entry("BalanceSyncFailed", "잔액 동기화 실패"),
			Map.entry("BalanceMismatchLocked", "잔액 불일치 잠금"),
			Map.entry("DeletedWishHidden", "삭제된 위시 숨김"),
			Map.entry("CardBalanceChangeExample", "카드 잔액 변경"),
			Map.entry("CardBalanceChangePageExample", "카드 잔액 변경 페이지"),
			Map.entry("AccountCardBalanceChangeExample", "계정 단위 카드 잔액 변경"),
			Map.entry("AccountCardBalanceChangePageExample", "계정 단위 카드 잔액 변경 페이지"),
			Map.entry("AccountFundMovementExample", "계정 단위 자금 이동"),
			Map.entry("AccountWishTransferExample", "계정 단위 위시 이체"),
			Map.entry("AccountWishTransferPageExample", "계정 단위 위시 이체 페이지"),
			Map.entry("WishFundMovementExample", "위시 단위 자금 이동"),
			Map.entry("WishTransferSourcePageExample", "위시 이체 출발 효과 페이지"),
			Map.entry("WishTransferDestinationPageExample", "위시 이체 도착 효과 페이지"),
			Map.entry("DeletedWishHistoryPageExample", "삭제된 위시 내역 페이지"),
			Map.entry("EmptyDeletedWishHistoryPageExample", "삭제된 위시 빈 이력 페이지"),
			Map.entry("PurposeAsciiBoundaries", "purpose의 ASCII 경계 공백"),
			Map.entry("PurposeDecomposedNfc", "purpose의 분해형 유니코드 NFC 정규화"),
			Map.entry("PurposeNbspBoundaries", "purpose의 NBSP 경계 공백"),
			Map.entry("InvalidPurposeEmptyAfterBoundaries", "경계 공백 제거 후 빈 purpose"),
			Map.entry("InvalidPurposeUnicodeCategories", "purpose에 금지된 유니코드 범주"),
			Map.entry("InvalidDateRange", "역전된 위시 계획 날짜 범위"),
			Map.entry("InvalidExpectedVersion", "유효하지 않은 expectedVersion"),
			Map.entry("InvalidSourceExpectedVersion", "유효하지 않은 sourceExpectedVersion"),
			Map.entry("InvalidDestinationExpectedVersion", "유효하지 않은 destinationExpectedVersion"),
			Map.entry("InvalidIfMatchVersion", "유효하지 않은 If-Match 버전"),
			Map.entry("SharedProgressAdjustmentFalse", "잔액 조정 건이 없는 진행 공유 카드"),
			Map.entry("SharedProgressAdjustmentTrue", "잔액 조정 건이 열린 진행 공유 카드"),
			Map.entry("SharedCompletion", "잔액 조정 필드가 없는 완료 공유 카드"),
			Map.entry("SharedAbandonmentFunded", "자금이 있던 포기 공유 카드"),
			Map.entry("SharedAbandonmentZeroFunded", "적립금 0인 포기 공유 카드"),
			Map.entry("SharedAbandonmentFullTarget", "목표 금액에서 포기한 공유 카드"),
			Map.entry("SharedAbandonmentFundedPage", "자금이 있던 포기 공유 카드 페이지"),
			Map.entry("SharedAbandonmentZeroFundedPage", "적립금 0인 포기 공유 카드 페이지"),
			Map.entry("SharedAbandonmentFullTargetPage", "목표 금액에서 포기한 공유 카드 페이지"));

	private static final Set<String> FORBIDDEN_SHARED_CARD_FIELDS = Set.of(
			"wishId", "cardBalanceAccountId", "studentId", "physicalCardId", "physicalCardNumber",
			"actualCardBalance", "ledgerAvailableBalance", "displayAvailableBalance", "amount",
			"fundMovements", "cardBalanceChanges", "adjustmentStatus", "abandonmentAmount", "abandonedAt",
			"closedAt", "ledgerEventId", "recommendationScore");

	private static Map<String, Object> document;
	private static Map<String, Object> examples;

	@BeforeAll
	static void parseContract() throws IOException {
		try (InputStream input = Files.newInputStream(Path.of("api", "openapi.yaml"))) {
			document = map(new Yaml().load(input));
		}
		examples = map(path("components", "examples"));
	}

	@Test
	void containsEveryApprovedNamedExampleAndValidatesEachAgainstItsSchema() {
		REQUIRED_EXAMPLE_SUMMARIES.forEach((name, summary) -> assertThat(map(examples.get(name)))
				.as(name + " localized summary")
				.containsEntry("summary", summary));

		examples.forEach((name, rawExample) -> {
			Map<String, Object> example = map(rawExample);
			String schemaRef = Objects.toString(example.get("x-schema-ref"), null);
			assertThat(schemaRef).as(name + " schema binding").startsWith("#/components/schemas/");
			assertThat(validate(example.get("value"), map(resolve(schemaRef)), "$"))
					.as(name + " schema validation").isEmpty();
		});
	}

	@Test
	void distinguishesEveryPublicRecapStateAndReadTimeStoryProjection() {
		assertThat(value("WeeklyRecapNotGenerated"))
				.containsEntry("status", "NOT_GENERATED")
				.containsEntry("generationVersion", null)
				.containsEntry("algorithmVersion", null)
				.containsEntry("generatedAt", null)
				.containsEntry("result", null);
		for (String name : List.of("WeeklyRecapGenerating", "WeeklyRecapFailed")) {
			assertThat(value(name)).containsEntry("result", null).containsEntry("generatedAt", null);
		}
		Map<String, Object> zero = value("WeeklyRecapZeroActivity");
		assertThat(zero).containsEntry("status", "SUCCEEDED");
		Map<String, Object> zeroResult = map(zero.get("result"));
		Map<String, Object> zeroAchievement = map(map(map(zeroResult.get("page1LastWeekPerformance"))
				.get("achievement")));
		assertThat(zeroAchievement).containsEntry("saveCount", 0).containsEntry("netSavings", 0)
				.containsEntry("newWishCount", 0);
		assertThat(list(map(zeroResult.get("page3AcademySuccessStories")).get("stories"))).isEmpty();

		Map<String, Object> visibleStories = map(map(value("WeeklyRecapSucceeded").get("result"))
				.get("page3AcademySuccessStories"));
		assertThat(list(visibleStories.get("stories"))).singleElement().satisfies(raw ->
				assertThat(map(raw)).containsOnlyKeys("wishId", "typeTitle", "ownerStudentId", "sharedCardId"));
		assertThat(list(map(map(value("WeeklyRecapStoryOmitted").get("result"))
				.get("page3AcademySuccessStories")).get("stories"))).isEmpty();

		assertThat(value("MonthlyRecapNotEligible"))
				.containsEntry("status", "NOT_ELIGIBLE").containsEntry("result", null);
		Map<String, Object> comparison = map(map(value("MonthlyRecapSucceeded").get("result"))
				.get("groupComparison"));
		assertThat(comparison).containsEntry("habitPercentile", null)
				.containsEntry("habitPercentileStatus", "no_peers")
				.containsEntry("achievementPercentile", null)
				.containsEntry("achievementPercentileStatus", "all_tied");
		assertThat(map(map(value("RecapQueryUnavailable").get("error"))))
				.containsEntry("code", "RECAP_QUERY_UNAVAILABLE").containsEntry("retryable", true);
	}

	@Test
	void rejectsInvalidBehaviorPayloadsAndPreservesUnavailableCounts() {
		Map<String, Object> exposure = new LinkedHashMap<>(value("BehaviorFeedExposureRequest"));
		exposure.put("clickKind", "AUTHOR_PROFILE");
		assertThat(validate(exposure, map(resolve("#/components/schemas/BehaviorFeedEventRequest")), "$"))
				.isNotEmpty();
		Map<String, Object> click = new LinkedHashMap<>(value("BehaviorFeedClickRequest"));
		click.remove("clickKind");
		assertThat(validate(click, map(resolve("#/components/schemas/BehaviorFeedEventRequest")), "$"))
				.isNotEmpty();
		Map<String, Object> request = new LinkedHashMap<>(value("BehaviorProfileVisitRequest"));
		request.put("actorId", "11111111-1111-4111-8111-111111111111");
		assertThat(validate(request, map(resolve("#/components/schemas/BehaviorProfileVisitRequest")), "$"))
				.isNotEmpty();
		Map<String, Object> none = new LinkedHashMap<>(value("BehaviorBeforeCollectionNone"));
		assertThat(none).containsEntry("visitCount", null).containsEntry("distinctVisitorCount", null);
		none.put("visitCount", 0);
		assertThat(validate(none, map(resolve("#/components/schemas/BehaviorProfileVisitMetrics")), "$"))
				.isNotEmpty();
		Map<String, Object> zero = new LinkedHashMap<>(value("BehaviorCompleteObservedZero"));
		zero.put("visitCount", null);
		assertThat(validate(zero, map(resolve("#/components/schemas/BehaviorProfileVisitMetrics")), "$"))
				.isNotEmpty();
		assertThat(value("BehaviorProfileVisitReplay")).isEqualTo(value("BehaviorProfileVisitAccepted"));
		assertThat(map(example("BehaviorProfileVisitReplay").get("x-response-headers")))
				.containsEntry("Idempotency-Replayed", true);
		Map<String, Object> ctr = map(list(value("BehaviorFeedCtr").get("items")).getFirst());
		assertThat(ctr).containsEntry("exposureCount", 3).containsEntry("clickCount", 4)
				.containsEntry("clickedExposedImpressionCount", 2).containsEntry("unmatchedClickCount", 1);
		assertThat(((Number) ctr.get("ctr")).doubleValue()).isEqualTo(2.0 / 3.0);
		assertThat(map(list(value("BehaviorFeedNullCtr").get("items")).getFirst()))
				.containsEntry("exposureCount", 0).containsEntry("ctr", null);
	}

	@Test
	void demonstratesIndependentDirectionsUnfilteredCountsAndHiddenTargets() {
		List<Object> students = list(value("StudentRelationshipSearchPage").get("items"));
		assertThat(students).extracting(raw -> List.of(map(raw).get("isFollowing"), map(raw).get("isFollowedBy")))
				.containsExactly(List.of(false, false), List.of(true, false), List.of(false, true), List.of(true, true));
		Map<String, Object> followerPage = value("FollowerSearchPage");
		assertThat(list(followerPage.get("items"))).singleElement().satisfies(raw ->
				assertThat(map(raw)).containsEntry("isFollowing", false).containsEntry("isFollowedBy", true));
		assertThat(followerPage).containsEntry("followingCount", 7).containsEntry("followerCount", 50);
		assertThat(list(value("EmptyFollowSearchPage").get("items"))).isEmpty();
		assertThat(value("EmptyFollowSearchPage"))
				.containsEntry("followingCount", 7).containsEntry("followerCount", 50).containsEntry("nextCursor", null);
		assertThat(value("ZeroFollowPage"))
				.containsEntry("followingCount", 0).containsEntry("followerCount", 0).containsEntry("nextCursor", null);
		assertThat(list(value("FollowingPageExample").get("items"))).allSatisfy(raw ->
				assertThat(map(raw)).containsEntry("isFollowing", true).containsKey("followedAt"));
		assertThat(value("StudentNotFoundCrossAcademy")).isEqualTo(value("StudentNotFoundBlocked"));
		assertThat(value("PatchWishFollowers")).containsEntry("visibility", "FOLLOWERS").containsKey("expectedVersion");
		assertThat(value("WishFollowersVisibility")).containsEntry("visibility", "FOLLOWERS");
	}

	@Test
	void distinguishesUnknownBalanceFromAFailedRefreshOfKnownValues() {
		Map<String, Object> unknownPage = value("UnknownBalancePage");
		Map<String, Object> unknown = map(list(unknownPage.get("items")).getFirst());
		assertThat(unknown).containsEntry("balanceKnowledge", "UNKNOWN")
				.containsEntry("actualCardBalance", null)
				.containsEntry("ledgerAvailableBalance", null)
				.containsEntry("displayAvailableBalance", null)
				.containsEntry("unresolvedShortage", null);

		Map<String, Object> failedKnown = value("FailedRefreshKnownBalance");
		assertThat(failedKnown).containsEntry("balanceKnowledge", "KNOWN")
				.containsEntry("lastRefreshStatus", "FAILED");
		assertThat(failedKnown.get("actualCardBalance")).isNotNull();
		assertThat(failedKnown.get("ledgerAvailableBalance")).isNotNull();
		assertThat(failedKnown.get("displayAvailableBalance")).isNotNull();
	}

	@Test
	void makesOnlyDeclaredTransientFailuresRetryableAndShowsIdempotentReplayExplicitly() {
		Map<String, Object> syncError = map(value("BalanceSyncFailed").get("error"));
		Map<String, Object> processingError = map(value("PhotoProcessingUnavailable").get("error"));
		Map<String, Object> deliveryError = map(value("PhotoDeliveryUnavailableOnReplay").get("error"));
		Map<String, Object> expiredError = map(value("WishPhotoExpiredReplay").get("error"));
		Map<String, Object> mutationExpiredError = map(value("WishMutationPhotoRevokedReplay").get("error"));
		Map<String, Object> transferExpiredError = map(value("WishTransferPhotoRevokedReplay").get("error"));
		Map<String, Object> reusedError = map(value("WishPhotoIdempotencyKeyReused").get("error"));
		Map<String, Object> contentError = map(value("PhotoContentRejected").get("error"));
		Map<String, Object> mismatchError = map(value("BalanceMismatchLocked").get("error"));
		Map<String, Object> deletedError = map(value("DeletedWishHidden").get("error"));
		assertThat(syncError).containsEntry("code", "BALANCE_SYNC_FAILED").containsEntry("retryable", true);
		assertThat(processingError).containsEntry("code", "PHOTO_PROCESSING_UNAVAILABLE")
				.containsEntry("retryable", true);
		assertThat(deliveryError).containsEntry("code", "PHOTO_DELIVERY_UNAVAILABLE")
				.containsEntry("retryable", true).containsEntry("details", Map.of());
		assertThat(expiredError).containsEntry("code", "WISH_PHOTO_EXPIRED")
				.containsEntry("retryable", false).containsEntry("details", Map.of());
		assertThat(mutationExpiredError).containsEntry("code", "WISH_PHOTO_EXPIRED")
				.containsEntry("retryable", false).containsEntry("details", Map.of());
		assertThat(transferExpiredError).containsEntry("code", "WISH_PHOTO_EXPIRED")
				.containsEntry("retryable", false).containsEntry("details", Map.of());
		assertThat(reusedError).containsEntry("code", "IDEMPOTENCY_KEY_REUSED")
				.containsEntry("retryable", false).containsEntry("details", Map.of());
		assertThat(contentError).containsEntry("code", "PHOTO_CONTENT_NOT_ALLOWED")
				.containsEntry("retryable", false);
		assertThat(mismatchError).containsEntry("code", "BALANCE_MISMATCH_LOCKED").containsEntry("retryable", false);
		assertThat(deletedError).containsEntry("code", "WISH_NOT_FOUND").containsEntry("retryable", false);
		assertThat(map(example("IdempotentReplay").get("x-response-headers")))
				.containsEntry("Idempotency-Replayed", true);
		assertThat(list(example("WishMutationPhotoRevokedReplay").get("x-omitted-response-headers")))
				.containsExactly("Idempotency-Replayed");
		assertThat(list(example("WishTransferPhotoRevokedReplay").get("x-omitted-response-headers")))
				.containsExactly("Idempotency-Replayed");
	}

	@Test
	void demonstratesWishPhotoUploadAttachmentReplacementRemovalAndPrivacy() {
		Map<String, Object> photo = value("WishPhotoUploaded");
		Map<String, Object> replayExample = map(examples.get("WishPhotoActiveReplay"));
		Map<String, Object> replay = map(replayExample.get("value"));
		assertThat(photo).containsOnlyKeys("id", "variants", "expiresAt");
		assertThat(map(photo.get("variants"))).containsOnlyKeys("small", "medium", "large")
				.doesNotContainKeys("bucket", "objectPath", "contentDigest", "safetyResult");
		assertThat(OffsetDateTime.parse(photo.get("expiresAt").toString()))
				.isEqualTo(OffsetDateTime.parse("2026-08-31T12:05:00Z"));
		assertThat(replay).containsOnlyKeys("id", "variants", "expiresAt")
				.containsEntry("id", photo.get("id"));
		assertThat(map(replay.get("variants"))).containsOnlyKeys("small", "medium", "large")
				.values().allSatisfy(url -> assertThat(url.toString()).contains("/signed/new-"));
		assertThat(OffsetDateTime.parse(replay.get("expiresAt").toString()))
				.isEqualTo(OffsetDateTime.parse("2026-09-01T12:05:00Z"));
		assertThat(map(replayExample.get("x-request-headers")))
				.containsEntry("Idempotency-Key", "wish-photo-2026-09-01");
		assertThat(replayExample).containsEntry(
				"x-request-photo", "same-exact-JPEG-bytes-as-the-initial-upload");
		assertThat(map(replayExample.get("x-response-headers")))
				.containsEntry("Idempotency-Replayed", true)
				.containsEntry("Cache-Control", "no-store");

		Map<String, Object> mutationActiveExample = example("WishMutationActivePhotoReplay");
		Map<String, Object> mutationActive = value("WishMutationActivePhotoReplay");
		Map<String, Object> mutationActiveWish = map(mutationActive.get("wish"));
		Map<String, Object> mutationActivePhoto = map(mutationActiveWish.get("photo"));
		assertThat(map(mutationActiveExample.get("x-private-photo-replay-state")))
				.containsEntry("kind", "ACTIVE_PHOTO")
				.containsEntry("photoId", mutationActivePhoto.get("id"));
		assertThat(map(mutationActiveExample.get("x-response-headers")))
				.containsEntry("Idempotency-Replayed", true)
				.containsEntry("Cache-Control", "no-store");
		assertThat(map(mutationActivePhoto.get("variants"))).containsOnlyKeys("small", "medium", "large")
				.values().allSatisfy(url -> assertThat(url.toString()).contains("/signed/replay-"));

		Map<String, Object> mutationNoPhotoExample = example("WishMutationNoPhotoReplayAfterLaterAttachment");
		Map<String, Object> mutationNoPhotoWish = map(value("WishMutationNoPhotoReplayAfterLaterAttachment").get("wish"));
		assertThat(map(mutationNoPhotoExample.get("x-private-photo-replay-state")))
				.containsExactly(Map.entry("kind", "NO_PHOTO"));
		assertThat(mutationNoPhotoExample).containsEntry(
				"x-current-attachment-ignored", "11111111-2222-3333-4444-555555555555");
		assertThat(mutationNoPhotoWish).containsEntry("photo", null);

		Map<String, Object> mutationRevoked = example("WishMutationPhotoRevokedReplay");
		assertThat(map(mutationRevoked.get("x-private-photo-replay-state")))
				.containsExactly(Map.entry("kind", "PHOTO_REVOKED"));
		assertThat(list(mutationRevoked.get("x-forbidden-response-fields")))
				.contains("wish", "photoId", "signedUrl", "retainedSnapshot", "currentAttachment");

		Map<String, Object> transferRevoked = example("WishTransferPhotoRevokedReplay");
		Map<String, Object> transferStates = map(transferRevoked.get("x-private-photo-replay-states"));
		assertThat(map(transferStates.get("source"))).containsEntry("kind", "ACTIVE_PHOTO");
		assertThat(map(transferStates.get("destination"))).containsExactly(Map.entry("kind", "PHOTO_REVOKED"));
		assertThat(transferRevoked).containsEntry("x-url-issuance-before-state-evaluation", false);
		assertThat(list(transferRevoked.get("x-forbidden-response-fields")))
				.contains("sourceWish", "destinationWish", "photoId", "signedUrl");

		Map<String, Object> expiredExample = map(examples.get("WishPhotoExpiredReplay"));
		assertThat(list(expiredExample.get("x-forbidden-response-fields"))).contains(
				"photo", "photoId", "variants", "expiresAt", "contentDigest", "receiptOutcome",
				"retainUntil", "signedUrl", "objectPath");

		assertThat(value("CreateWishWithPhoto"))
				.containsEntry("photoId", "9a8b7c6d-5e4f-4321-9876-1234567890ab");
		assertThat(value("PatchWishReplacePhoto"))
				.containsEntry("expectedVersion", 3)
				.containsEntry("photoId", "11111111-2222-3333-4444-555555555555");
		assertThat(value("PatchWishRemovePhoto"))
				.containsEntry("expectedVersion", 4)
				.containsEntry("photoId", null);

		for (String name : List.of(
				"WishCreatedPrivateZero", "IdempotentReplay", "WishBalanceAdjustmentOpen",
				"RepresentativeWishDuringBalanceMismatch", "RepresentativeWishSelected",
				"RepresentativeWishSameSelectionNoop")) {
			Map<String, Object> raw = value(name);
			Map<String, Object> wish = raw.containsKey("wish") ? map(raw.get("wish")) : raw;
			assertThat(wish).as(name).containsEntry("photo", null);
		}
		for (String name : List.of(
				"SharedProgressAdjustmentFalse", "SharedProgressAdjustmentTrue", "SharedCompletion")) {
			assertThat(value(name)).as(name).containsEntry("photo", null);
		}
	}

	@Test
	void demonstratesRepresentativeSelectionSuccessNoopMismatchAndTerminalConflict() {
		Map<String, Object> mismatch = value("RepresentativeWishDuringBalanceMismatch");
		Map<String, Object> selected = value("RepresentativeWishSelected");
		Map<String, Object> noop = value("RepresentativeWishSameSelectionNoop");
		Map<String, Object> conflict = map(value("TerminalRepresentativeSelectionConflict").get("error"));

		assertThat(mismatch)
				.containsEntry("id", "22222222-2222-2222-2222-222222222222")
				.containsEntry("cardBalanceAccountId", "11111111-1111-1111-1111-111111111111")
				.containsEntry("state", "IN_PROGRESS")
				.containsEntry("visibility", "PRIVATE")
				.containsEntry("balanceAdjustmentInProgress", true)
				.containsEntry("version", 0);
		assertThat(selected)
				.containsEntry("id", "341ab749-bbab-4b08-9334-0e4b12347b48")
				.containsEntry("state", "AMOUNT_REACHED")
				.containsEntry("version", 3);
		assertThat(noop).isEqualTo(selected);
		assertThat(example("RepresentativeWishSameSelectionNoop").get("description").toString())
				.contains("updatedAt과 version은 바뀌지 않습니다");
		assertThat(conflict)
				.containsEntry("code", "INVALID_STATE_TRANSITION")
				.containsEntry("retryable", false);
	}

	@Test
	void includesTheRequiredNullClosureAndAbandonmentHistoryInEveryActiveWishExample() {
		Map<String, Object> created = map(value("WishCreatedPrivateZero").get("wish"));
		Map<String, Object> replay = map(value("IdempotentReplay").get("wish"));
		List<Map<String, Object>> activeWishes = List.of(
				created,
				replay,
				value("WishBalanceAdjustmentOpen"),
				value("RepresentativeWishDuringBalanceMismatch"),
				value("RepresentativeWishSelected"),
				value("RepresentativeWishSameSelectionNoop"));

		activeWishes.forEach(wish -> {
			assertThat(wish.get("state")).isIn("IN_PROGRESS", "AMOUNT_REACHED");
			assertThat(wish)
					.containsKey("startDate")
					.containsEntry("completedAt", null)
					.containsEntry("closedAt", null)
					.containsEntry("abandonmentAmount", null);
		});
		assertThat(example("IdempotentReplay").get("description").toString())
				.contains("startDate", "closedAt", "abandonmentAmount", "최초 결과에 캡처된 값");

		assertThat(created)
				.containsEntry("startDate", "2026-09-01")
				.containsEntry("targetDate", "2027-02-28")
				.containsEntry("createdAt", "2026-08-16T02:10:00Z");
		assertThat(value("WishBalanceAdjustmentOpen")).containsEntry("startDate", null);
		assertThat(value("RepresentativeWishDuringBalanceMismatch")).containsEntry("startDate", null);

		Map<String, Object> missingClosure = new LinkedHashMap<>(created);
		missingClosure.remove("closedAt");
		assertThat(validate(missingClosure, schema("Wish"), "$"))
				.as("closedAt is required even while its active-state value is null")
				.isNotEmpty();

		Map<String, Object> missingHistory = new LinkedHashMap<>(created);
		missingHistory.remove("abandonmentAmount");
		assertThat(validate(missingHistory, schema("Wish"), "$"))
				.as("abandonmentAmount is required even while its active-state value is null")
				.isNotEmpty();

		Map<String, Object> leakedInternalInstant = new LinkedHashMap<>(created);
		leakedInternalInstant.put("abandonedAt", "2026-08-16T02:10:00Z");
		assertThat(validate(leakedInternalInstant, schema("Wish"), "$"))
				.as("the internal abandonment timestamp must not be public")
				.isNotEmpty();
	}

	@Test
	void documentsPlanStartDateRequestsAndTheExactRangeFailure() {
		Map<String, Object> createSchema = schema("CreateWishRequest");
		Map<String, Object> patchSchema = schema("WishMergePatch");

		Map<String, Object> omittedCreate = Map.of("purpose", "유럽 여행", "targetAmount", 3000000);
		Map<String, Object> nullCreate = new LinkedHashMap<>(omittedCreate);
		nullCreate.put("startDate", null);
		Map<String, Object> datedCreate = new LinkedHashMap<>(omittedCreate);
		datedCreate.put("startDate", "2026-09-01");
		datedCreate.put("targetDate", "2027-02-28");
		for (Map<String, Object> request : List.of(omittedCreate, nullCreate, datedCreate)) {
			assertThat(validate(request, createSchema, "$")).isEmpty();
		}

		for (Object invalidStartDate : List.of(
				"2026-09-01T00:00:00Z", "2026-02-30", "09/01/2026", 20260901, true)) {
			Map<String, Object> invalidCreate = new LinkedHashMap<>(omittedCreate);
			invalidCreate.put("startDate", invalidStartDate);
			assertThat(validate(invalidCreate, createSchema, "$"))
					.as("invalid create startDate " + invalidStartDate)
					.isNotEmpty();
		}
		Map<String, Object> unknownCreate = new LinkedHashMap<>(omittedCreate);
		unknownCreate.put("planStartDate", "2026-09-01");
		assertThat(validate(unknownCreate, createSchema, "$"))
				.as("closed create request rejects an unknown date field")
				.isNotEmpty();

		Map<String, Object> setPatch = new LinkedHashMap<>();
		setPatch.put("expectedVersion", 3);
		setPatch.put("startDate", "2026-10-01");
		setPatch.put("targetDate", "2027-03-31");
		Map<String, Object> clearPatch = new LinkedHashMap<>();
		clearPatch.put("expectedVersion", 4);
		clearPatch.put("startDate", null);
		assertThat(validate(setPatch, patchSchema, "$")).isEmpty();
		assertThat(validate(clearPatch, patchSchema, "$")).isEmpty();

		Map<String, Object> createExamples = requestExamples("createWish", "application/json");
		assertThat(map(map(createExamples.get("create-with-period")).get("value")))
				.containsEntry("startDate", "2026-09-01")
				.containsEntry("targetDate", "2027-02-28");
		Map<String, Object> patchExamples = requestExamples("patchWish", "application/merge-patch+json");
		assertThat(map(map(patchExamples.get("replace-plan-period")).get("value")))
				.containsEntry("expectedVersion", 3)
				.containsEntry("startDate", "2026-10-01")
				.containsEntry("targetDate", "2027-03-31");
		assertThat(map(map(patchExamples.get("clear-plan-start-date")).get("value")))
				.containsEntry("expectedVersion", 4)
				.containsEntry("startDate", null);

		Map<String, Object> invalidExample = example("InvalidDateRange");
		assertThat(map(invalidExample.get("x-request-value")))
				.containsEntry("startDate", "2027-03-01")
				.containsEntry("targetDate", "2027-02-28");
		Map<String, Object> error = map(value("InvalidDateRange").get("error"));
		assertThat(error)
				.containsEntry("code", "INVALID_DATE_RANGE")
				.containsEntry("message", "startDate must be on or before targetDate.")
				.containsEntry("retryable", false)
				.containsEntry("details", Map.of());
		assertThat(list(error.get("fieldErrors"))).containsExactly(
				Map.of("field", "startDate", "message", "startDate must be on or before targetDate."),
				Map.of("field", "targetDate", "message", "targetDate must be on or after startDate."));
	}

	@Test
	void distinguishesFundedZeroDeletedAndReplayedAbandonmentHistory() {
		Map<String, Object> fundedResult = value("WishAbandonedFunded");
		Map<String, Object> zeroResult = value("WishAbandonedZeroFunded");
		Map<String, Object> deletedResult = value("DeletedAbandonedWish");
		Map<String, Object> replayResult = value("AbandonmentIdempotentReplay");
		Map<String, Object> funded = map(fundedResult.get("wish"));
		Map<String, Object> zero = map(zeroResult.get("wish"));
		Map<String, Object> deleted = map(deletedResult.get("wish"));
		Map<String, Object> replay = map(replayResult.get("wish"));

		for (Map<String, Object> wish : List.of(funded, zero, deleted, replay)) {
			assertThat(wish).containsEntry("state", "ABANDONED").containsEntry("amount", 0);
			assertThat(wish.get("abandonmentAmount")).isInstanceOf(Number.class);
			assertThat(((Number) wish.get("abandonmentAmount")).longValue())
					.isBetween(0L, ((Number) wish.get("targetAmount")).longValue());
			assertThat(wish).doesNotContainKeys("abandonedAt", "abandonment_amount", "deletedAt");
		}
		assertThat(funded).containsEntry("abandonmentAmount", 470000);
		assertThat(fundedResult.get("eventId")).isNotNull();
		assertThat(zero).containsEntry("abandonmentAmount", 0);
		assertThat(zeroResult).containsEntry("eventId", null);
		assertThat(deleted).containsEntry("abandonmentAmount", funded.get("abandonmentAmount"));
		assertThat(deleted.get("closedAt")).isEqualTo(funded.get("closedAt"));
		assertThat(replay).isEqualTo(funded);
		assertThat(replayResult.get("eventId")).isEqualTo(fundedResult.get("eventId"));
		assertThat(map(example("AbandonmentIdempotentReplay").get("x-response-headers")))
				.containsEntry("Idempotency-Replayed", true);
		assertThat(example("AbandonmentIdempotentReplay").get("description").toString())
				.contains("최초 성공", "abandonmentAmount", "추가 변경이나 이벤트를 만들지 않습니다");
	}

	@Test
	void demonstratesAcceptedPurposeInputsAndTheirNormalizedOutputs() {
		Map<String, Object> ascii = value("PurposeAsciiBoundaries");
		Map<String, Object> decomposed = value("PurposeDecomposedNfc");
		Map<String, Object> nbsp = value("PurposeNbspBoundaries");

		assertThat(ascii.get("purpose").toString()).startsWith(" ").endsWith(" ");
		assertThat(example("PurposeAsciiBoundaries")).containsEntry("x-normalized-purpose", "새 노트북");

		String decomposedPurpose = decomposed.get("purpose").toString();
		assertThat(Normalizer.isNormalized(decomposedPurpose, Normalizer.Form.NFC)).isFalse();
		assertThat(example("PurposeDecomposedNfc")).containsEntry("x-normalized-purpose", "Café");

		String nbspPurpose = nbsp.get("purpose").toString();
		assertThat(nbspPurpose.codePointAt(0)).isEqualTo(0x00A0);
		assertThat(nbspPurpose.codePointBefore(nbspPurpose.length())).isEqualTo(0x00A0);
		assertThat(example("PurposeNbspBoundaries")).containsEntry("x-normalized-purpose", "비상금 계획");

		for (String name : List.of("PurposeAsciiBoundaries", "PurposeDecomposedNfc", "PurposeNbspBoundaries")) {
			Map<String, Object> request = value(name);
			assertThat(validate(request, schema("CreateWishRequest"), "$"))
					.as(name + " request input").isEmpty();
			assertThat(validate(example(name).get("x-normalized-purpose"), schema("Purpose"), "$"))
					.as(name + " normalized output").isEmpty();
		}
	}

	@Test
	void documentsEveryPurposeRejectionCategoryAndNegativeVersionField() {
		assertThat(example("InvalidPurposeEmptyAfterBoundaries"))
				.containsEntry("x-request-purpose", " \u00A0 ");
		assertThat(list(example("InvalidPurposeUnicodeCategories").get("x-rejected-unicode-categories")))
				.containsExactly("Cc", "Cf", "Zl", "Zp");
		assertInvalidError("InvalidPurposeEmptyAfterBoundaries", "INVALID_PURPOSE", "purpose");
		assertInvalidError("InvalidPurposeUnicodeCategories", "INVALID_PURPOSE", "purpose");
		assertInvalidError("InvalidExpectedVersion", "INVALID_VERSION", "expectedVersion");
		assertInvalidError("InvalidSourceExpectedVersion", "INVALID_VERSION", "sourceExpectedVersion");
		assertInvalidError("InvalidDestinationExpectedVersion", "INVALID_VERSION", "destinationExpectedVersion");
		assertInvalidError("InvalidIfMatchVersion", "INVALID_VERSION", "If-Match");
	}

	@Test
	void requiresTheReadTimeBooleanOnProgressAndRejectsItOnCompletion() {
		Map<String, Object> progressFalse = value("SharedProgressAdjustmentFalse");
		Map<String, Object> progressTrue = value("SharedProgressAdjustmentTrue");
		Map<String, Object> completion = value("SharedCompletion");
		Map<String, Object> abandonment = value("SharedAbandonmentFunded");
		assertThat(progressFalse).containsEntry("balanceAdjustmentInProgress", false);
		assertThat(progressTrue).containsEntry("balanceAdjustmentInProgress", true);
		assertThat(completion).doesNotContainKeys("balanceAdjustmentInProgress", "adjustmentStatus");
		assertThat(abandonment).doesNotContainKeys("balanceAdjustmentInProgress", "adjustmentStatus");

		Map<String, Object> missingBoolean = new LinkedHashMap<>(progressFalse);
		missingBoolean.remove("balanceAdjustmentInProgress");
		assertThat(validate(missingBoolean, schema("ProgressSharedCard"), "$"))
				.as("Progress must require balanceAdjustmentInProgress").isNotEmpty();

		Map<String, Object> staleEnum = new LinkedHashMap<>(progressTrue);
		staleEnum.put("adjustmentStatus", "OPEN");
		assertThat(validate(staleEnum, schema("ProgressSharedCard"), "$"))
				.as("the replaced adjustmentStatus contract must be rejected").isNotEmpty();

		Map<String, Object> completionWithBoolean = new LinkedHashMap<>(completion);
		completionWithBoolean.put("balanceAdjustmentInProgress", true);
		assertThat(validate(completionWithBoolean, schema("CompletionSharedCard"), "$"))
				.as("Completion must reject the Progress-only boolean").isNotEmpty();

		Map<String, Object> abandonmentWithBoolean = new LinkedHashMap<>(abandonment);
		abandonmentWithBoolean.put("balanceAdjustmentInProgress", true);
		assertThat(validate(abandonmentWithBoolean, schema("AbandonmentSharedCard"), "$"))
				.as("Abandonment must reject the read-time boolean and owner state").isNotEmpty();
	}

	@Test
	void keepsSharedCardsPubliclyUsefulWithoutLeakingOwnerState() {
		for (String name : List.of("SharedProgressAdjustmentFalse", "SharedProgressAdjustmentTrue", "SharedCompletion",
				"SharedAbandonmentFunded", "SharedAbandonmentZeroFunded", "SharedAbandonmentFullTarget")) {
			Map<String, Object> card = value(name);
			assertThat(card).as(name).containsKey("targetAmount");
			Set<String> observedFields = new HashSet<>();
			collectFieldNames(card, observedFields);
			assertThat(observedFields).as(name + " privacy").doesNotContainAnyElementsOf(FORBIDDEN_SHARED_CARD_FIELDS);
		}
	}

	@Test
	void freezesAbandonmentProgressWithoutLeakingHistoricalAmountOrCurrentAllocation() {
		Map<String, Object> funded = value("SharedAbandonmentFunded");
		Map<String, Object> zero = value("SharedAbandonmentZeroFunded");
		Map<String, Object> fullTarget = value("SharedAbandonmentFullTarget");

		assertThat(funded).containsEntry("kind", "ABANDONMENT").containsEntry("state", "ABANDONED")
				.containsEntry("progressPercent", 47);
		assertThat(zero).containsEntry("kind", "ABANDONMENT").containsEntry("state", "ABANDONED")
				.containsEntry("progressPercent", 0);
		assertThat(fullTarget).containsEntry("kind", "ABANDONMENT").containsEntry("state", "ABANDONED")
				.containsEntry("progressPercent", 100);
		for (Map<String, Object> card : List.of(funded, zero, fullTarget)) {
			assertThat(validate(card, schema("SharedCard"), "$"))
					.as("ABANDONMENT must be an exact closed SharedCard arm").isEmpty();
			assertThat(card.keySet()).doesNotContainAnyElementsOf(Set.of(
					"abandonmentAmount", "amount", "wishId", "cardBalanceAccountId", "abandonedAt", "closedAt",
					"balanceAdjustmentInProgress", "ledgerEventId", "recommendationScore"));
		}

		Map<String, Object> leakedAmount = new LinkedHashMap<>(funded);
		leakedAmount.put("abandonmentAmount", 470000);
		assertThat(validate(leakedAmount, schema("AbandonmentSharedCard"), "$"))
				.as("exact historical KRW is never public").isNotEmpty();
		Map<String, Object> currentAllocation = new LinkedHashMap<>(zero);
		currentAllocation.put("amount", 0);
		assertThat(validate(currentAllocation, schema("AbandonmentSharedCard"), "$"))
				.as("post-abandonment current allocation is never public").isNotEmpty();

		for (String name : List.of("SharedAbandonmentFundedPage", "SharedAbandonmentZeroFundedPage",
				"SharedAbandonmentFullTargetPage")) {
			Map<String, Object> item = map(list(value(name).get("items")).getFirst());
			assertThat(validate(item, schema("SharedCard"), "$"))
					.as(name + " page item is the ABANDONMENT union arm").isEmpty();
			assertThat(item.get("sharedCardId")).isIn(funded.get("sharedCardId"), zero.get("sharedCardId"),
					fullTarget.get("sharedCardId"));
		}
	}

	@Test
	void preservesOneCardChangeIdentityAndAdjustmentLinkAcrossBothHistoryViews() {
		Map<String, Object> cardChange = value("CardBalanceChangeExample");
		Map<String, Object> accountChange = value("AccountCardBalanceChangeExample");

		assertThat(cardChange.get("eventId")).isEqualTo(accountChange.get("eventId"));
		assertThat(cardChange.get("observationId")).isEqualTo(accountChange.get("observationId"));
		assertThat(cardChange.get("actualCardBalanceDelta"))
				.isEqualTo(accountChange.get("actualCardBalanceDelta"));
		assertThat(cardChange.get("balanceAdjustment"))
				.isEqualTo(accountChange.get("balanceAdjustment"));
		assertThat(accountChange).containsEntry("accountAvailableBalanceAfter", -25000);

		Map<String, Object> zeroDelta = new LinkedHashMap<>(cardChange);
		zeroDelta.put("actualCardBalanceDelta", 0);
		assertThat(validate(zeroDelta, schema("CardBalanceChange"), "$"))
				.as("zero-delta successful observations are not money-history items")
				.isNotEmpty();
	}

	@Test
	void representsOneTransferAsOneAccountItemAndTwoOppositeWishEffects() {
		Map<String, Object> accountTransfer = value("AccountWishTransferExample");
		Map<String, Object> sourcePage = value("WishTransferSourcePageExample");
		Map<String, Object> destinationPage = value("WishTransferDestinationPageExample");
		Map<String, Object> source = map(list(sourcePage.get("items")).getFirst());
		Map<String, Object> destination = map(list(destinationPage.get("items")).getFirst());

		assertThat(source.get("eventId")).isEqualTo(accountTransfer.get("eventId"));
		assertThat(destination.get("eventId")).isEqualTo(accountTransfer.get("eventId"));
		assertThat(accountTransfer).containsEntry("accountAvailableBalanceDelta", 0)
				.containsEntry("amount", 30000);
		assertThat(source).containsEntry("direction", "SOURCE")
				.containsEntry("wishAmountDelta", -30000);
		assertThat(destination).containsEntry("direction", "DESTINATION")
				.containsEntry("wishAmountDelta", 30000);
		assertThat(map(source.get("counterpartyWish")).get("wishId"))
				.isEqualTo(map(destinationPage.get("wish")).get("wishId"));
		assertThat(map(destination.get("counterpartyWish")).get("wishId"))
				.isEqualTo(map(sourcePage.get("wish")).get("wishId"));
	}

	@Test
	void keepsOwnedTombstoneHistoryReadableWithoutFabricatingADetailLink() {
		Map<String, Object> deletedPage = value("DeletedWishHistoryPageExample");
		Map<String, Object> deletedSubject = map(deletedPage.get("wish"));
		Map<String, Object> deletionEvent = map(list(deletedPage.get("items")).getFirst());
		Map<String, Object> adjustment = map(deletionEvent.get("balanceAdjustment"));

		assertThat(deletedSubject).containsEntry("displayPurpose", "새 노트북")
				.containsEntry("deletedWish", true)
				.containsEntry("detailAvailable", false);
		assertThat(deletionEvent).containsEntry("eventType", "WISH_DELETION_RETURN")
				.containsEntry("wishPurposeSnapshot", "새 노트북")
				.containsEntry("wishAmountAfter", 0);
		assertThat(adjustment).containsEntry("eventRole", "RESOLUTION")
				.containsEntry("sequenceNumber", 2);

		Set<String> observedFields = new HashSet<>();
		collectFieldNames(deletedPage, observedFields);
		assertThat(observedFields).doesNotContain("href", "url", "detailPath");

		Map<String, Object> emptyDeletedPage = value("EmptyDeletedWishHistoryPageExample");
		assertThat(map(emptyDeletedPage.get("wish"))).containsEntry("deletedWish", true)
				.containsEntry("detailAvailable", false);
		assertThat(list(emptyDeletedPage.get("items"))).isEmpty();
		assertThat(emptyDeletedPage).containsEntry("nextCursor", null);
	}

	@Test
	void validatesAllNullableDateCombinationsAndRejectsMissingOrMalformedAuthorFields() {
		for (String kind : List.of("Progress", "Completion")) {
			Set<String> combinations = new HashSet<>();
			for (String suffix : List.of("Neither", "StartOnly", "TargetOnly", "Both")) {
				Map<String, Object> card = value("Shared" + kind + "Dates" + suffix);
				assertThat(validate(card, schema(kind + "SharedCard"), "$" )).isEmpty();
				combinations.add((card.get("startDate") != null) + ":" + (card.get("targetDate") != null));
				for (String required : List.of("ownerId", "startDate", "targetDate")) {
					Map<String, Object> missing = new LinkedHashMap<>(card);
					missing.remove(required);
					assertThat(validate(missing, schema(kind + "SharedCard"), "$" )).isNotEmpty();
				}
				for (String field : List.of("ownerId", "startDate", "targetDate")) {
					Map<String, Object> malformed = new LinkedHashMap<>(card);
					malformed.put(field, "invalid");
					assertThat(validate(malformed, schema(kind + "SharedCard"), "$" )).isNotEmpty();
				}
			}
			assertThat(combinations).containsExactlyInAnyOrder("false:false", "true:false", "false:true", "true:true");
		}
		List<Object> sameAuthor = list(value("SharedSameAuthorPage").get("items"));
		assertThat(sameAuthor).hasSize(2).extracting(card -> map(card).get("ownerId"))
				.containsOnly("33333333-3333-4333-8333-333333333333");
		assertThat(sameAuthor).extracting(card -> map(card).get("sharedCardId")).doesNotHaveDuplicates();
		List<Object> namesakes = list(value("SharedSameNicknameAuthorsPage").get("items"));
		assertThat(namesakes).extracting(card -> map(card).get("ownerNickname")).containsOnly("rabbit");
		assertThat(namesakes).extracting(card -> map(card).get("ownerId")).doesNotHaveDuplicates();
		assertThat(value("SharedAuthorEmptyPage")).containsEntry("items", List.of()).containsEntry("nextCursor", null);
		assertThat(value("AcademyStudentLookup")).containsEntry("isFollowing", true).containsEntry("isFollowedBy", false);
		assertThat(value("AcademySelfLookup")).containsEntry("isFollowing", false).containsEntry("isFollowedBy", false);
		assertThat(value("AcademyStudentLookup").get("studentId")).isEqualTo(map(sameAuthor.getFirst()).get("ownerId"));
	}

	@Test
	void validatesAuthorAndSharedCardResponseExamplesAgainstTheirOperationResponseSchema() {
		map(document.get("paths")).forEach((pathName, rawPath) -> map(rawPath).forEach((method, rawOperation) -> {
			if (!(rawOperation instanceof Map<?, ?>)) {
				return;
			}
			Map<String, Object> operation = map(rawOperation);
			if (!Set.of("listAcademySharedCards", "getAcademySharedCard", "getAcademyStudent")
					.contains(operation.get("operationId"))) {
				return;
			}
			map(operation.get("responses")).forEach((status, rawResponse) -> {
				Map<String, Object> response = resolveObject(rawResponse);
				map(response.get("content")).forEach((mediaType, rawMedia) -> {
					Map<String, Object> media = map(rawMedia);
					map(media.get("examples")).forEach((exampleName, rawExample) -> {
						Map<String, Object> example = resolveObject(rawExample);
						assertThat(validate(example.get("value"), map(media.get("schema")), "$"))
								.as(method + " " + pathName + " " + status + " " + exampleName).isEmpty();
					});
				});
			});
		}));
		Map<String, Object> sharedResponse = map(path("paths", "/v1/academies/{academyId}/shared-cards/{cardId}",
				"get", "responses", "200", "content", "application/json", "examples"));
		for (String kind : List.of("progress", "completion")) {
			assertThat(sharedResponse).containsKeys(kind + "-dates-neither", kind + "-dates-startonly",
					kind + "-dates-targetonly", kind + "-dates-both");
		}
	}

	private static Map<String, Object> resolveObject(Object value) {
		Map<String, Object> object = map(value);
		return object.containsKey("$ref") ? map(resolve(object.get("$ref").toString())) : object;
	}

	@Test
	void allDirectAndNestedSharedCardExamplesCarryOnlyThePublicAuthorContract() {
		List<Map<String, Object>> cards = new ArrayList<>();
		examples.values().forEach(example -> collectSharedCards(map(example).get("value"), cards));
		assertThat(cards).hasSizeGreaterThanOrEqualTo(19);
		for (Map<String, Object> card : cards) {
			assertThat(card).containsKeys("ownerId", "startDate", "targetDate");
			Set<String> fields = new HashSet<>();
			collectFieldNames(card, fields);
			assertThat(fields).doesNotContainAnyElementsOf(FORBIDDEN_SHARED_CARD_FIELDS);
		}
	}

	private static void collectSharedCards(Object value, List<Map<String, Object>> cards) {
		if (value instanceof Map<?, ?> object) {
			if (object.containsKey("sharedCardId") && object.containsKey("kind")) {
				cards.add(map(object));
			}
			object.values().forEach(child -> collectSharedCards(child, cards));
		} else if (value instanceof List<?> array) {
			array.forEach(child -> collectSharedCards(child, cards));
		}
	}

	private static Map<String, Object> example(String name) {
		return map(examples.get(name));
	}

	private static Map<String, Object> value(String name) {
		return map(example(name).get("value"));
	}

	private static Map<String, Object> requestExamples(String operationId, String mediaType) {
		Map<String, Object> paths = map(document.get("paths"));
		Map<String, Object> operation = paths.values().stream()
				.map(OpenApiExamplesTest::map)
				.flatMap(pathItem -> pathItem.values().stream())
				.filter(Map.class::isInstance)
				.map(OpenApiExamplesTest::map)
				.filter(candidate -> operationId.equals(candidate.get("operationId")))
				.findFirst()
				.orElseThrow();
		Map<String, Object> requestBody = map(operation.get("requestBody"));
		Map<String, Object> content = map(requestBody.get("content"));
		return map(map(content.get(mediaType)).get("examples"));
	}

	private static void assertInvalidError(String name, String code, String field) {
		Map<String, Object> error = map(value(name).get("error"));
		assertThat(error).containsEntry("code", code).containsEntry("retryable", false);
		if ("INVALID_VERSION".equals(code)) {
			assertThat(example(name)).containsEntry("x-request-value", -1);
		}
		assertThat(list(error.get("fieldErrors"))).singleElement()
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.containsEntry("field", field);
	}

	private static Map<String, Object> schema(String name) {
		return map(path("components", "schemas", name));
	}

	private static List<String> validate(Object value, Map<String, Object> schema, String location) {
		List<String> errors = new ArrayList<>();
		if (schema.containsKey("$ref")) {
			Object resolved = resolve(schema.get("$ref").toString());
			if (resolved == null) {
				return List.of(location + " unresolved ref " + schema.get("$ref"));
			}
			return validate(value, map(resolved), schema.get("$ref").toString());
		}

		if (schema.containsKey("oneOf")) {
			long matches = list(schema.get("oneOf")).stream()
					.map(OpenApiExamplesTest::map)
					.filter(candidate -> validate(value, candidate, location).isEmpty())
					.count();
			if (matches != 1) {
				errors.add(location + " matched " + matches + " oneOf branches");
			}
		}
		if (schema.containsKey("anyOf")) {
			boolean matches = list(schema.get("anyOf")).stream()
					.map(OpenApiExamplesTest::map)
					.anyMatch(candidate -> validate(value, candidate, location).isEmpty());
			if (!matches) {
				errors.add(location + " matched no anyOf branch");
			}
		}
		if (schema.containsKey("allOf")) {
			list(schema.get("allOf")).stream()
					.map(OpenApiExamplesTest::map)
					.forEach(candidate -> errors.addAll(validate(value, candidate, location)));
		}
		if (schema.containsKey("if")) {
			String branch = validate(value, map(schema.get("if")), location).isEmpty() ? "then" : "else";
			if (schema.containsKey(branch)) {
				errors.addAll(validate(value, map(schema.get(branch)), location));
			}
		}
		if (schema.containsKey("not") && validate(value, map(schema.get("not")), location).isEmpty()) {
			errors.add(location + " matched a forbidden schema");
		}

		Object rawType = schema.get("type");
		if (rawType != null && !matchesType(value, rawType)) {
			errors.add(location + " expected type " + rawType + " but was "
					+ (value == null ? "null" : value.getClass().getSimpleName()));
			return errors;
		}
		if (schema.containsKey("enum") && !list(schema.get("enum")).contains(value)) {
			errors.add(location + " not in enum " + schema.get("enum"));
		}
		if (schema.containsKey("const") && !Objects.equals(schema.get("const"), value)) {
			errors.add(location + " expected const " + schema.get("const"));
		}

		if (value instanceof Map<?, ?> rawObject) {
			Map<String, Object> object = map(rawObject);
			for (Object required : list(schema.get("required"))) {
				if (!object.containsKey(required.toString())) {
					errors.add(location + " missing " + required);
				}
			}
			Map<String, Object> properties = map(schema.get("properties"));
			object.forEach((name, child) -> {
				if (properties.containsKey(name)) {
					errors.addAll(validate(child, map(properties.get(name)), location + "." + name));
				} else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
					errors.add(location + " rejects additional property " + name);
				}
			});
		}

		if (value instanceof List<?> array) {
			if (schema.get("minItems") instanceof Number minimum && array.size() < minimum.intValue()) {
				errors.add(location + " fewer items than " + minimum);
			}
			if (schema.get("maxItems") instanceof Number maximum && array.size() > maximum.intValue()) {
				errors.add(location + " more items than " + maximum);
			}

			if (schema.containsKey("items")) {
				for (int index = 0; index < array.size(); index++) {
					errors.addAll(validate(array.get(index), map(schema.get("items")), location + "[" + index + "]"));
				}
			}
			if (Boolean.TRUE.equals(schema.get("uniqueItems")) && new HashSet<>(array).size() != array.size()) {
				errors.add(location + " contains duplicate items");
			}
		}

		if (value instanceof String string) {
			if (schema.get("minLength") instanceof Number minimum && string.codePointCount(0, string.length()) < minimum.intValue()) {
				errors.add(location + " is shorter than " + minimum);
			}
			if (schema.get("maxLength") instanceof Number maximum && string.codePointCount(0, string.length()) > maximum.intValue()) {
				errors.add(location + " is longer than " + maximum);
			}
			if (schema.get("pattern") instanceof String pattern && !Pattern.compile(pattern).matcher(string).find()) {
				errors.add(location + " does not match " + pattern);
			}
			validateFormat(string, schema.get("format"), location, errors);
		}

		if (isInteger(value)) {
			BigDecimal number = new BigDecimal(value.toString());
			if (schema.get("minimum") instanceof Number minimum
					&& number.compareTo(new BigDecimal(minimum.toString())) < 0) {
				errors.add(location + " is below minimum " + minimum);
			}
			if (schema.get("maximum") instanceof Number maximum
					&& number.compareTo(new BigDecimal(maximum.toString())) > 0) {
				errors.add(location + " is above maximum " + maximum);
			}
		}
		return errors;
	}

	private static boolean matchesType(Object value, Object rawType) {
		if (rawType instanceof List<?> allowed) {
			return allowed.stream().anyMatch(type -> matchesType(value, type));
		}
		return switch (rawType.toString()) {
			case "null" -> value == null;
			case "object" -> value instanceof Map<?, ?>;
			case "array" -> value instanceof List<?>;
			case "string" -> value instanceof String;
			case "integer" -> isInteger(value);
			case "number" -> value instanceof Number;
			case "boolean" -> value instanceof Boolean;
			default -> false;
		};
	}

	private static boolean isInteger(Object value) {
		return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
				|| value instanceof java.math.BigInteger;
	}

	private static void validateFormat(String value, Object rawFormat, String location, List<String> errors) {
		if (rawFormat == null) {
			return;
		}
		try {
			switch (rawFormat.toString()) {
				case "uuid" -> UUID.fromString(value);
				case "date" -> LocalDate.parse(value);
				case "date-time" -> OffsetDateTime.parse(value);
				default -> { }
			}
		} catch (RuntimeException invalid) {
			errors.add(location + " is not a valid " + rawFormat);
		}
	}

	private static void collectFieldNames(Object value, Set<String> names) {
		if (value instanceof Map<?, ?> object) {
			object.forEach((key, child) -> {
				names.add(key.toString());
				collectFieldNames(child, names);
			});
		} else if (value instanceof List<?> array) {
			array.forEach(child -> collectFieldNames(child, names));
		}
	}

	private static Object path(String... segments) {
		Object current = document;
		for (String segment : segments) {
			current = map(current).get(segment);
		}
		return current;
	}

	private static Object resolve(String ref) {
		Object current = document;
		for (String encoded : ref.substring(2).split("/")) {
			String segment = encoded.replace("~1", "/").replace("~0", "~");
			if (!(current instanceof Map<?, ?> currentMap) || !currentMap.containsKey(segment)) {
				return null;
			}
			current = currentMap.get(segment);
		}
		return current;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value == null ? Map.of() : (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return value == null ? List.of() : (List<Object>) value;
	}
}
