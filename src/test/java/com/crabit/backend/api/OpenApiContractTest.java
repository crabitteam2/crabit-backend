package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.crabit.backend.wish.BalanceLookupMethod;
import com.crabit.backend.wish.LedgerEventType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiContractTest {

	private static final Path CONTRACT = Path.of("api", "openapi.yaml");
	private static final Set<String> HTTP_METHODS = Set.of(
			"get", "post", "put", "patch", "delete", "options", "head", "trace");

	private static Map<String, Object> document;
	private static Map<String, Operation> operations;

	@BeforeAll
	static void parseContract() throws IOException {
		try (InputStream input = Files.newInputStream(CONTRACT)) {
			document = map(new Yaml().load(input));
		}
		operations = collectOperations(document);
	}

	@Test
	void materializesTheExactApprovedOperationInventoryWithoutParserErrors() {
		assertThat(document.get("openapi")).isEqualTo("3.1.0");
		assertThat(map(document.get("info")).get("version")).isEqualTo("0.0.1");
		assertThat(operations).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
				entry("uploadWishPhoto", "POST", "/v1/wish-photos"),
				entry("deletePendingWishPhoto", "DELETE", "/v1/wish-photos/{photoId}"),
				entry("listMyCardBalanceAccounts", "GET", "/v1/me/card-balance-accounts"),
				entry("getCardBalanceAccount", "GET", "/v1/card-balance-accounts/{cardBalanceAccountId}"),
				entry("refreshCardBalance", "POST", "/v1/card-balance-accounts/{cardBalanceAccountId}/balance-refreshes"),
				entry("getRepresentativeWish", "GET", "/v1/card-balance-accounts/{cardBalanceAccountId}/representative-wish"),
				entry("selectRepresentativeWish", "PUT", "/v1/card-balance-accounts/{cardBalanceAccountId}/representative-wish"),
				entry("listCardBalanceChanges", "GET", "/v1/card-balance-accounts/{cardBalanceAccountId}/card-balance-changes"),
				entry("listAccountFundMovements", "GET", "/v1/card-balance-accounts/{cardBalanceAccountId}/fund-movements"),
				entry("listWishes", "GET", "/v1/card-balance-accounts/{cardBalanceAccountId}/wishes"),
				entry("createWish", "POST", "/v1/card-balance-accounts/{cardBalanceAccountId}/wishes"),
				entry("getWish", "GET", "/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}"),
				entry("patchWish", "PATCH", "/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}"),
				entry("deleteWish", "DELETE", "/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}"),
				entry("depositToWish", "POST", "/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/deposits"),
				entry("withdrawFromWish", "POST", "/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/withdrawals"),
				entry("transferWishFunds", "POST", "/v1/card-balance-accounts/{cardBalanceAccountId}/transfers"),
				entry("completeWish", "POST", "/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/completion"),
				entry("abandonWish", "POST", "/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/abandonment"),
				entry("listWishFundMovements", "GET", "/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/fund-movements"),
				entry("listAcademySharedCards", "GET", "/v1/academies/{academyId}/shared-cards"),
				entry("getAcademySharedCard", "GET", "/v1/academies/{academyId}/shared-cards/{cardId}"),
				entry("searchAcademyStudents", "GET", "/v1/academies/{academyId}/students"),
				entry("listAcademyFriends", "GET", "/v1/academies/{academyId}/friends"),
				entry("unfriendAcademyStudent", "DELETE", "/v1/academies/{academyId}/friends/{studentId}"),
				entry("sendFriendRequest", "POST", "/v1/academies/{academyId}/friend-requests"),
				entry("listSentFriendRequests", "GET", "/v1/academies/{academyId}/friend-requests/sent"),
				entry("listReceivedFriendRequests", "GET", "/v1/academies/{academyId}/friend-requests/received"),
				entry("cancelFriendRequest", "DELETE", "/v1/academies/{academyId}/friend-requests/{friendRequestId}"),
				entry("acceptFriendRequest", "POST", "/v1/academies/{academyId}/friend-requests/{friendRequestId}/acceptance"),
				entry("rejectFriendRequest", "POST", "/v1/academies/{academyId}/friend-requests/{friendRequestId}/rejection"),
				entry("listMyStudentBlocks", "GET", "/v1/me/student-blocks"),
				entry("blockStudent", "POST", "/v1/me/student-blocks"),
				entry("unblockStudent", "DELETE", "/v1/me/student-blocks/{studentId}")));
		assertThat(operations).hasSize(34);
	}

	@Test
	void requiresSyntheticBearerAndTheApprovedStatusInventoryOnEveryOperation() throws IOException {
		Map<String, Object> securitySchemes = map(path("components", "securitySchemes"));
		assertThat(securitySchemes).containsOnlyKeys("SyntheticBearer").doesNotContainKey("SeedBearer");
		Map<String, Object> scheme = map(securitySchemes.get("SyntheticBearer"));
		assertThat(scheme).containsEntry("type", "http").containsEntry("scheme", "bearer")
				.containsEntry("bearerFormat", "opaque-synthetic-token")
				.containsEntry("description", "불투명한 합성 인증 주체 토큰입니다. 알려진 토큰은 학생 또는 인증된 비학생 "
						+ "교직원 인증 주체를 식별합니다. 토큰 발급·갱신, 페르소나 선택, 테스트 픽스처 제어는 이 계약의 범위 밖입니다.");
		assertThat(Files.readString(CONTRACT)).doesNotContain("SeedBearer", "opaque-seed-token");

		operations.forEach((operationId, operation) -> {
			assertThat(list(operation.body().get("security")))
					.as(operationId + " security")
					.containsExactly(Map.of("SyntheticBearer", List.of()));
			Set<String> statuses = map(operation.body().get("responses")).keySet();
			assertThat(statuses).as(operationId + " authentication errors").contains("401", "403");
		});

		Map<String, Set<String>> expected = new LinkedHashMap<>();
		expected.put("uploadWishPhoto", Set.of("201", "400", "401", "403", "409", "413", "415", "422", "429", "503"));
		expected.put("deletePendingWishPhoto", Set.of("204", "400", "401", "403", "404", "409"));
		expected.put("listMyCardBalanceAccounts", Set.of("200", "401", "403"));
		expected.put("getCardBalanceAccount", Set.of("200", "401", "403", "404"));
		expected.put("refreshCardBalance", Set.of("200", "401", "403", "404", "503"));
		expected.put("getRepresentativeWish", Set.of("200", "204", "400", "401", "403", "404", "503"));
		expected.put("selectRepresentativeWish", Set.of("200", "400", "401", "403", "404", "409", "415", "503"));
		expected.put("listCardBalanceChanges", Set.of("200", "400", "401", "403", "404"));
		expected.put("listAccountFundMovements", Set.of("200", "400", "401", "403", "404"));
		expected.put("listWishes", Set.of("200", "400", "401", "403", "404", "503"));
		expected.put("createWish", Set.of("201", "400", "401", "403", "404", "409", "415", "422", "503"));
		expected.put("getWish", Set.of("200", "400", "401", "403", "404", "503"));
		expected.put("patchWish", Set.of("200", "400", "401", "403", "404", "409", "415", "422", "503"));
		expected.put("deleteWish", Set.of("200", "400", "401", "403", "404", "409", "422", "503"));
		expected.put("depositToWish", Set.of("200", "400", "401", "403", "404", "409", "422", "503"));
		expected.put("withdrawFromWish", Set.of("200", "400", "401", "403", "404", "409", "422", "503"));
		expected.put("transferWishFunds", Set.of("200", "400", "401", "403", "404", "409", "422", "503"));
		expected.put("completeWish", Set.of("200", "400", "401", "403", "404", "409", "415", "422", "503"));
		expected.put("abandonWish", Set.of("200", "400", "401", "403", "404", "409", "415", "422", "503"));
		expected.put("listWishFundMovements", Set.of("200", "400", "401", "403", "404"));
		expected.put("listAcademySharedCards", Set.of("200", "400", "401", "403", "404", "503"));
		expected.put("getAcademySharedCard", Set.of("200", "401", "403", "404", "503"));
		expected.put("searchAcademyStudents", Set.of("200", "400", "401", "403", "404"));
		expected.put("listAcademyFriends", Set.of("200", "400", "401", "403", "404"));
		expected.put("unfriendAcademyStudent", Set.of("204", "400", "401", "403", "404"));
		expected.put("sendFriendRequest", Set.of("201", "400", "401", "403", "404", "409"));
		expected.put("listSentFriendRequests", Set.of("200", "400", "401", "403", "404"));
		expected.put("listReceivedFriendRequests", Set.of("200", "400", "401", "403", "404"));
		expected.put("cancelFriendRequest", Set.of("200", "400", "401", "403", "404", "409"));
		expected.put("acceptFriendRequest", Set.of("200", "400", "401", "403", "404", "409"));
		expected.put("rejectFriendRequest", Set.of("200", "400", "401", "403", "404", "409"));
		expected.put("listMyStudentBlocks", Set.of("200", "400", "401", "403"));
		expected.put("blockStudent", Set.of("201", "400", "401", "403", "404", "409"));
		expected.put("unblockStudent", Set.of("204", "400", "401", "403", "404"));

		expected.forEach((operationId, statuses) -> assertThat(map(operations.get(operationId).body().get("responses")).keySet())
				.as(operationId + " statuses").containsExactlyInAnyOrderElementsOf(statuses));
	}

	@Test
	void materializesTheApprovedWishPhotoUploadAndPendingDeletionContract() {
		Map<String, Object> upload = operations.get("uploadWishPhoto").body();
		assertThat(upload).containsEntry("tags", List.of("Wishes"))
				.containsEntry("security", List.of(Map.of("SyntheticBearer", List.of())));
		assertThat(resolvedParameters(upload)).singleElement().satisfies(parameter -> {
			assertThat(parameter).containsEntry("name", "Idempotency-Key")
					.containsEntry("in", "header").containsEntry("required", true);
			assertThat(map(parameter.get("schema"))).containsEntry("type", "string")
					.containsEntry("minLength", 1).containsEntry("maxLength", 200);
		});

		Map<String, Object> multipart = map(map(map(upload.get("requestBody")).get("content"))
				.get("multipart/form-data"));
		assertThat(ref(map(multipart.get("schema"))))
				.isEqualTo("#/components/schemas/WishPhotoUploadRequest");
		assertThat(map(map(multipart.get("encoding")).get("photo")))
				.containsEntry("contentType", "image/jpeg");
		Map<String, Object> request = schema("WishPhotoUploadRequest");
		assertThat(request).containsEntry("type", "object")
				.containsEntry("additionalProperties", false);
		assertThat(list(request.get("required"))).containsExactly("photo");
		assertThat(map(map(request.get("properties")).get("photo")))
				.containsEntry("type", "string")
				.containsEntry("format", "binary")
				.containsEntry("contentMediaType", "image/jpeg")
				.containsEntry("x-maximum-bytes", 5_242_880)
				.containsEntry("x-required-dimensions", "1080x1080");
		assertThat(map(map(request.get("properties")).get("photo")).get("description").toString())
				.contains("변환 전 수신한 이 파트의 정확한 바이트",
						"multipart framing", "boundary", "filename은 포함하지 않습니다");

		Map<String, Object> success = resolvedResponse("uploadWishPhoto", "201");
		assertThat(ref(map(map(map(success.get("content")).get("application/json")).get("schema"))))
				.isEqualTo("#/components/schemas/WishPhoto");
		assertThat(map(success.get("headers"))).containsOnlyKeys(
				"Idempotency-Replayed", "Cache-Control");
		assertThat(map(upload.get("x-quotas")))
				.containsEntry("maximumUnattachedPendingPhotosPerStudent", 3)
				.containsEntry("maximumNewProcessingAttemptsPerStudentPerRollingHour", 20);
		assertThat(map(map(upload.get("x-processing-policy")).get("rejectContentSafety")))
				.containsEntry("categories", List.of("adult", "racy", "violence"))
				.containsEntry("likelihoods", List.of("LIKELY", "VERY_LIKELY"));
		assertThat(upload.get("description").toString()).contains(
				"multipart framing·boundary·filename을 제외",
				"최초 receipt 생성 업로드가 시작된 시점부터 정확히 24시간",
				"ACTIVE_SUCCESS", "REVOKED_SUCCESS", "WISH_PHOTO_EXPIRED",
				"PHOTO_DELIVERY_UNAVAILABLE", "request time이 retainUntil에 도달");

		Map<String, Object> receipt = map(upload.get("x-idempotency-receipt"));
		assertThat(list(receipt.get("scope"))).containsExactly("authenticatedOwner", "Idempotency-Key");
		assertThat(receipt.get("contentDigest").toString()).contains(
				"변환 전 수신 photo 파트 정확한 바이트", "SHA-256",
				"multipart framing", "boundary", "filename");
		assertThat(map(receipt.get("retention")))
				.containsEntry("duration", "PT24H")
				.containsEntry("startsAt", "initial-receipt-creating-upload-began")
				.containsEntry("retainedWhile", "requestTime < retainUntil")
				.containsEntry("expiresAtOrAfter", "requestTime >= retainUntil");
		assertThat(list(receipt.get("retainedFields"))).containsExactly(
				"owner", "idempotencyKey", "contentDigest", "outcome", "photoId");
		assertThat(list(receipt.get("outcomeKinds"))).containsExactly(
				"ACTIVE_SUCCESS", "REVOKED_SUCCESS", "PHOTO_TOO_LARGE", "UNSUPPORTED_PHOTO_TYPE",
				"INVALID_PHOTO", "PHOTO_CONTENT_NOT_ALLOWED");
		assertThat(list(receipt.get("forbiddenRetainedData"))).contains(
				"rawImageBytes", "transformedImageBytes", "signedUrls", "objectPath", "errorMessage",
				"contentSafetyCategory", "contentSafetyLikelihood", "providerPayload", "traceId");
		assertThat(list(receipt.get("notRetainedOutcomes"))).containsExactly(
				"PHOTO_UPLOAD_RATE_LIMITED", "PHOTO_PROCESSING_UNAVAILABLE", "PHOTO_DELIVERY_UNAVAILABLE",
				"authenticationFailure", "malformedRequestWithoutUsableKeyAndDigest", "unexpectedServerFailure");
		assertThat(list(receipt.get("lookupAndResponseOrder"))).hasSize(6)
				.anySatisfy(step -> assertThat(step.toString()).contains("IDEMPOTENCY_KEY_REUSED"))
				.anySatisfy(step -> assertThat(step.toString()).contains("WISH_PHOTO_EXPIRED"))
				.anySatisfy(step -> assertThat(step.toString()).contains("PHOTO_DELIVERY_UNAVAILABLE"));
		assertThat(list(receipt.get("validSuccessStates"))).containsExactly(
				"unexpired-unattached-PENDING", "ATTACHED-including-after-COMPLETED-or-ABANDONED");
		assertThat(list(receipt.get("revocationTriggers"))).containsExactly(
				"pendingCancellation", "pendingAttachmentExpiry", "photoReplacement", "explicitPhotoRemoval",
				"wishDeletion", "DELETE_PENDING", "hardObjectOrPhotoRowCleanup");
		assertThat(list(receipt.get("transitionOrder"))).hasSize(4)
				.anySatisfy(step -> assertThat(step.toString()).contains("REVOKED_SUCCESS", "retainUntil"))
				.anySatisfy(step -> assertThat(step.toString()).contains("fail closed", "WISH_PHOTO_EXPIRED"));
		assertThat(map(receipt.get("concurrency")))
				.containsEntry("serializationBoundary", List.of("authenticatedOwner", "Idempotency-Key"))
				.containsEntry("sameKeyMaximumNewTerminalReceiptAndPhoto", 1)
				.containsEntry("expiryAndReuseUseSameLock", true)
				.containsEntry("revocationCommitsBeforeDestructiveCleanup", true)
				.containsEntry("failedNewUploadLeavesNoAttachablePhotoOrActiveSuccessReceipt", true);
		assertThat(declaredErrorCodes("uploadWishPhoto")).containsExactlyInAnyOrder(
				"MALFORMED_REQUEST", "IDEMPOTENCY_KEY_REQUIRED", "AUTH_REQUIRED", "FORBIDDEN",
				"IDEMPOTENCY_KEY_REUSED", "WISH_PHOTO_EXPIRED", "PHOTO_TOO_LARGE", "UNSUPPORTED_PHOTO_TYPE",
				"INVALID_PHOTO", "PHOTO_CONTENT_NOT_ALLOWED", "PHOTO_UPLOAD_RATE_LIMITED",
				"PHOTO_PROCESSING_UNAVAILABLE", "PHOTO_DELIVERY_UNAVAILABLE");
		assertThat(map(resolvedResponse("uploadWishPhoto", "429").get("headers")))
				.containsOnlyKeys("Retry-After");
		assertThat(ref(map(map(upload.get("responses")).get("503"))))
				.isEqualTo("#/components/responses/PhotoUploadUnavailable");
		Map<String, Object> conflict = resolvedResponse("uploadWishPhoto", "409");
		assertThat(list(conflict.get("x-error-codes"))).containsExactly(
				"IDEMPOTENCY_KEY_REUSED", "WISH_PHOTO_EXPIRED");
		assertThat(conflict.get("description").toString()).contains(
				"보존 중인", "digest가 다르며", "Pending 취소·만료", "Wish 삭제", "hard cleanup",
				"retryable false", "photoId", "retainUntil", "노출하지 않습니다");
		assertThat(map(map(map(conflict.get("content")).get("application/json")).get("examples")))
				.containsOnlyKeys("revoked-same-content", "retained-different-content");
		Map<String, Object> unavailable = resolvedResponse("uploadWishPhoto", "503");
		assertThat(list(unavailable.get("x-error-codes"))).containsExactly(
				"PHOTO_PROCESSING_UNAVAILABLE", "PHOTO_DELIVERY_UNAVAILABLE");
		assertThat(unavailable.get("description").toString()).contains(
				"새 업로드", "terminal receipt를 생성하지 않",
				"ACTIVE_SUCCESS 재생", "receipt를 변경·삭제하지 않",
				"retryable true", "provider 정보를 노출하지 않습니다");
		assertThat(map(map(map(unavailable.get("content")).get("application/json")).get("examples")))
				.containsOnlyKeys("processing-unavailable", "replay-delivery-unavailable");

		Map<String, Object> deletePath = map(path("paths", "/v1/wish-photos/{photoId}"));
		assertThat(list(deletePath.get("parameters"))).singleElement().satisfies(raw -> {
			Map<String, Object> parameter = map(resolve(ref(raw)));
			assertThat(parameter).containsEntry("name", "photoId")
					.containsEntry("in", "path").containsEntry("required", true);
			assertThat(ref(parameter.get("schema"))).isEqualTo("#/components/schemas/Uuid");
		});
		assertThat(resolvedResponse("deletePendingWishPhoto", "204")).doesNotContainKey("content");
		assertThat(declaredErrorCodes("deletePendingWishPhoto")).containsExactlyInAnyOrder(
				"MALFORMED_REQUEST", "AUTH_REQUIRED", "FORBIDDEN",
				"WISH_PHOTO_NOT_FOUND", "WISH_PHOTO_ALREADY_ATTACHED");
		assertThat(operations.get("deletePendingWishPhoto").body().get("description").toString())
				.contains("DELETE_PENDING", "REVOKED_SUCCESS", "retainUntil", "204 no-op",
						"다른 학생 소유", "이미 첨부");
		assertThat(operations.get("createWish").body().get("description").toString())
				.contains("첨부는 사진 업로드 receipt의 ACTIVE_SUCCESS를 유지",
						"retainUntil을 소비·연장·교체하지 않습니다");
		assertThat(operations.get("patchWish").body().get("description").toString())
				.contains("교체나 명시적 제거", "REVOKED_SUCCESS",
						"새로 첨부한 사진의 ACTIVE_SUCCESS", "위시를 포기",
						"첨부 사진과 ACTIVE_SUCCESS receipt는 보존");
		assertThat(operations.get("deleteWish").body().get("description").toString())
				.contains("DELETE_PENDING", "REVOKED_SUCCESS", "retainUntil");
	}

	@Test
	void materializesPhotoAttachmentPrivateDeliveryAndFailureSemantics() {
		Map<String, Object> photo = schema("WishPhoto");
		assertThat(photo).containsEntry("type", "object")
				.containsEntry("additionalProperties", false);
		assertThat(list(photo.get("required"))).containsExactly("id", "variants", "expiresAt");
		assertThat(map(photo.get("properties"))).containsOnlyKeys("id", "variants", "expiresAt");
		Map<String, Object> variants = schema("WishPhotoVariants");
		assertThat(list(variants.get("required"))).containsExactly("small", "medium", "large");
		assertThat(map(variants.get("properties"))).containsOnlyKeys("small", "medium", "large")
				.values().allSatisfy(raw -> assertThat(map(raw)).containsEntry("format", "uri"));
		assertThat(list(map(variants.get("x-delivery-constraints")).get("forbiddenWireFields")))
				.containsExactly("bucket", "objectPath", "contentDigest", "safetyResult");

		for (String schemaName : List.of("Wish", "ProgressSharedCard", "CompletionSharedCard")) {
			Map<String, Object> responseSchema = schema(schemaName);
			assertThat(list(responseSchema.get("required"))).as(schemaName).contains("photo");
			assertThat(list(map(map(responseSchema.get("properties")).get("photo")).get("oneOf")))
					.as(schemaName + " nullable photo").hasSize(2)
					.anySatisfy(branch -> assertThat(map(branch))
							.containsEntry("$ref", "#/components/schemas/WishPhoto"))
					.anySatisfy(branch -> assertThat(map(branch)).containsEntry("type", "null"));
		}

		Map<String, Object> create = schema("CreateWishRequest");
		Map<String, Object> createPhoto = map(map(create.get("properties")).get("photoId"));
		assertThat(list(createPhoto.get("type"))).containsExactly("string", "null");
		assertThat(createPhoto).containsEntry("format", "uuid");
		assertThat(list(create.get("required"))).doesNotContain("photoId");
		Map<String, Object> patch = schema("WishMergePatch");
		Map<String, Object> patchPhoto = map(map(patch.get("properties")).get("photoId"));
		assertThat(list(patchPhoto.get("type"))).containsExactly("string", "null");
		assertThat(patchPhoto).containsEntry("format", "uuid");
		assertThat(list(patch.get("anyOf"))).anySatisfy(branch ->
				assertThat(list(map(branch).get("required"))).containsExactly("photoId"));

		Map<String, Integer> photoSuccesses = Map.ofEntries(
				Map.entry("getRepresentativeWish", 200), Map.entry("selectRepresentativeWish", 200),
				Map.entry("listWishes", 200), Map.entry("createWish", 201), Map.entry("getWish", 200),
				Map.entry("patchWish", 200), Map.entry("deleteWish", 200), Map.entry("depositToWish", 200),
				Map.entry("withdrawFromWish", 200), Map.entry("transferWishFunds", 200),
				Map.entry("completeWish", 200), Map.entry("abandonWish", 200),
				Map.entry("listAcademySharedCards", 200), Map.entry("getAcademySharedCard", 200));
		photoSuccesses.forEach((operationId, status) -> {
			assertThat(map(resolvedResponse(operationId, status.toString()).get("headers")))
					.as(operationId + " no-store").containsKey("Cache-Control");
			assertThat(list(resolvedResponse(operationId, "503").get("x-error-codes")))
					.as(operationId + " photo delivery failure").contains("PHOTO_DELIVERY_UNAVAILABLE");
		});

		assertThat(list(schema("ErrorCode").get("enum"))).contains(
				"WISH_PHOTO_NOT_FOUND", "WISH_PHOTO_EXPIRED", "WISH_PHOTO_ALREADY_ATTACHED",
				"PHOTO_TOO_LARGE", "UNSUPPORTED_PHOTO_TYPE", "INVALID_PHOTO",
				"PHOTO_CONTENT_NOT_ALLOWED", "PHOTO_UPLOAD_RATE_LIMITED",
				"PHOTO_PROCESSING_UNAVAILABLE", "PHOTO_DELIVERY_UNAVAILABLE");
		Map<String, Object> error = map(map(schema("ErrorEnvelope").get("properties")).get("error"));
		Map<String, Object> condition = map(list(error.get("allOf")).getFirst());
		assertThat(list(map(map(map(condition.get("if")).get("properties")).get("code")).get("enum")))
				.containsExactly("BALANCE_SYNC_FAILED", "PHOTO_UPLOAD_RATE_LIMITED",
						"PHOTO_PROCESSING_UNAVAILABLE", "PHOTO_DELIVERY_UNAVAILABLE");
		assertThat(map(error.get("properties")).get("details")).satisfies(raw ->
				assertThat(map(raw).get("description").toString()).contains(
						"photoId", "receipt outcome", "retainUntil", "signed URL", "object path",
						"content digest", "provider payload", "빈 details 객체"));
	}

	@Test
	void materializesTheCorrectedWishMutationPhotoReplayContract() {
		Map<String, Object> replay = map(document.get("x-wish-mutation-photo-replay"));
		assertThat(list(replay.get("appliesTo"))).containsExactly(
				"createWish", "depositToWish", "withdrawFromWish", "transferWishFunds",
				"completeWish", "abandonWish", "deleteWish");
		assertThat(map(replay.get("scope")))
				.containsEntry("receiptNamespace", "permanent-per-authenticated-student")
				.containsEntry("ownerSource", "authenticated-principal-only")
				.containsEntry("operationTargetAndRequestFingerprintPreserved", true);

		Map<String, Object> receiptState = map(replay.get("receiptState"));
		assertThat(receiptState).containsEntry("encoding", "private-tagged-union")
				.containsEntry("wireSchemaAddition", false);
		Map<String, Object> variants = map(receiptState.get("variants"));
		assertThat(variants).containsOnlyKeys("NO_PHOTO", "ACTIVE_PHOTO", "PHOTO_REVOKED");
		assertThat(list(map(variants.get("NO_PHOTO")).get("retainedFields")))
				.containsExactly("kind");
		assertThat(map(variants.get("NO_PHOTO")).get("replay").toString())
				.contains("나중에 현재 사진이 첨부되어도", "photo null");
		assertThat(list(map(variants.get("ACTIVE_PHOTO")).get("retainedFields")))
				.containsExactly("kind", "photoId");
		assertThat(map(variants.get("ACTIVE_PHOTO")).get("replay").toString())
				.contains("같은 photoId", "새 5분", "small, medium, large");
		assertThat(list(map(variants.get("PHOTO_REVOKED")).get("retainedFields")))
				.containsExactly("kind");
		assertThat(map(variants.get("PHOTO_REVOKED")).get("replay").toString())
				.contains("409 WISH_PHOTO_EXPIRED", "사진 capability 없이");
		assertThat(list(receiptState.get("invariants"))).hasSize(4)
				.anySatisfy(value -> assertThat(value.toString()).contains("photoId는 ACTIVE_PHOTO에만"))
				.anySatisfy(value -> assertThat(value.toString()).contains("PHOTO_REVOKED", "photoId", "포함하지 않"));
		assertThat(receiptState.get("legacyCompatibility").toString())
				.contains("tag가 없으면 NO_PHOTO", "현재 attachment에서 ACTIVE_PHOTO를 추론하지 않");

		assertThat(map(replay.get("capture")).get("transfer").toString())
				.contains("sourcePhotoReplayState", "destinationPhotoReplayState", "원자적으로");
		assertThat(map(replay.get("capture")).get("delete").toString())
				.contains("redaction·revocation", "항상 NO_PHOTO");
		Map<String, Object> replayRules = map(replay.get("replay"));
		assertThat(replayRules.get("transferAtomicity").toString())
				.contains("두 캡처 상태", "한쪽이라도 PHOTO_REVOKED", "부분 본문을 반환하지 않");
		assertThat(replayRules.get("deliveryFailure").toString())
				.contains("503 PHOTO_DELIVERY_UNAVAILABLE", "부분 성공 본문", "receipt를 바꾸지 않");
		assertThat(replayRules.get("successHeaders").toString())
				.contains("성공한 일치 재생에만", "Idempotency-Replayed true", "Cache-Control no-store");

		Map<String, Object> redaction = map(replay.get("atomicRedaction"));
		assertThat(list(redaction.get("triggers"))).containsExactly(
				"photoReplacement", "explicitPhotoRemoval", "pendingPhotoCancellation",
				"pendingPhotoExpiry", "wishDeletion", "DELETE_PENDING", "hardCleanup", "integrityRepair");
		assertThat(redaction.get("rule").toString()).contains(
				"모든 create, deposit, withdrawal, transfer, completion, abandonment, delete receipt",
				"식별자 없는 PHOTO_REVOKED", "rollback");
		assertThat(list(replay.get("lockOrder"))).containsExactly(
				"owner-mutation-receipt-namespace",
				"wish-photo-upload-receipts-by-owner-key-photo-id",
				"wish-photo-rows-by-ascending-photo-uuid",
				"cleanup-work-rows");
		Map<String, Object> concurrency = map(replay.get("concurrency"));
		assertThat(concurrency.get("invariant").toString())
				.contains("receipt 또는 receipt namespace를 먼저", "photo를 잠근 뒤 receipt를 잠그지 않");
		assertThat(concurrency.get("discoveryAndRevalidation").toString())
				.contains("non-locking read", "owner, photoId, state, attachment", "retainUntil");
		Map<String, Object> outcomes = map(concurrency.get("linearizedOutcomes"));
		assertThat(outcomes.get("replayFirst").toString())
				.contains("ACTIVE_PHOTO", "원래 identity", "모든 일치 참조를 PHOTO_REVOKED");
		assertThat(outcomes.get("revocationFirst").toString())
				.contains("먼저 revoke·redact", "WISH_PHOTO_EXPIRED");
		assertThat(outcomes.get("failureBoundary").toString())
				.contains("PostgreSQL deadlock", "current-photo substitution", "deleted photoId retention",
						"partial transfer capability");
		assertThat(map(replay.get("frontendBoundary")))
				.containsEntry("excludedBackendErrorCode", "BFF_REQUEST_TIMEOUT");
		assertThat(list(schema("ErrorCode").get("enum"))).doesNotContain("BFF_REQUEST_TIMEOUT");

		for (String operationId : List.of(
				"createWish", "depositToWish", "withdrawFromWish", "transferWishFunds",
				"completeWish", "abandonWish")) {
			assertThat(declaredErrorCodes(operationId)).as(operationId + " revoked replay")
					.contains("WISH_PHOTO_EXPIRED", "PHOTO_DELIVERY_UNAVAILABLE");
		}
		assertThat(declaredErrorCodes("deleteWish")).contains("PHOTO_DELIVERY_UNAVAILABLE")
				.doesNotContain("WISH_PHOTO_EXPIRED");
		assertThat(operations.get("deleteWish").body().get("description").toString())
				.contains("모든 Wish mutation receipt", "항상 NO_PHOTO", "DeleteConflict에는 WISH_PHOTO_EXPIRED가 없습니다");
		assertThat(operations.get("transferWishFunds").body().get("description").toString())
				.contains("두 상태를 URL 발급 전에 함께 평가", "어느 한쪽이라도 PHOTO_REVOKED",
						"부분 본문을 반환하지 않고", "503 PHOTO_DELIVERY_UNAVAILABLE");
		assertThat(map(path("components", "headers", "IdempotencyReplayed")).get("description").toString())
				.contains("ACTIVE_PHOTO", "NO_PHOTO", "PHOTO_REVOKED", "409 WISH_PHOTO_EXPIRED",
						"503 PHOTO_DELIVERY_UNAVAILABLE", "header를 보내지 않습니다");
		assertThat(map(path("components", "responses", "WishMutationSuccess")).get("description").toString())
				.contains("NO_PHOTO", "ACTIVE_PHOTO", "PHOTO_REVOKED", "최초 snapshot의 정확한 위시");
		assertThat(map(map(map(map(path("components", "responses", "TransferConflict")).get("content"))
				.get("application/json")).get("examples")))
				.containsOnlyKeys("either-side-photo-revoked");
		assertThat(map(map(map(map(path("components", "responses", "PhotoDeliveryUnavailable")).get("content"))
				.get("application/json")).get("examples")))
				.containsOnlyKeys("replay-delivery-unavailable");
		assertThat(schemaNames()).doesNotContain("ACTIVE_PHOTO", "NO_PHOTO", "PHOTO_REVOKED");
	}

	@Test
	void materializesTheApprovedOwnerScopedCardBalanceAccountDetailContract() {
		String detailPath = "/v1/card-balance-accounts/{cardBalanceAccountId}";
		Map<String, Object> pathItem = map(path("paths", detailPath));
		Map<String, Object> operation = operations.get("getCardBalanceAccount").body();

		assertThat(operation)
				.containsEntry("tags", List.of("Card Balance Accounts"))
				.containsEntry("summary", "소유한 카드 잔액 계정 조회")
				.doesNotContainKeys("parameters", "requestBody");
		assertThat(operation.get("description").toString()).contains(
				"현재 저장된 프로젝션",
				"인증된 학생의 활성 계정",
				"임의 식별자, 종료된 계정, 소유권 불일치, 학원 불일치",
				"같은 리소스 없음 응답",
				"외부 잔액 조회를 수행하지 않으며",
				"영속 상태를 변경하지 않습니다",
				"UNKNOWN 금액은 null로 유지",
				"후속 시도가 실패하면",
				"lastRefreshStatus는 FAILED");

		List<Map<String, Object>> pathParameters = list(pathItem.get("parameters")).stream()
				.map(OpenApiContractTest::map)
				.map(parameter -> map(resolve(ref(parameter))))
				.toList();
		assertThat(pathParameters).singleElement().satisfies(parameter -> {
			assertThat(parameter)
					.containsEntry("name", "cardBalanceAccountId")
					.containsEntry("in", "path")
					.containsEntry("required", true);
			assertThat(ref(parameter.get("schema"))).isEqualTo("#/components/schemas/Uuid");
		});

		Map<String, Object> responses = map(operation.get("responses"));
		Map<String, Object> success = map(responses.get("200"));
		Map<String, Object> json = map(map(success.get("content")).get("application/json"));
		assertThat(success).containsEntry("description", "현재 저장된 카드 잔액 계정 프로젝션입니다.");
		assertThat(ref(json.get("schema"))).isEqualTo("#/components/schemas/CardBalanceAccount");
		Map<String, Object> accountUnion = schema("CardBalanceAccount");
		assertThat(list(accountUnion.get("oneOf"))).extracting(OpenApiContractTest::ref)
				.containsExactly(
						"#/components/schemas/UnknownCardBalanceAccount",
						"#/components/schemas/KnownCardBalanceAccount");
		assertThat(map(accountUnion.get("discriminator")))
				.containsEntry("propertyName", "balanceKnowledge");
		assertThat(map(map(accountUnion.get("discriminator")).get("mapping"))).containsExactly(
				Map.entry("UNKNOWN", "#/components/schemas/UnknownCardBalanceAccount"),
				Map.entry("KNOWN", "#/components/schemas/KnownCardBalanceAccount"));

		Map<String, Object> examples = map(json.get("examples"));
		assertThat(examples).containsOnlyKeys("unknown", "failed-refresh-known", "adjustment-open-known");
		Map<String, Object> unknown = map(examples.get("unknown"));
		assertThat(unknown).containsEntry("x-schema-ref", "#/components/schemas/UnknownCardBalanceAccount");
		assertThat(map(unknown.get("value")))
				.containsEntry("balanceKnowledge", "UNKNOWN")
				.containsEntry("balanceAdjustmentInProgress", false)
				.containsEntry("actualCardBalance", null)
				.containsEntry("ledgerAvailableBalance", null)
				.containsEntry("displayAvailableBalance", null)
				.containsEntry("unresolvedShortage", null)
				.containsEntry("lastRefreshStatus", null)
				.containsEntry("lastRefreshedAt", null);
		assertThat(ref(examples.get("failed-refresh-known")))
				.isEqualTo("#/components/examples/FailedRefreshKnownBalance");
		assertThat(ref(examples.get("adjustment-open-known")))
				.isEqualTo("#/components/examples/KnownBalanceAdjustmentOpen");

		assertThat(ref(responses.get("401"))).isEqualTo("#/components/responses/AuthRequired");
		assertThat(ref(responses.get("403"))).isEqualTo("#/components/responses/Forbidden");
		assertThat(ref(responses.get("404"))).isEqualTo("#/components/responses/CardBalanceAccountNotFound");
		assertThat(resolvedResponse("getCardBalanceAccount", "404").get("description").toString())
				.contains("없거나", "종료되었거나", "소유하지 않거나", "다른 학원", "숨깁니다");
	}

	@Test
	void materializesTheApprovedRepresentativeWishContract() {
		String representativePath =
				"/v1/card-balance-accounts/{cardBalanceAccountId}/representative-wish";
		Map<String, Object> pathItem = map(path("paths", representativePath));

		assertThat(pathItem.keySet()).containsExactlyInAnyOrder("parameters", "get", "put");
		List<Map<String, Object>> pathParameters = list(pathItem.get("parameters")).stream()
				.map(OpenApiContractTest::map)
				.map(parameter -> map(resolve(ref(parameter))))
				.toList();
		assertThat(pathParameters).singleElement().satisfies(parameter -> {
			assertThat(parameter)
					.containsEntry("name", "cardBalanceAccountId")
					.containsEntry("in", "path")
					.containsEntry("required", true);
			assertThat(ref(parameter.get("schema"))).isEqualTo("#/components/schemas/Uuid");
		});

		Map<String, Object> get = operations.get("getRepresentativeWish").body();
		assertThat(get)
				.containsEntry("tags", List.of("Wishes"))
				.containsEntry("summary", "현재 대표 위시 조회")
				.containsEntry("security", List.of(Map.of("SyntheticBearer", List.of())))
				.doesNotContainKeys("parameters", "requestBody");
		assertThat(get.get("description").toString()).contains(
				"인증된 학생",
				"소유한 같은 학원의 활성 카드 잔액 계정",
				"삭제되지 않은 IN_PROGRESS 또는 AMOUNT_REACHED 위시",
				"본문 없이 204",
				"잔액 조정 건이 OPEN",
				"외부 잔액을 조회하거나 영속 상태를 변경하지 않습니다",
				"활성 상태의 삭제되지 않은 위시가 정확히 하나",
				"두 번째 활성 위시를 만들어도 기존 대표는 유지",
				"완료·포기·삭제",
				"계정을 종료하면 선택이 제거");
		Map<String, Object> getResponses = map(get.get("responses"));
		assertThat(getResponses.keySet())
				.containsExactlyInAnyOrder("200", "204", "400", "401", "403", "404", "503");
		Map<String, Object> getSuccess = map(getResponses.get("200"));
		assertThat(getSuccess).containsEntry("description", "현재 대표 위시입니다.");
		Map<String, Object> getJson = map(map(getSuccess.get("content")).get("application/json"));
		assertThat(ref(getJson.get("schema"))).isEqualTo("#/components/schemas/Wish");
		assertThat(ref(map(getJson.get("examples")).get("representative-during-balance-mismatch")))
				.isEqualTo("#/components/examples/RepresentativeWishDuringBalanceMismatch");
		assertThat(map(getResponses.get("204")))
				.containsEntry("description", "유효한 계정에 현재 대표 위시가 없습니다.")
				.doesNotContainKey("content");
		assertThat(ref(getResponses.get("400"))).isEqualTo("#/components/responses/MalformedRequest");
		assertThat(ref(getResponses.get("401"))).isEqualTo("#/components/responses/AuthRequired");
		assertThat(ref(getResponses.get("403"))).isEqualTo("#/components/responses/Forbidden");
		assertThat(ref(getResponses.get("404")))
				.isEqualTo("#/components/responses/CardBalanceAccountNotFound");
		assertThat(ref(getResponses.get("503")))
				.isEqualTo("#/components/responses/PhotoDeliveryUnavailable");

		Map<String, Object> put = operations.get("selectRepresentativeWish").body();
		assertThat(put)
				.containsEntry("tags", List.of("Wishes"))
				.containsEntry("summary", "대표 위시 선택")
				.containsEntry("security", List.of(Map.of("SyntheticBearer", List.of())))
				.doesNotContainKey("parameters");
		assertThat(put.get("description").toString()).contains(
				"원자적으로 교체",
				"현재 대표를 다시 선택하면 변경 없이 200",
				"위시의 updatedAt과 version을 유지",
				"잔액 조정 건이 OPEN",
				"원장 이벤트, 알림 아웃박스 항목, 선택 이력, 위시 변경을 만들지 않습니다",
				"계정 우선 잠금",
				"마지막으로 커밋된 선택이 최종 선택",
				"401 AUTH_REQUIRED",
				"403 FORBIDDEN",
				"400 MALFORMED_REQUEST",
				"404 CARD_BALANCE_ACCOUNT_NOT_FOUND",
				"404 WISH_NOT_FOUND",
				"409 INVALID_STATE_TRANSITION");
		Map<String, Object> requestBody = map(put.get("requestBody"));
		assertThat(requestBody).containsEntry("required", true);
		Map<String, Object> requestContent = map(requestBody.get("content"));
		assertThat(requestContent.keySet()).containsExactly("application/json");
		Map<String, Object> requestJson = map(requestContent.get("application/json"));
		assertThat(ref(requestJson.get("schema")))
				.isEqualTo("#/components/schemas/RepresentativeWishSelectionRequest");
		assertThat(map(map(requestJson.get("examples")).get("atomic-selection")).get("value"))
				.isEqualTo(Map.of("wishId", "341ab749-bbab-4b08-9334-0e4b12347b48"));

		Map<String, Object> selectionRequest = schema("RepresentativeWishSelectionRequest");
		assertThat(selectionRequest)
				.containsEntry("type", "object")
				.containsEntry("additionalProperties", false);
		assertThat(list(selectionRequest.get("required"))).containsExactly("wishId");
		Map<String, Object> selectionProperties = map(selectionRequest.get("properties"));
		assertThat(selectionProperties).containsOnlyKeys("wishId");
		assertThat(ref(selectionProperties.get("wishId"))).isEqualTo("#/components/schemas/Uuid");
		assertThat(map(selectionProperties.get("wishId")).get("description").toString()).contains(
				"삭제되지 않은 활성 위시", "이 카드 잔액 계정");

		Map<String, Object> putResponses = map(put.get("responses"));
		assertThat(putResponses.keySet())
				.containsExactlyInAnyOrder("200", "400", "401", "403", "404", "409", "415", "503");
		Map<String, Object> putSuccess = map(putResponses.get("200"));
		assertThat(putSuccess).containsEntry(
				"description", "선택된 대표 위시는 변경 결과 래퍼나 eventId 없이 직접 반환됩니다.");
		Map<String, Object> putJson = map(map(putSuccess.get("content")).get("application/json"));
		assertThat(ref(putJson.get("schema"))).isEqualTo("#/components/schemas/Wish");
		assertThat(map(putJson.get("examples")).keySet())
				.containsExactlyInAnyOrder("atomic-selection", "same-selection-noop");
		assertThat(ref(map(putJson.get("examples")).get("atomic-selection")))
				.isEqualTo("#/components/examples/RepresentativeWishSelected");
		assertThat(ref(map(putJson.get("examples")).get("same-selection-noop")))
				.isEqualTo("#/components/examples/RepresentativeWishSameSelectionNoop");
		assertThat(ref(putResponses.get("400"))).isEqualTo("#/components/responses/MalformedRequest");
		assertThat(ref(putResponses.get("401"))).isEqualTo("#/components/responses/AuthRequired");
		assertThat(ref(putResponses.get("403"))).isEqualTo("#/components/responses/Forbidden");
		assertThat(ref(putResponses.get("404")))
				.isEqualTo("#/components/responses/WishOrAccountNotFound");
		assertThat(ref(putResponses.get("409")))
				.isEqualTo("#/components/responses/RepresentativeWishSelectionConflict");
		assertThat(ref(putResponses.get("415")))
				.isEqualTo("#/components/responses/JsonUnsupportedMediaType");
		assertThat(ref(putResponses.get("503")))
				.isEqualTo("#/components/responses/PhotoDeliveryUnavailable");
		assertThat(errorCodes("RepresentativeWishSelectionConflict"))
				.containsExactly("INVALID_STATE_TRANSITION");
		Map<String, Object> conflictJson = map(map(map(path(
				"components", "responses", "RepresentativeWishSelectionConflict"))
				.get("content")).get("application/json"));
		assertThat(ref(conflictJson.get("schema")))
				.isEqualTo("#/components/schemas/ErrorEnvelope");
		assertThat(errorCodes("JsonUnsupportedMediaType")).containsExactly("UNSUPPORTED_MEDIA_TYPE");
		assertThat(map(path("components", "responses", "JsonUnsupportedMediaType"))
				.get("description").toString()).contains("application/json").doesNotContain("PATCH");

		assertThat(map(schema("Wish").get("properties"))).doesNotContainKeys("isRepresentative", "eventId");
		String serializedPath = pathItem.toString();
		assertThat(serializedPath).doesNotContain(
				"Idempotency-Key", "If-Match", "expectedVersion", "Idempotency-Replayed",
				"ETag", "WishMutationResult");
	}

	@Test
	void preservesTheApprovedComponentAndExampleInventories() {
		assertThat(schemaNames()).hasSize(75);
		assertThat(map(path("components", "responses"))).hasSize(54);
		assertThat(map(path("components", "examples"))).hasSize(92);
	}

	@Test
	void materializesTheApprovedWishClosureAndAbandonmentHistoryWithoutExposingInternalFields() {
		Map<String, Object> wish = schema("Wish");
		List<Object> required = list(wish.get("required"));
		Map<String, Object> properties = map(wish.get("properties"));
		Map<String, Object> abandonmentAmount = map(properties.get("abandonmentAmount"));
		Map<String, Object> closedAt = map(properties.get("closedAt"));

		assertThat(wish).containsEntry("additionalProperties", false);
		assertThat(required).contains("abandonmentAmount", "closedAt");
		assertThat(required.stream().filter("abandonmentAmount"::equals)).hasSize(1);
		assertThat(required.stream().filter("closedAt"::equals)).hasSize(1);
		List<Map<String, Object>> abandonmentBranches = list(abandonmentAmount.get("oneOf"))
				.stream().map(OpenApiContractTest::map).toList();
		assertThat(abandonmentBranches).hasSize(2)
				.anySatisfy(branch -> assertThat(branch)
						.containsEntry("$ref", "#/components/schemas/KrwNonNegative"))
				.anySatisfy(branch -> assertThat(branch).containsEntry("type", "null"));
		assertThat(abandonmentAmount.get("description").toString()).contains(
				"포기하기 직전", "ABANDONED에서는 0을 포함해 targetAmount 이하",
				"IN_PROGRESS, AMOUNT_REACHED, COMPLETED에서는 명시적인 null",
				"현재 할당액", "논리 삭제", "멱등 재생");
		assertThat(closedAt)
				.containsEntry("type", List.of("string", "null"))
				.containsEntry("format", "date-time")
				.containsEntry("pattern", "Z$");
		assertThat(closedAt.get("description").toString()).contains(
				"COMPLETED에서는 completedAt과 정확히 같고",
				"ABANDONED에서는 내부에 영속된 abandonedAt과 같으며",
				"IN_PROGRESS와 AMOUNT_REACHED에서는 null",
				"targetDate, updatedAt, 논리 삭제 시각과 무관");
		assertThat(properties).doesNotContainKeys("abandonedAt", "abandonment_amount", "deletedAt");
		for (String requestSchema : List.of(
				"CreateWishRequest", "WishMergePatch", "WishAmountCommand",
				"WishTransferRequest", "WishVersionCommand")) {
			assertThat(map(schema(requestSchema).get("properties"))).as(requestSchema)
					.doesNotContainKeys("abandonmentAmount", "abandonment_amount");
		}
		for (String nonOwnerSchema : List.of("ProgressSharedCard", "CompletionSharedCard")) {
			assertThat(map(schema(nonOwnerSchema).get("properties"))).as(nonOwnerSchema)
					.doesNotContainKeys("abandonmentAmount", "abandonment_amount");
		}
	}

	@Test
	void bindsIdempotencyAndConcurrencyOnlyAtTheirApprovedLocations() {
		Set<String> expectedIdempotent = Set.of(
				"uploadWishPhoto", "createWish", "depositToWish", "withdrawFromWish", "transferWishFunds",
				"completeWish", "abandonWish", "deleteWish");
		Set<String> actualIdempotent = new LinkedHashSet<>();
		operations.forEach((operationId, operation) -> {
			if (resolvedParameters(operation.body()).stream()
					.anyMatch(parameter -> "Idempotency-Key".equals(parameter.get("name")))) {
				actualIdempotent.add(operationId);
			}
		});
		assertThat(actualIdempotent).containsExactlyInAnyOrderElementsOf(expectedIdempotent);

		assertThat(operations.get("refreshCardBalance").body()).doesNotContainKeys("requestBody");
		assertThat(operations.get("patchWish").body()).doesNotContainKeys("parameters");
		Map<String, Object> patchContent = map(map(operations.get("patchWish").body().get("requestBody")).get("content"));
		assertThat(patchContent.keySet()).containsExactly("application/merge-patch+json");
		assertThat(schemaRef(patchContent.get("application/merge-patch+json"))).isEqualTo("WishMergePatch");

		Map<String, Object> patchSchema = schema("WishMergePatch");
		assertThat(list(patchSchema.get("required"))).contains("expectedVersion");
		assertThat(list(patchSchema.get("anyOf"))).hasSize(5);
		assertThat(resolvedParameters(operations.get("deleteWish").body()))
				.extracting(parameter -> parameter.get("name"))
				.containsExactlyInAnyOrder("If-Match", "Idempotency-Key");
		assertThat(operations.get("deleteWish").body()).doesNotContainKey("requestBody");

		assertThat(schema("WishAmountCommand")).extracting("required")
				.isEqualTo(List.of("amount", "expectedVersion"));
		assertThat(schema("WishTransferRequest")).extracting("required").isEqualTo(List.of(
				"sourceWishId", "destinationWishId", "amount", "sourceExpectedVersion", "destinationExpectedVersion"));
		assertThat(schema("WishVersionCommand")).extracting("required")
				.isEqualTo(List.of("expectedVersion"));
	}

	@Test
	void separatesPreNormalizationPurposeInputFromNormalizedPurposeOutput() {
		Map<String, Object> input = schema("PurposeInput");
		assertThat(input).containsEntry("type", "string")
				.doesNotContainKeys("minLength", "maxLength", "pattern");
		assertThat(input.get("description").toString()).containsSubsequence(
				"요청 값을 문자열로 디코딩",
				"Cc, Cf, Zl, Zp",
				"앞뒤의 모든 유니코드 Space_Separator",
				"NFC",
				"1~200개",
				"INVALID_PURPOSE");

		assertThat(ref(map(schema("CreateWishRequest").get("properties")).get("purpose")))
				.isEqualTo("#/components/schemas/PurposeInput");
		assertThat(ref(map(schema("WishMergePatch").get("properties")).get("purpose")))
				.isEqualTo("#/components/schemas/PurposeInput");

		Map<String, Object> output = schema("Purpose");
		assertThat(output).containsEntry("type", "string")
				.containsEntry("minLength", 1)
				.containsEntry("maxLength", 200);
		assertThat(output.get("description").toString()).contains(
				"NFC로 정규화", "앞뒤 경계 공백이 없으며", "Cc", "Cf", "Zl", "Zp", "1~200개");
		assertThat(ref(map(schema("Wish").get("properties")).get("purpose")))
				.isEqualTo("#/components/schemas/Purpose");
		assertThat(ref(map(schema("ProgressSharedCard").get("properties")).get("purpose")))
				.isEqualTo("#/components/schemas/Purpose");
		assertThat(ref(map(schema("CompletionSharedCard").get("properties")).get("purpose")))
				.isEqualTo("#/components/schemas/Purpose");
	}

	@Test
	void mapsEveryDecodedNegativeVersionTo422InvalidVersion() {
		assertThat(list(schema("ErrorCode").get("enum"))).contains("INVALID_VERSION");
		assertThat(schema("WishVersion").get("description").toString()).contains(
				"없거나 정수가 아니면", "400 MALFORMED_REQUEST",
				"디코딩된 버전이 음수이면", "422 INVALID_VERSION",
				"최신 버전과 다르면", "409 VERSION_CONFLICT");

		assert422("patchWish", List.of("INVALID_AMOUNT", "INVALID_PURPOSE", "INVALID_VERSION"),
				"invalid-expected-version");
		assert422("depositToWish", List.of("INVALID_AMOUNT", "INVALID_VERSION"),
				"invalid-expected-version");
		assert422("withdrawFromWish", List.of("INVALID_AMOUNT", "INVALID_VERSION"),
				"invalid-expected-version");
		assert422("transferWishFunds", List.of("INVALID_AMOUNT", "INVALID_VERSION"),
				"invalid-source-expected-version", "invalid-destination-expected-version");
		assert422("completeWish", List.of("INVALID_VERSION"), "invalid-expected-version");
		assert422("abandonWish", List.of("INVALID_VERSION"), "invalid-expected-version");
		assert422("deleteWish", List.of("INVALID_VERSION"), "invalid-if-match-version");

		for (String operationId : List.of("patchWish", "depositToWish", "withdrawFromWish", "transferWishFunds",
				"completeWish", "abandonWish", "deleteWish")) {
			Map<String, Object> responses = map(operations.get(operationId).body().get("responses"));
			assertThat(responses.keySet()).as(operationId + " malformed and stale versions")
					.contains("400", "409");
		}
		assertThat(errorCodes("PatchConflict")).contains("VERSION_CONFLICT");
		assertThat(errorCodes("DepositConflict")).contains("VERSION_CONFLICT");
		assertThat(errorCodes("WithdrawalConflict")).contains("VERSION_CONFLICT");
		assertThat(errorCodes("TransferConflict")).contains("VERSION_CONFLICT");
		assertThat(errorCodes("StateMutationConflict")).contains("VERSION_CONFLICT");
		assertThat(errorCodes("DeleteConflict")).contains("VERSION_CONFLICT");
	}

	@Test
	void fixesTheApprovedEnumsKrwBoundsAndErrorEnvelope() {
		List<Object> lookupMethods = list(schema("BalanceLookupMethod").get("enum"));
		assertThat(lookupMethods)
				.containsExactly("USER_REQUESTED", "PRE_DEPOSIT", "AUTO_DAILY")
				.doesNotContain("APP_LAUNCH", "MANUAL_REFRESH");
		assertThat(List.of(BalanceLookupMethod.values()).stream().map(Enum::name).toList())
				.containsExactlyElementsOf(lookupMethods.stream().map(Object::toString).toList());

		List<Object> ledgerEventTypes = list(schema("LedgerEventType").get("enum"));
		assertThat(ledgerEventTypes)
				.containsExactly("CARD_BALANCE_CHANGE", "WISH_DEPOSIT", "WISH_WITHDRAWAL", "WISH_TRANSFER",
						"WISH_COMPLETION_RETURN", "WISH_ABANDONMENT_RETURN", "WISH_DELETION_RETURN")
				.doesNotContain("CORRECTION");
		assertThat(List.of(LedgerEventType.values()).stream().map(Enum::name).toList())
				.containsExactlyElementsOf(ledgerEventTypes.stream().map(Object::toString).toList());
		assertThat(schema("KrwSigned")).containsEntry("minimum", -9007199254740991L)
				.containsEntry("maximum", 9007199254740991L);
		assertThat(schema("KrwPositive")).containsEntry("minimum", 1)
				.containsEntry("maximum", 9007199254740991L);

		Map<String, Object> error = map(map(schema("ErrorEnvelope").get("properties")).get("error"));
		assertThat(list(error.get("required"))).containsExactly(
				"code", "message", "retryable", "traceId", "fieldErrors", "details");
		assertThat(error).containsEntry("additionalProperties", false);
		assertThat(errorCodes("BalanceSyncFailed")).containsExactly("BALANCE_SYNC_FAILED");
		assertThat(errorCodes("DepositConflict")).containsExactly(
				"VERSION_CONFLICT", "INVALID_STATE_TRANSITION", "BALANCE_MISMATCH_LOCKED",
				"INSUFFICIENT_AVAILABLE_BALANCE", "TARGET_AMOUNT_EXCEEDED", "IDEMPOTENCY_KEY_REUSED",
				"WISH_PHOTO_EXPIRED");
	}

	@Test
	void modelsClosedSharedCardVariantsWithTheReadTimeBooleanOnlyOnProgress() {
		Map<String, Object> progress = schema("ProgressSharedCard");
		Map<String, Object> completion = schema("CompletionSharedCard");
		Map<String, Object> progressProperties = map(progress.get("properties"));
		Map<String, Object> completionProperties = map(completion.get("properties"));

		assertThat(progress).containsEntry("additionalProperties", false);
		assertThat(completion).containsEntry("additionalProperties", false);
		assertThat(list(progress.get("required"))).contains("targetAmount", "balanceAdjustmentInProgress");
		assertThat(progressProperties.get("balanceAdjustmentInProgress"))
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.containsEntry("type", "boolean");
		assertThat(completionProperties).doesNotContainKeys("balanceAdjustmentInProgress", "adjustmentStatus", "amount");
		assertThat(progressProperties).doesNotContainKeys("adjustmentStatus", "amount");
		assertThat(schemaNames()).doesNotContain("SharedCardAdjustmentStatus");

		Set<String> forbidden = Set.of("wishId", "cardBalanceAccountId", "studentId", "physicalCardId",
				"physicalCardNumber", "actualCardBalance", "ledgerAvailableBalance", "displayAvailableBalance",
				"amount", "fundMovements", "cardBalanceChanges");
		assertThat(progressProperties.keySet()).doesNotContainAnyElementsOf(forbidden);
		assertThat(completionProperties.keySet()).doesNotContainAnyElementsOf(forbidden);
	}

	@Test
	void projectsTheAccountScopedAdjustmentStateWithoutLeakingCaseDetails() {
		Map<String, Object> unknown = schema("UnknownCardBalanceAccount");
		Map<String, Object> known = schema("KnownCardBalanceAccount");
		Map<String, Object> wish = schema("Wish");
		Map<String, Object> unknownFlag = map(map(unknown.get("properties")).get("balanceAdjustmentInProgress"));
		Map<String, Object> knownFlag = map(map(known.get("properties")).get("balanceAdjustmentInProgress"));
		Map<String, Object> wishFlag = map(map(wish.get("properties")).get("balanceAdjustmentInProgress"));

		assertThat(list(unknown.get("required"))).contains("balanceAdjustmentInProgress");
		assertThat(unknownFlag).containsEntry("type", "boolean").containsEntry("const", false);
		assertThat(unknownFlag.get("description").toString()).contains(
				"항상 false", "OPEN 잔액 조정 건", "성공적인 잔액 관측");

		assertThat(list(known.get("required"))).contains("balanceAdjustmentInProgress");
		assertThat(knownFlag).containsEntry("type", "boolean");
		assertThat(knownFlag.get("description").toString()).contains(
				"응답 조회 시점", "OPEN", "RESOLVED 이력만 있는 경우",
				"하나의 일관된 계정 프로젝션", "이후 조회가 실패하면");

		assertThat(list(wish.get("required"))).contains("balanceAdjustmentInProgress");
		assertThat(wishFlag).containsEntry("type", "boolean");
		assertThat(wishFlag.get("description").toString()).contains(
				"응답 스냅샷", "파생 값", "변경이 커밋된 후의 값",
				"version이나 updatedAt은 증가하지 않습니다", "boolean 값만 노출", "부족액");
		assertThat(map(wish.get("properties")).keySet()).doesNotContain(
				"unresolvedShortage", "adjustmentCaseId", "observationId", "eventLinks", "accountHistory");

		Map<String, Object> examples = map(path("components", "examples"));
		Map<String, Object> unknownPage = map(map(examples.get("UnknownBalancePage")).get("value"));
		Map<String, Object> unknownItem = map(list(unknownPage.get("items")).getFirst());
		assertThat(unknownItem).containsEntry("balanceAdjustmentInProgress", false);
		assertThat(map(map(examples.get("FailedRefreshKnownBalance")).get("value")))
				.containsEntry("balanceAdjustmentInProgress", false);

		Map<String, Object> createdWish = map(map(map(examples.get("WishCreatedPrivateZero")).get("value"))
				.get("wish"));
		Map<String, Object> replay = map(examples.get("IdempotentReplay"));
		Map<String, Object> replayWish = map(map(replay.get("value")).get("wish"));
		assertThat(createdWish).containsEntry("balanceAdjustmentInProgress", false);
		assertThat(replay.get("description").toString()).contains(
				"최초 결과에 캡처된", "현재 조회 시점 값이 아니라");
		assertThat(replayWish).containsEntry("balanceAdjustmentInProgress", true);

		Map<String, Object> knownOpen = map(map(examples.get("KnownBalanceAdjustmentOpen")).get("value"));
		Map<String, Object> wishOpen = map(map(examples.get("WishBalanceAdjustmentOpen")).get("value"));
		assertThat(map(examples.get("KnownBalanceAdjustmentOpen")))
				.containsEntry("summary", "잔액 조정 건이 열린 KNOWN 잔액")
				.containsEntry("x-schema-ref", "#/components/schemas/KnownCardBalanceAccount");
		assertThat(knownOpen).containsEntry("balanceAdjustmentInProgress", true)
				.containsEntry("ledgerAvailableBalance", -20000)
				.containsEntry("unresolvedShortage", 20000);
		assertThat(map(examples.get("WishBalanceAdjustmentOpen")))
				.containsEntry("summary", "잔액 조정 건이 열린 위시")
				.containsEntry("x-schema-ref", "#/components/schemas/Wish");
		assertThat(wishOpen).containsEntry("balanceAdjustmentInProgress", true);
		Set<String> wishExampleKeys = new TreeSet<>();
		collectKeys(wishOpen, wishExampleKeys);
		assertThat(wishExampleKeys).doesNotContain(
				"unresolvedShortage", "adjustmentCaseId", "observationId", "eventLinks", "accountHistory");
	}

	@Test
	void appliesTheMismatchGuardOnlyToTheApprovedOperationsAndPreservesReplayOrdering() {
		Map<String, Object> create = operations.get("createWish").body();
		assertThat(create.get("description").toString()).contains(
				"일치하는 Idempotency-Key의 이전 성공 결과",
				"현재 불일치 방어 조건보다 먼저 재생",
				"OPEN 잔액 조정 건", "새 위시를 저장하기 전에");
		assertThat(ref(map(map(create.get("responses")).get("409"))))
				.isEqualTo("#/components/responses/CreateConflict");
		assertThat(errorCodes("CreateConflict"))
				.containsExactly("BALANCE_MISMATCH_LOCKED", "IDEMPOTENCY_KEY_REUSED",
						"WISH_PHOTO_EXPIRED", "WISH_PHOTO_ALREADY_ATTACHED");
		Map<String, Object> createConflict = map(path("components", "responses", "CreateConflict"));
		Map<String, Object> createConflictJson = map(map(createConflict.get("content"))
				.get("application/json"));
		assertThat(map(createConflictJson.get("examples")))
				.containsKey("balance-mismatch-locked");

		assertThat(operations.get("patchWish").body().get("description").toString()).contains(
				"COMPLETED 또는 ABANDONED 위시는 공개 범위만 변경",
				"포기된 위시의 공개 범위를 변경하면 소유자에게 보이는 위시 메타데이터",
				"공유 카드는 절대 생성하지 않습니다", "모든 요청 필드", "purpose",
				"targetAmount", "targetDate", "공개 범위를 확대·축소", "PRIVATE");
		assertThat(errorCodes("PatchConflict")).containsExactly(
				"VERSION_CONFLICT", "INVALID_STATE_TRANSITION", "BALANCE_MISMATCH_LOCKED",
				"WISH_PHOTO_EXPIRED", "WISH_PHOTO_ALREADY_ATTACHED");
		assertThat(errorCodes("DeleteConflict")).containsExactly(
				"VERSION_CONFLICT", "IDEMPOTENCY_KEY_REUSED");
		assertThat(errorCodes("StateMutationConflict")).containsExactly(
				"VERSION_CONFLICT", "INVALID_STATE_TRANSITION", "IDEMPOTENCY_KEY_REUSED",
				"WISH_PHOTO_EXPIRED");
		assertThat(errorCodes("WithdrawalConflict")).doesNotContain("BALANCE_MISMATCH_LOCKED");
		assertThat(errorCodes("DepositConflict")).contains("BALANCE_MISMATCH_LOCKED");
		assertThat(errorCodes("TransferConflict")).contains("BALANCE_MISMATCH_LOCKED");

		for (String operationId : List.of(
				"refreshCardBalance", "listMyCardBalanceAccounts", "getCardBalanceAccount", "listWishes", "getWish",
				"listCardBalanceChanges", "listAccountFundMovements", "listWishFundMovements",
				"withdrawFromWish", "completeWish", "abandonWish", "deleteWish",
				"getRepresentativeWish", "selectRepresentativeWish")) {
			assertThat(declaredErrorCodes(operationId)).as(operationId + " mismatch allowance")
					.doesNotContain("BALANCE_MISMATCH_LOCKED");
		}
		for (String operationId : List.of("createWish", "depositToWish", "transferWishFunds", "patchWish")) {
			assertThat(declaredErrorCodes(operationId)).as(operationId + " mismatch guard")
					.contains("BALANCE_MISMATCH_LOCKED");
		}
	}

	@Test
	void resolvesEveryInternalReferenceAndKeepsHistoryAndPaginationDiscriminated() {
		List<String> unresolved = new ArrayList<>();
		walk(document, node -> {
			if (node instanceof Map<?, ?> candidate && candidate.get("$ref") instanceof String ref
					&& ref.startsWith("#/") && resolve(ref) == null) {
				unresolved.add(ref);
			}
		});
		assertThat(unresolved).isEmpty();
		assertThat(schema("CardBalanceChange"))
				.containsEntry("type", "object")
				.containsEntry("additionalProperties", false)
				.doesNotContainKey("oneOf");
		assertThat(schemaNames()).doesNotContain("SuccessfulCardBalanceChange", "FailedCardBalanceObservation");
		assertThat(list(schema("AccountFundMovement").get("oneOf"))).hasSize(7);
		assertThat(list(schema("WishFundMovement").get("oneOf"))).hasSize(6);
		assertThat(list(schema("SharedCard").get("oneOf"))).hasSize(2);
		assertThat(map(path("components", "parameters", "Limit", "schema")))
				.containsEntry("default", 20).containsEntry("maximum", 100);
	}

	@Test
	void bindsEveryHistoryItemToOneImmutableEventAndItsNullableProvenance() {
		Map<String, Object> cardChange = schema("CardBalanceChange");
		assertImmutableHistoryProvenance("CardBalanceChange");
		assertThat(list(cardChange.get("required"))).containsExactly(
				"eventId", "eventType", "observationId", "lookupMethod", "occurredAt",
				"actualCardBalanceDelta", "actualCardBalanceAfter", "correctionOfEventId", "balanceAdjustment");
		assertThat(map(map(cardChange.get("properties")).get("eventType")))
				.containsEntry("const", "CARD_BALANCE_CHANGE");
		assertThat(list(map(map(cardChange.get("properties")).get("actualCardBalanceDelta")).get("allOf")))
				.anySatisfy(branch -> assertThat(map(map(branch).get("not"))).containsEntry("const", 0));

		List<String> accountVariants = List.of(
				"AccountCardBalanceChange", "AccountWishDeposit", "AccountWishWithdrawal", "AccountWishTransfer",
				"AccountWishCompletionReturn", "AccountWishAbandonmentReturn", "AccountWishDeletionReturn");
		Map<String, Object> accountDiscriminator = map(schema("AccountFundMovement").get("discriminator"));
		assertThat(accountDiscriminator).containsEntry("propertyName", "eventType");
		assertThat(map(accountDiscriminator.get("mapping"))).containsExactly(
				Map.entry("CARD_BALANCE_CHANGE", "#/components/schemas/AccountCardBalanceChange"),
				Map.entry("WISH_DEPOSIT", "#/components/schemas/AccountWishDeposit"),
				Map.entry("WISH_WITHDRAWAL", "#/components/schemas/AccountWishWithdrawal"),
				Map.entry("WISH_TRANSFER", "#/components/schemas/AccountWishTransfer"),
				Map.entry("WISH_COMPLETION_RETURN", "#/components/schemas/AccountWishCompletionReturn"),
				Map.entry("WISH_ABANDONMENT_RETURN", "#/components/schemas/AccountWishAbandonmentReturn"),
				Map.entry("WISH_DELETION_RETURN", "#/components/schemas/AccountWishDeletionReturn"));
		accountVariants.forEach(OpenApiContractTest::assertImmutableHistoryProvenance);

		List<String> wishVariants = List.of(
				"WishDepositMovement", "WishWithdrawalMovement", "WishTransferMovement",
				"WishCompletionReturnMovement", "WishAbandonmentReturnMovement", "WishDeletionReturnMovement");
		Map<String, Object> wishDiscriminator = map(schema("WishFundMovement").get("discriminator"));
		assertThat(wishDiscriminator).containsEntry("propertyName", "eventType");
		assertThat(map(wishDiscriminator.get("mapping"))).hasSize(6);
		wishVariants.forEach(schemaName -> {
			assertImmutableHistoryProvenance(schemaName);
			assertThat(list(schema(schemaName).get("required"))).contains("wishPurposeSnapshot", "wishAmountDelta", "wishAmountAfter");
		});

		Map<String, Object> adjustment = schema("BalanceAdjustmentEventReference");
		assertThat(adjustment).containsEntry("additionalProperties", false);
		assertThat(list(adjustment.get("required")))
				.containsExactly("adjustmentCaseId", "eventRole", "sequenceNumber");
		assertThat(list(map(map(adjustment.get("properties")).get("eventRole")).get("enum")))
				.containsExactly("OPENING_DECREASE", "INTERMEDIATE", "RESOLUTION");
		assertThat(map(map(adjustment.get("properties")).get("sequenceNumber")))
				.containsEntry("minimum", 0);
	}

	@Test
	void exposesEventTimeWishContextAndKeepsOwnedTombstoneHistoryReadableWithoutLinks() {
		Map<String, Object> reference = schema("WishHistoryReference");
		Map<String, Object> subject = schema("WishHistorySubject");
		assertThat(reference).containsEntry("additionalProperties", false);
		assertThat(list(reference.get("required")))
				.containsExactly("wishId", "wishPurposeSnapshot", "deletedWish", "detailAvailable");
		assertThat(subject).containsEntry("additionalProperties", false);
		assertThat(list(subject.get("required")))
				.containsExactly("wishId", "displayPurpose", "deletedWish", "detailAvailable");
		assertThat(map(reference.get("properties")).keySet())
				.doesNotContain("href", "url", "detailPath");
		assertThat(map(subject.get("properties")).keySet())
				.doesNotContain("href", "url", "detailPath");

		Map<String, Object> wishPage = schema("WishFundMovementPage");
		assertThat(list(wishPage.get("required"))).containsExactly("wish", "items", "nextCursor");
		assertThat(ref(map(wishPage.get("properties")).get("wish")))
				.isEqualTo("#/components/schemas/WishHistorySubject");
		assertThat(ref(map(schema("WishTransferMovement").get("properties")).get("counterpartyWish")))
				.isEqualTo("#/components/schemas/WishHistoryReference");
		assertThat(map(schema("AccountWishTransfer").get("properties")))
				.containsKeys("sourceWish", "destinationWish")
				.doesNotContainKeys("sourceWishId", "destinationWishId");

		Map<String, Object> history404 = resolvedResponse("listWishFundMovements", "404");
		assertThat(history404.get("description").toString()).contains(
				"소유자가 논리 삭제한 위시", "의도적으로 숨기지 않으며 200을 반환");
		assertThat(ref(map(map(operations.get("listWishFundMovements").body().get("responses")).get("404"))))
				.isEqualTo("#/components/responses/WishHistoryOrAccountNotFound");
		assertThat(ref(map(map(operations.get("getWish").body().get("responses")).get("404"))))
				.isEqualTo("#/components/responses/WishOrAccountNotFound");
	}

	@Test
	void documentsStableHistoryCursorScopeAndPerRequestOwnershipChecks() {
		for (String operationId : List.of("listCardBalanceChanges", "listAccountFundMovements", "listWishFundMovements")) {
			Map<String, Object> operation = operations.get(operationId).body();
			assertThat(operation.get("description").toString()).contains(
					"occurredAt DESC, eventId DESC",
					"정렬 버전",
					"부분 페이지 없이",
					"엄격히 뒤에 이어지며",
					"같은 타임스탬프",
					"유효한 limit 값을 함께 사용할 수 있습니다",
					"권한과 소유권은 요청할 때마다 다시 평가",
					"캐시 가능성을 보장하지 않습니다");
			assertThat(resolvedParameters(operation))
					.extracting(parameter -> parameter.get("name"))
					.containsExactly("cursor", "limit");
		}
		assertThat(operations.get("listWishFundMovements").body().get("description").toString())
				.contains("계정, 위시");
	}

	@Test
	void documentsTheProvisionalSharedCardOrderWithoutClientRankingControls() {
		Map<String, Object> operation = operations.get("listAcademySharedCards").body();
		assertThat(operation.get("description").toString()).contains(
				"임시 정렬",
				"contentUpdatedAt DESC, sharedCardId DESC",
				"현재는 정렬 매개변수를 지원하지 않습니다",
				"콘텐츠 또는 게시 상태가 바뀔 때만 카드 순서가 달라집니다",
				"친구 우선순위와 임베딩 기반 추천 정렬은 향후 계약에서 정할 사항",
				"이 버전에서는 사용하지 않습니다");
		assertThat(resolvedParameters(operation))
				.extracting(parameter -> parameter.get("name"))
				.containsExactly("cursor", "limit")
				.doesNotContain("sort", "ranking", "friendPriority", "embeddingRecommendation");
	}

	@Test
	void localizesEveryDocumentationScalarWithoutTheRejectedSemanticInversions() {
		String expectedOverview = "소유자용 카드 잔액 계정(Card Balance Account) 및 위시(Wish) 작업과 "
				+ "학원용 공유 카드(Shared Card) 조회, 친구 관리(Friend Management), 잔액 조정 건(Balance Adjustment Case) "
				+ "상태 표시를 제공합니다. 리소스 소유권과 현재 공개 범위는 리소스별 404 응답으로 숨깁니다. "
				+ "모든 타임스탬프는 RFC 3339 UTC Z 형식이며, 모든 KRW 금액은 범위가 제한된 정수 원 단위입니다. "
				+ "모든 오류 본문은 공통 ErrorEnvelope를 사용합니다.";
		Map<String, String> expectedTagDescriptions = Map.of(
				"Card Balance Accounts", "카드 잔액 계정(Card Balance Account)의 잔액, 새로고침, 원장 이력과 "
						+ "잔액 조정 건(Balance Adjustment Case) 상태를 다룹니다.",
				"Wishes", "위시(Wish)의 생성, 조회, 변경, 삭제, 대표 선택과 자금 이동을 다룹니다.",
				"Shared Cards", "학원에 공개되는 공유 카드(Shared Card) 조회를 다룹니다.",
				"Friend Management", "같은 학원 학생 간 친구 관리(Friend Management)의 검색, 친구 요청, "
						+ "수락·거절·취소와 차단을 다룹니다.");

		assertThat(map(document.get("info"))).containsEntry("description", expectedOverview);
		assertThat(list(document.get("tags"))).extracting(OpenApiContractTest::map)
				.extracting(tag -> Map.entry(tag.get("name").toString(), tag.get("description").toString()))
				.containsExactly(
						Map.entry("Card Balance Accounts", expectedTagDescriptions.get("Card Balance Accounts")),
						Map.entry("Wishes", expectedTagDescriptions.get("Wishes")),
						Map.entry("Shared Cards", expectedTagDescriptions.get("Shared Cards")),
						Map.entry("Friend Management", expectedTagDescriptions.get("Friend Management")));
		assertThat(operations.get("listMyCardBalanceAccounts").body()).containsEntry(
				"description", "UNKNOWN 잔액은 임의로 0을 만들지 않고 null로 유지합니다. 각 계정은 계정 범위의 "
						+ "잔액 조정 건(Balance Adjustment Case)이 현재 OPEN인지도 함께 표시합니다.");

		List<String> summaries = new ArrayList<>();
		List<String> descriptions = new ArrayList<>();
		walk(document, value -> {
			if (value instanceof Map<?, ?> candidate) {
				if (candidate.get("summary") instanceof String summary) {
					summaries.add(summary);
				}
				if (candidate.get("description") instanceof String description) {
					descriptions.add(description);
				}
			}
		});

		assertThat(summaries).hasSize(128).allSatisfy(summary ->
				assertThat(summary).isNotBlank().containsPattern("[가-힣]"));
		assertThat(descriptions).hasSize(438).allSatisfy(description ->
				assertThat(description).isNotBlank().containsPattern("[가-힣]"));

		String localizedDocumentation = String.join("\n", summaries) + "\n" + String.join("\n", descriptions);
		assertThat(localizedDocumentation).doesNotContain(
				"합성 주 토큰", "교직원 교장", "고정 장치", "디코딩된 부정",
				"서명된", "임시 주문", "추천 주문", "부족하지 않은 부울 금액",
				"대표희망", "계좌 희망", "기금 운동", "균형 불일치",
				"카드 잔액 계정(카드 잔액 계정)", "위시(위시)", "공유 카드(공유 카드)",
				"친구 관리(친구 관리)", "잔액 조정 건(잔액 조정 건)");

		assertThat(path("paths", "/v1/card-balance-accounts/{cardBalanceAccountId}/representative-wish",
				"get", "responses", "204", "description"))
				.isEqualTo("유효한 계정에 현재 대표 위시가 없습니다.");
		assertThat(path("components", "securitySchemes", "SyntheticBearer", "description"))
				.isEqualTo("불투명한 합성 인증 주체 토큰입니다. 알려진 토큰은 학생 또는 인증된 비학생 교직원 인증 주체를 "
						+ "식별합니다. 토큰 발급·갱신, 페르소나 선택, 테스트 픽스처 제어는 이 계약의 범위 밖입니다.");

		String wishAdjustment = map(schema("Wish").get("properties"))
				.get("balanceAdjustmentInProgress").toString();
		assertThat(wishAdjustment).contains(
				"boolean 값만 노출", "부족액", "adjustmentCaseId", "observationId",
				"계정 이력은 절대 노출하지 않습니다");
		assertThat(map(map(schema("AccountWishTransfer").get("properties")).get("eventId")))
				.containsEntry("description", "부호가 반대인 두 위시 이체 프로젝션이 공유하는 하나의 불변 원장 이벤트 UUID입니다.");
		assertThat(map(map(schema("WishTransferMovement").get("properties")).get("eventId")))
				.containsEntry("description", "부호가 반대인 두 위시 이체 프로젝션이 공유하는 하나의 불변 원장 이벤트 UUID입니다.");
		for (String schemaName : List.of("ProgressSharedCard", "CompletionSharedCard")) {
			assertThat(map(map(schema(schemaName).get("properties")).get("sharedCardId")))
					.containsEntry("description", "개인정보를 노출하지 않는 이 공유 카드 프로젝션의 안정적인 UUID입니다. "
							+ "기반 위시 또는 계정 식별자는 노출하지 않습니다.");
		}
	}

	@Test
	void directlyDescribesEveryResponseVisibleField() {
		Set<String> visitedSchemaRefs = new LinkedHashSet<>();
		Set<String> responseFields = new TreeSet<>();
		Set<String> missingDescriptions = new TreeSet<>();

		operations.values().forEach(operation -> map(operation.body().get("responses")).values().forEach(rawResponse -> {
			Map<String, Object> response = map(rawResponse);
			if (response.containsKey("$ref")) {
				response = map(resolve(ref(response)));
			}
			Map<String, Object> json = map(map(response.get("content")).get("application/json"));
			if (json.containsKey("schema")) {
				auditResponseSchema(json.get("schema"), "response", visitedSchemaRefs,
						responseFields, missingDescriptions);
			}
		}));

		assertThat(responseFields).as("response-visible field inventory").isNotEmpty();
		assertThat(missingDescriptions)
				.as("response-visible fields without direct descriptions")
				.isEmpty();
	}

	private static void auditResponseSchema(
			Object rawSchema,
			String qualifiedPath,
			Set<String> visitedSchemaRefs,
			Set<String> responseFields,
			Set<String> missingDescriptions) {
		Map<String, Object> candidate = map(rawSchema);
		if (candidate.containsKey("$ref")) {
			String schemaRef = ref(candidate);
			if (schemaRef.startsWith("#/components/schemas/") && visitedSchemaRefs.add(schemaRef)) {
				String schemaName = schemaRef.substring(schemaRef.lastIndexOf('/') + 1);
				auditResponseSchema(resolve(schemaRef), schemaName, visitedSchemaRefs,
						responseFields, missingDescriptions);
			}
		}

		map(candidate.get("properties")).forEach((field, rawFieldSchema) -> {
			String fieldPath = qualifiedPath + "." + field;
			Map<String, Object> fieldSchema = map(rawFieldSchema);
			responseFields.add(fieldPath);
			if (!(fieldSchema.get("description") instanceof String description) || description.isBlank()) {
				missingDescriptions.add(fieldPath);
			}
			auditResponseSchema(fieldSchema, fieldPath, visitedSchemaRefs,
					responseFields, missingDescriptions);
		});

		if (candidate.containsKey("items")) {
			auditResponseSchema(candidate.get("items"), qualifiedPath + "[]", visitedSchemaRefs,
					responseFields, missingDescriptions);
		}
		for (String composition : List.of("oneOf", "anyOf", "allOf")) {
			list(candidate.get(composition)).forEach(branch -> auditResponseSchema(
					branch, qualifiedPath, visitedSchemaRefs, responseFields, missingDescriptions));
		}
	}

	private static Map.Entry<String, Operation> entry(String operationId, String method, String path) {
		return Map.entry(operationId, new Operation(method, path, Map.of()));
	}

	private static Map<String, Operation> collectOperations(Map<String, Object> root) {
		Map<String, Operation> found = new LinkedHashMap<>();
		map(root.get("paths")).forEach((path, rawPathItem) -> map(rawPathItem).forEach((method, rawOperation) -> {
			if (!HTTP_METHODS.contains(method)) {
				return;
			}
			Map<String, Object> body = map(rawOperation);
			String operationId = body.get("operationId").toString();
			assertThat(found.put(operationId, new Operation(method.toUpperCase(), path, body)))
					.as("duplicate operationId " + operationId).isNull();
		}));
		return found;
	}

	private static List<Map<String, Object>> resolvedParameters(Map<String, Object> operation) {
		return list(operation.getOrDefault("parameters", List.of())).stream()
				.map(OpenApiContractTest::map)
				.map(parameter -> parameter.containsKey("$ref") ? map(resolve(parameter.get("$ref").toString())) : parameter)
				.toList();
	}

	private static String schemaRef(Object mediaType) {
		String ref = map(map(mediaType).get("schema")).get("$ref").toString();
		return ref.substring(ref.lastIndexOf('/') + 1);
	}

	private static List<Object> errorCodes(String responseName) {
		return list(map(path("components", "responses", responseName)).get("x-error-codes"));
	}

	private static Set<String> declaredErrorCodes(String operationId) {
		Set<String> codes = new TreeSet<>();
		map(operations.get(operationId).body().get("responses")).values().stream()
				.map(OpenApiContractTest::map)
				.map(response -> response.containsKey("$ref") ? map(resolve(ref(response))) : response)
				.map(response -> list(response.get("x-error-codes")))
				.forEach(values -> values.stream().map(Object::toString).forEach(codes::add));
		return codes;
	}

	private static void assertImmutableHistoryProvenance(String schemaName) {
		Map<String, Object> historySchema = schema(schemaName);
		assertThat(historySchema).as(schemaName).containsEntry("type", "object")
				.containsEntry("additionalProperties", false);
		assertThat(list(historySchema.get("required"))).as(schemaName + " required provenance")
				.contains("eventId", "eventType", "occurredAt", "correctionOfEventId", "balanceAdjustment");

		Map<String, Object> properties = map(historySchema.get("properties"));
		Map<String, Object> correction = map(properties.get("correctionOfEventId"));
		assertThat(list(correction.get("type"))).as(schemaName + " nullable correction")
				.containsExactly("string", "null");
		assertThat(correction).containsEntry("format", "uuid");

		List<Map<String, Object>> adjustmentBranches = list(map(properties.get("balanceAdjustment")).get("oneOf"))
				.stream().map(OpenApiContractTest::map).toList();
		assertThat(adjustmentBranches).as(schemaName + " nullable adjustment").hasSize(2)
				.anySatisfy(branch -> assertThat(branch).containsEntry(
						"$ref", "#/components/schemas/BalanceAdjustmentEventReference"))
				.anySatisfy(branch -> assertThat(branch).containsEntry("type", "null"));
	}

	private static void assert422(String operationId, List<String> expectedCodes, String... expectedExamples) {
		Map<String, Object> response = resolvedResponse(operationId, "422");
		assertThat(list(response.get("x-error-codes"))).as(operationId + " 422 codes")
				.containsExactlyElementsOf(expectedCodes);
		Map<String, Object> content = map(response.get("content"));
		Map<String, Object> mediaType = map(content.get("application/json"));
		assertThat(map(mediaType.get("examples")).keySet()).as(operationId + " negative-version examples")
				.contains(expectedExamples);
	}

	private static Map<String, Object> resolvedResponse(String operationId, String status) {
		Map<String, Object> response = map(map(operations.get(operationId).body().get("responses")).get(status));
		return response.containsKey("$ref") ? map(resolve(ref(response))) : response;
	}

	private static String ref(Object value) {
		return map(value).get("$ref").toString();
	}

	private static Map<String, Object> schema(String name) {
		return map(path("components", "schemas", name));
	}

	private static Set<String> schemaNames() {
		return map(path("components", "schemas")).keySet();
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

	private static void walk(Object value, java.util.function.Consumer<Object> visitor) {
		visitor.accept(value);
		if (value instanceof Map<?, ?> map) {
			map.values().forEach(child -> walk(child, visitor));
		} else if (value instanceof List<?> list) {
			list.forEach(child -> walk(child, visitor));
		}
	}

	private static void collectKeys(Object value, Set<String> keys) {
		if (value instanceof Map<?, ?> map) {
			map.forEach((key, child) -> {
				keys.add(key.toString());
				collectKeys(child, keys);
			});
		} else if (value instanceof List<?> list) {
			list.forEach(child -> collectKeys(child, keys));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value == null ? Map.of() : (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return value == null ? List.of() : (List<Object>) value;
	}

	private record Operation(String method, String path, Map<String, Object> body) {
		@Override
		public boolean equals(Object other) {
			return other instanceof Operation operation
					&& method.equals(operation.method) && path.equals(operation.path);
		}

		@Override
		public int hashCode() {
			return 31 * method.hashCode() + path.hashCode();
		}
	}
}
