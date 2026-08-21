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
				entry("listMyCardBalanceAccounts", "GET", "/v1/me/card-balance-accounts"),
				entry("getCardBalanceAccount", "GET", "/v1/card-balance-accounts/{cardBalanceAccountId}"),
				entry("refreshCardBalance", "POST", "/v1/card-balance-accounts/{cardBalanceAccountId}/balance-refreshes"),
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
				entry("getAcademySharedCard", "GET", "/v1/academies/{academyId}/shared-cards/{cardId}")));
		assertThat(operations).hasSize(18);
	}

	@Test
	void requiresSyntheticBearerAndTheApprovedStatusInventoryOnEveryOperation() throws IOException {
		Map<String, Object> securitySchemes = map(path("components", "securitySchemes"));
		assertThat(securitySchemes).containsOnlyKeys("SyntheticBearer").doesNotContainKey("SeedBearer");
		Map<String, Object> scheme = map(securitySchemes.get("SyntheticBearer"));
		assertThat(scheme).containsEntry("type", "http").containsEntry("scheme", "bearer")
				.containsEntry("bearerFormat", "opaque-synthetic-token")
				.containsEntry("description", "Opaque synthetic-principal token. A known token identifies either a student "
						+ "or an authenticated non-student staff principal. Token issuance, refresh, persona selection, and "
						+ "fixture control are outside this contract.");
		assertThat(Files.readString(CONTRACT)).doesNotContain("SeedBearer", "opaque-seed-token");

		operations.forEach((operationId, operation) -> {
			assertThat(list(operation.body().get("security")))
					.as(operationId + " security")
					.containsExactly(Map.of("SyntheticBearer", List.of()));
			Set<String> statuses = map(operation.body().get("responses")).keySet();
			assertThat(statuses).as(operationId + " authentication errors").contains("401", "403");
		});

		Map<String, Set<String>> expected = new LinkedHashMap<>();
		expected.put("listMyCardBalanceAccounts", Set.of("200", "401", "403"));
		expected.put("getCardBalanceAccount", Set.of("200", "401", "403", "404"));
		expected.put("refreshCardBalance", Set.of("200", "401", "403", "404", "503"));
		expected.put("listCardBalanceChanges", Set.of("200", "400", "401", "403", "404"));
		expected.put("listAccountFundMovements", Set.of("200", "400", "401", "403", "404"));
		expected.put("listWishes", Set.of("200", "400", "401", "403", "404"));
		expected.put("createWish", Set.of("201", "400", "401", "403", "404", "409", "415", "422"));
		expected.put("getWish", Set.of("200", "400", "401", "403", "404"));
		expected.put("patchWish", Set.of("200", "400", "401", "403", "404", "409", "415", "422"));
		expected.put("deleteWish", Set.of("200", "400", "401", "403", "404", "409", "422"));
		expected.put("depositToWish", Set.of("200", "400", "401", "403", "404", "409", "422", "503"));
		expected.put("withdrawFromWish", Set.of("200", "400", "401", "403", "404", "409", "422"));
		expected.put("transferWishFunds", Set.of("200", "400", "401", "403", "404", "409", "422"));
		expected.put("completeWish", Set.of("200", "400", "401", "403", "404", "409", "415", "422"));
		expected.put("abandonWish", Set.of("200", "400", "401", "403", "404", "409", "415", "422"));
		expected.put("listWishFundMovements", Set.of("200", "400", "401", "403", "404"));
		expected.put("listAcademySharedCards", Set.of("200", "400", "401", "403", "404"));
		expected.put("getAcademySharedCard", Set.of("200", "401", "403", "404"));

		expected.forEach((operationId, statuses) -> assertThat(map(operations.get(operationId).body().get("responses")).keySet())
				.as(operationId + " statuses").containsExactlyInAnyOrderElementsOf(statuses));
	}

	@Test
	void materializesTheApprovedOwnerScopedCardBalanceAccountDetailContract() {
		String detailPath = "/v1/card-balance-accounts/{cardBalanceAccountId}";
		Map<String, Object> pathItem = map(path("paths", detailPath));
		Map<String, Object> operation = operations.get("getCardBalanceAccount").body();

		assertThat(operation)
				.containsEntry("tags", List.of("Card Balance Accounts"))
				.containsEntry("summary", "Get an owned Card Balance Account")
				.doesNotContainKeys("parameters", "requestBody");
		assertThat(operation.get("description").toString()).contains(
				"authenticated student's active account",
				"current persisted projection",
				"random identifier, closed account, ownership mismatch, and academy mismatch",
				"same not-found response",
				"no external balance lookup",
				"mutates no persistent state",
				"UNKNOWN amounts remain null",
				"later failed attempt retains the latest successful",
				"lastRefreshStatus FAILED");

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
		assertThat(success).containsEntry("description", "Current persisted Card Balance Account projection.");
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
				.contains("absent", "closed", "non-owned", "cross-academy", "hidden");
	}

	@Test
	void preservesTheApprovedComponentAndExampleInventories() {
		assertThat(schemaNames()).hasSize(58);
		assertThat(map(path("components", "responses"))).hasSize(27);
		assertThat(map(path("components", "examples"))).hasSize(37);
	}

	@Test
	void bindsIdempotencyAndConcurrencyOnlyAtTheirApprovedLocations() {
		Set<String> expectedIdempotent = Set.of(
				"createWish", "depositToWish", "withdrawFromWish", "transferWishFunds",
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
		assertThat(list(patchSchema.get("anyOf"))).hasSize(4);
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
				"Decode the request value as a string",
				"Cc, Cf, Zl, or Zp",
				"leading and trailing Unicode Space_Separator",
				"NFC",
				"1 through 200 Unicode code points",
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
				"NFC-normalized", "boundary-space-free", "Cc", "Cf", "Zl", "Zp", "1 through 200");
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
				"missing or non-integer", "400 MALFORMED_REQUEST",
				"decoded negative", "422 INVALID_VERSION",
				"stale non-negative", "409 VERSION_CONFLICT");

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
				"INSUFFICIENT_AVAILABLE_BALANCE", "TARGET_AMOUNT_EXCEEDED", "IDEMPOTENCY_KEY_REUSED");
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
				"Always false", "OPEN Balance Adjustment Case", "successful balance observation");

		assertThat(list(known.get("required"))).contains("balanceAdjustmentInProgress");
		assertThat(knownFlag).containsEntry("type", "boolean");
		assertThat(knownFlag.get("description").toString()).contains(
				"True iff", "OPEN", "response read time", "RESOLVED-only history",
				"one consistent account projection", "later failed lookup");

		assertThat(list(wish.get("required"))).contains("balanceAdjustmentInProgress");
		assertThat(wishFlag).containsEntry("type", "boolean");
		assertThat(wishFlag.get("description").toString()).contains(
				"response snapshot", "derived and not persisted", "committed post-mutation state",
				"does not advance Wish version or updatedAt", "never shortage amount");
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
				"captured by that original result", "rather than the current read-time value");
		assertThat(replayWish).containsEntry("balanceAdjustmentInProgress", true);

		Map<String, Object> knownOpen = map(map(examples.get("KnownBalanceAdjustmentOpen")).get("value"));
		Map<String, Object> wishOpen = map(map(examples.get("WishBalanceAdjustmentOpen")).get("value"));
		assertThat(map(examples.get("KnownBalanceAdjustmentOpen")))
				.containsEntry("summary", "known-balance-adjustment-open")
				.containsEntry("x-schema-ref", "#/components/schemas/KnownCardBalanceAccount");
		assertThat(knownOpen).containsEntry("balanceAdjustmentInProgress", true)
				.containsEntry("ledgerAvailableBalance", -20000)
				.containsEntry("unresolvedShortage", 20000);
		assertThat(map(examples.get("WishBalanceAdjustmentOpen")))
				.containsEntry("summary", "wish-balance-adjustment-open")
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
				"matching successful Idempotency-Key result is replayed before",
				"OPEN Balance Adjustment Case", "before a new Wish is persisted");
		assertThat(ref(map(map(create.get("responses")).get("409"))))
				.isEqualTo("#/components/responses/CreateConflict");
		assertThat(errorCodes("CreateConflict"))
				.containsExactly("BALANCE_MISMATCH_LOCKED", "IDEMPOTENCY_KEY_REUSED");
		Map<String, Object> createConflict = map(path("components", "responses", "CreateConflict"));
		Map<String, Object> createConflictJson = map(map(createConflict.get("content"))
				.get("application/json"));
		assertThat(map(createConflictJson.get("examples")))
				.containsKey("balance-mismatch-locked");

		assertThat(operations.get("patchWish").body().get("description").toString()).contains(
				"completed and abandoned Wishes may change visibility only",
				"changing an abandoned Wish's visibility updates owner-visible Wish metadata",
				"never creates a shared card", "rejects every requested patch field", "purpose",
				"targetAmount", "targetDate", "every visibility change", "widening",
				"narrowing", "PRIVATE");
		assertThat(errorCodes("PatchConflict")).containsExactly(
				"VERSION_CONFLICT", "INVALID_STATE_TRANSITION", "BALANCE_MISMATCH_LOCKED");
		assertThat(errorCodes("DeleteConflict")).containsExactly(
				"VERSION_CONFLICT", "IDEMPOTENCY_KEY_REUSED");
		assertThat(errorCodes("StateMutationConflict")).containsExactly(
				"VERSION_CONFLICT", "INVALID_STATE_TRANSITION", "IDEMPOTENCY_KEY_REUSED");
		assertThat(errorCodes("WithdrawalConflict")).doesNotContain("BALANCE_MISMATCH_LOCKED");
		assertThat(errorCodes("DepositConflict")).contains("BALANCE_MISMATCH_LOCKED");
		assertThat(errorCodes("TransferConflict")).contains("BALANCE_MISMATCH_LOCKED");

		for (String operationId : List.of(
				"refreshCardBalance", "listMyCardBalanceAccounts", "getCardBalanceAccount", "listWishes", "getWish",
				"listCardBalanceChanges", "listAccountFundMovements", "listWishFundMovements",
				"withdrawFromWish", "completeWish", "abandonWish", "deleteWish")) {
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
				"owned tombstoned Wish", "returns 200");
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
					"occurredAt DESC then eventId DESC",
					"ordering version",
					"without a partial page",
					"strictly below",
					"equal timestamps",
					"Any valid limit",
					"Authorization and ownership are re-evaluated on every request",
					"no cacheability guarantee");
			assertThat(resolvedParameters(operation))
					.extracting(parameter -> parameter.get("name"))
					.containsExactly("cursor", "limit");
		}
		assertThat(operations.get("listWishFundMovements").body().get("description").toString())
				.contains("account, Wish");
	}

	@Test
	void documentsTheProvisionalSharedCardOrderWithoutClientRankingControls() {
		Map<String, Object> operation = operations.get("listAcademySharedCards").body();
		assertThat(operation.get("description").toString()).contains(
				"Provisional ordering",
				"contentUpdatedAt DESC, then sharedCardId DESC",
				"No sort parameter is currently supported",
				"Under this temporary policy, only content or publication changes reorder cards",
				"Friend-priority and embedding-based recommendation ordering remain open for a future contract",
				"not active in this version");
		assertThat(resolvedParameters(operation))
				.extracting(parameter -> parameter.get("name"))
				.containsExactly("cursor", "limit")
				.doesNotContain("sort", "ranking", "friendPriority", "embeddingRecommendation");
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
