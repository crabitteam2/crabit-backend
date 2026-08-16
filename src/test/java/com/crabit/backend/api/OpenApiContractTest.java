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
		assertThat(operations).hasSize(17);
	}

	@Test
	void requiresSeedBearerAndTheApprovedStatusInventoryOnEveryOperation() {
		Map<String, Object> scheme = map(path("components", "securitySchemes", "SeedBearer"));
		assertThat(scheme).containsEntry("type", "http").containsEntry("scheme", "bearer")
				.containsEntry("bearerFormat", "opaque-seed-token");

		operations.forEach((operationId, operation) -> {
			assertThat(list(operation.body().get("security")))
					.as(operationId + " security")
					.containsExactly(Map.of("SeedBearer", List.of()));
			Set<String> statuses = map(operation.body().get("responses")).keySet();
			assertThat(statuses).as(operationId + " authentication errors").contains("401", "403");
		});

		Map<String, Set<String>> expected = new LinkedHashMap<>();
		expected.put("listMyCardBalanceAccounts", Set.of("200", "401", "403"));
		expected.put("refreshCardBalance", Set.of("200", "401", "403", "404", "503"));
		expected.put("listCardBalanceChanges", Set.of("200", "400", "401", "403", "404"));
		expected.put("listAccountFundMovements", Set.of("200", "400", "401", "403", "404"));
		expected.put("listWishes", Set.of("200", "400", "401", "403", "404"));
		expected.put("createWish", Set.of("201", "400", "401", "403", "404", "409", "422"));
		expected.put("getWish", Set.of("200", "401", "403", "404"));
		expected.put("patchWish", Set.of("200", "400", "401", "403", "404", "409", "415", "422"));
		expected.put("deleteWish", Set.of("200", "400", "401", "403", "404", "409", "422"));
		expected.put("depositToWish", Set.of("200", "400", "401", "403", "404", "409", "422", "503"));
		expected.put("withdrawFromWish", Set.of("200", "400", "401", "403", "404", "409", "422"));
		expected.put("transferWishFunds", Set.of("200", "400", "401", "403", "404", "409", "422"));
		expected.put("completeWish", Set.of("200", "400", "401", "403", "404", "409", "422"));
		expected.put("abandonWish", Set.of("200", "400", "401", "403", "404", "409", "422"));
		expected.put("listWishFundMovements", Set.of("200", "400", "401", "403", "404"));
		expected.put("listAcademySharedCards", Set.of("200", "400", "401", "403", "404"));
		expected.put("getAcademySharedCard", Set.of("200", "401", "403", "404"));

		expected.forEach((operationId, statuses) -> assertThat(map(operations.get(operationId).body().get("responses")).keySet())
				.as(operationId + " statuses").containsExactlyInAnyOrderElementsOf(statuses));
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
	void resolvesEveryInternalReferenceAndKeepsHistoryAndPaginationDiscriminated() {
		List<String> unresolved = new ArrayList<>();
		walk(document, node -> {
			if (node instanceof Map<?, ?> candidate && candidate.get("$ref") instanceof String ref
					&& ref.startsWith("#/") && resolve(ref) == null) {
				unresolved.add(ref);
			}
		});
		assertThat(unresolved).isEmpty();
		assertThat(list(schema("CardBalanceChange").get("oneOf"))).hasSize(2);
		assertThat(list(schema("AccountFundMovement").get("oneOf"))).hasSize(6);
		assertThat(list(schema("WishFundMovement").get("oneOf"))).hasSize(6);
		assertThat(list(schema("SharedCard").get("oneOf"))).hasSize(2);
		assertThat(map(path("components", "parameters", "Limit", "schema")))
				.containsEntry("default", 20).containsEntry("maximum", 100);
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
