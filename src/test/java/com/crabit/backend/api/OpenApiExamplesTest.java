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
			Map.entry("StudentRelationshipSearchPage", "모든 관계 상태가 포함된 친구 검색 결과"),
			Map.entry("EmptyStudentRelationshipPage", "친구 검색 빈 페이지"),
			Map.entry("FriendPageExample", "현재 친구 목록 페이지"),
			Map.entry("EmptyFriendPage", "현재 친구 목록 빈 페이지"),
			Map.entry("FriendRequestCreated", "PENDING 친구 요청 생성"),
			Map.entry("SentFriendRequestPage", "보낸 PENDING 친구 요청 페이지"),
			Map.entry("ReceivedFriendRequestPage", "받은 PENDING 친구 요청 페이지"),
			Map.entry("EmptyFriendRequestPage", "PENDING 친구 요청 빈 페이지"),
			Map.entry("FriendRequestCanceled", "친구 요청 취소"),
			Map.entry("FriendAccepted", "친구 요청 수락 및 친구 관계 생성"),
			Map.entry("FriendRequestRejected", "친구 요청 거절"),
			Map.entry("StudentBlockCreated", "학생 차단 생성"),
			Map.entry("StudentBlockPageExample", "활성 학생 차단 페이지"),
			Map.entry("EmptyStudentBlockPage", "활성 학생 블록 빈 페이지"),
			Map.entry("MalformedFriendManagementUuid", "친구 관리 잘못된 UUID"),
			Map.entry("MalformedFriendManagementNickname", "친구 관리 잘못된 닉네임"),
			Map.entry("MalformedFriendManagementLimit", "친구 관리 잘못된 limit"),
			Map.entry("MalformedFriendManagementCursor", "친구 관리 잘못된 커서"),
			Map.entry("AuthRequiredFriendManagement", "친구 관리 인증 필요"),
			Map.entry("ForbiddenFriendManagement", "친구 관리 접근 금지"),
			Map.entry("AcademyNotFoundFriendManagement", "친구 관리 학원 없음"),
			Map.entry("StudentNotFoundCrossAcademy", "친구 관리 다른 학원 학생 숨김"),
			Map.entry("StudentNotFoundBlocked", "친구 관리 양방향 차단 학생 숨김"),
			Map.entry("FriendshipNotFoundFriendManagement", "친구 관리 친구 관계 없음"),
			Map.entry("FriendRequestNotFoundUnauthorized", "친구 관리 권한 없는 요청 숨김"),
			Map.entry("StudentBlockNotFoundFriendManagement", "친구 관리 학생 차단 없음"),
			Map.entry("SelfRelationshipConflict", "친구 관리 자기 자신 관계 충돌"),
			Map.entry("AlreadyFriendsConflict", "친구 관리 이미 친구인 관계 충돌"),
			Map.entry("FriendRequestAlreadyPendingConflict", "친구 관리 이미 PENDING인 요청 충돌"),
			Map.entry("IncomingFriendRequestPendingConflict", "친구 관리 반대 방향 PENDING 요청 충돌"),
			Map.entry("FriendRequestNotPendingConflict", "친구 관리 PENDING이 아닌 요청 충돌"),
			Map.entry("FriendRequestNotActionableConflict", "친구 관리 처리할 수 없는 요청 충돌"),
			Map.entry("StudentBlockAlreadyActiveConflict", "친구 관리 이미 활성인 학생 차단 충돌"),
			Map.entry("UnknownBalancePage", "UNKNOWN 잔액 페이지"),
			Map.entry("FailedRefreshKnownBalance", "후속 조회 실패 후 유지된 KNOWN 잔액"),
			Map.entry("EmptyWishPage", "빈 위시 페이지"),
			Map.entry("WishCreatedPrivateZero", "적립금 0인 PRIVATE 위시 생성"),
			Map.entry("IdempotentReplay", "멱등 재생"),
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
			Map.entry("InvalidExpectedVersion", "유효하지 않은 expectedVersion"),
			Map.entry("InvalidSourceExpectedVersion", "유효하지 않은 sourceExpectedVersion"),
			Map.entry("InvalidDestinationExpectedVersion", "유효하지 않은 destinationExpectedVersion"),
			Map.entry("InvalidIfMatchVersion", "유효하지 않은 If-Match 버전"),
			Map.entry("SharedProgressAdjustmentFalse", "잔액 조정 건이 없는 진행 공유 카드"),
			Map.entry("SharedProgressAdjustmentTrue", "잔액 조정 건이 열린 진행 공유 카드"),
			Map.entry("SharedCompletion", "잔액 조정 필드가 없는 완료 공유 카드"));

	private static final Set<String> FORBIDDEN_SHARED_CARD_FIELDS = Set.of(
			"wishId", "cardBalanceAccountId", "studentId", "physicalCardId", "physicalCardNumber",
			"actualCardBalance", "ledgerAvailableBalance", "displayAvailableBalance", "amount",
			"fundMovements", "cardBalanceChanges", "adjustmentStatus");

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
	void includesTheRequiredNullClosureInstantInEveryActiveWishExample() {
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
			assertThat(wish).containsEntry("completedAt", null).containsEntry("closedAt", null);
		});
		assertThat(example("IdempotentReplay").get("description").toString())
				.contains("closedAt", "최초 결과에 캡처된 값");

		Map<String, Object> missingClosure = new LinkedHashMap<>(created);
		missingClosure.remove("closedAt");
		assertThat(validate(missingClosure, schema("Wish"), "$"))
				.as("closedAt is required even while its active-state value is null")
				.isNotEmpty();

		Map<String, Object> leakedInternalInstant = new LinkedHashMap<>(created);
		leakedInternalInstant.put("abandonedAt", "2026-08-16T02:10:00Z");
		assertThat(validate(leakedInternalInstant, schema("Wish"), "$"))
				.as("the internal abandonment timestamp must not be public")
				.isNotEmpty();
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
		assertThat(progressFalse).containsEntry("balanceAdjustmentInProgress", false);
		assertThat(progressTrue).containsEntry("balanceAdjustmentInProgress", true);
		assertThat(completion).doesNotContainKeys("balanceAdjustmentInProgress", "adjustmentStatus");

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
	}

	@Test
	void keepsSharedCardsPubliclyUsefulWithoutLeakingOwnerState() {
		for (String name : List.of("SharedProgressAdjustmentFalse", "SharedProgressAdjustmentTrue", "SharedCompletion")) {
			Map<String, Object> card = value(name);
			assertThat(card).as(name).containsKey("targetAmount");
			Set<String> observedFields = new HashSet<>();
			collectFieldNames(card, observedFields);
			assertThat(observedFields).as(name + " privacy").doesNotContainAnyElementsOf(FORBIDDEN_SHARED_CARD_FIELDS);
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

	private static Map<String, Object> example(String name) {
		return map(examples.get(name));
	}

	private static Map<String, Object> value(String name) {
		return map(example(name).get("value"));
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
