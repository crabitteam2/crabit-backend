package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:wish-openapi-documentation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=none",
	"spring.flyway.enabled=false",
	"spring.main.banner-mode=off",
	"logging.level.root=warn",
	"crabit.documentation.enabled=true"
})
@AutoConfigureMockMvc
class WishOpenApiDocumentationTest {

	private static final String COLLECTION =
			"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes";
	private static final String ITEM = COLLECTION + "/{wishId}";
	private static final String COMPLETION = ITEM + "/completion";
	private static final String ABANDONMENT = ITEM + "/abandonment";
	private static final String DEPOSIT = ITEM + "/deposits";
	private static final String WITHDRAWAL = ITEM + "/withdrawals";
	private static final String WISH_HISTORY = ITEM + "/fund-movements";
	private static final String TRANSFER =
			"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers";
	private static final String CARD_HISTORY =
			"/v1/card-balance-accounts/{cardBalanceAccountId}/card-balance-changes";
	private static final String ACCOUNT_HISTORY =
			"/v1/card-balance-accounts/{cardBalanceAccountId}/fund-movements";
	private static final String ACCOUNT_DETAIL =
			"/v1/card-balance-accounts/{cardBalanceAccountId}";
	private static final String REPRESENTATIVE =
			"/v1/card-balance-accounts/{cardBalanceAccountId}/representative-wish";
	private static final Set<String> MOVEMENT_PATHS = Set.of(DEPOSIT, WITHDRAWAL, TRANSFER);

	@Autowired
	private MockMvc mockMvc;

	@Test
	void generatedMovementOperationsMatchTheImplementedCanonicalSubset() throws Exception {
		Map<String, Object> canonical = canonicalDocument();
		Map<String, Object> generated = document();

		for (String path : List.of(DEPOSIT, WITHDRAWAL, TRANSFER)) {
			assertThat(movementOperationProjection(generated, path))
					.as("generated POST %s", path)
					.isEqualTo(movementOperationProjection(canonical, path));
		}

		for (String schemaName : List.of(
				"WishMutationResult", "WishAmountCommand",
				"WishTransferRequest", "WishTransferResult")) {
			assertThat(componentShape(generated, schemaName))
					.as("generated component %s", schemaName)
					.isEqualTo(componentShape(canonical, schemaName));
		}
		assertThat(componentShape(generated, "Wish"))
				.as("generated Wish includes the approved adjustment projection")
				.isEqualTo(componentShape(canonical, "Wish"));

		for (String schemaName : List.of(
				"Uuid", "KrwPositive", "KrwNonNegative", "WishVersion",
				"Purpose", "WishState", "WishVisibility", "UtcInstant")) {
			assertThat(schemaShape(componentSchema(generated, schemaName)))
					.as("generated primitive component %s", schemaName)
					.isEqualTo(schemaShape(componentSchema(canonical, schemaName)));
		}
		for (String parameterName : List.of(
				"CardBalanceAccountId", "WishId", "IdempotencyKey")) {
			assertThat(parameterShape(value(generated,
						"components", "parameters", parameterName)))
					.as("generated parameter component %s", parameterName)
					.isEqualTo(parameterShape(value(canonical,
							"components", "parameters", parameterName)));
		}
		assertThat(headerShape(object(value(generated,
				"components", "headers", "IdempotencyReplayed"))))
				.as("generated IdempotencyReplayed header")
				.isEqualTo(headerShape(object(value(canonical,
						"components", "headers", "IdempotencyReplayed"))));
		for (String responseName : List.of(
				"WishMutationSuccess", "MalformedOrIdempotencyRequired", "AuthRequired",
				"Forbidden", "WishOrAccountNotFound", "DepositConflict",
				"WithdrawalConflict", "TransferConflict", "InvalidAmountOrVersion",
				"InvalidTransferAmountOrVersion", "BalanceSyncFailed")) {
			assertThat(responseShape(object(value(generated,
						"components", "responses", responseName))))
					.as("generated response component %s", responseName)
					.isEqualTo(responseShape(object(value(canonical,
							"components", "responses", responseName))));
		}
		Map<String, Object> generatedSecuritySchemes =
				object(value(generated, "components", "securitySchemes"));
		assertThat(generatedSecuritySchemes)
				.containsOnlyKeys("SyntheticBearer")
				.doesNotContainKey("SeedBearer");
		assertThat(securitySchemeShape(object(generatedSecuritySchemes.get("SyntheticBearer"))))
				.as("generated SyntheticBearer security scheme")
				.isEqualTo(securitySchemeShape(object(value(canonical,
						"components", "securitySchemes", "SyntheticBearer"))));
	}

	@Test
	void generatedHistoryOperationsExposeTheApprovedImmutableProjectionSurface() throws Exception {
		Map<String, Object> generated = document();
		Map<String, String> responseSchemas = Map.of(
				CARD_HISTORY, "CardBalanceChangePage",
				ACCOUNT_HISTORY, "AccountFundMovementPage",
				WISH_HISTORY, "WishFundMovementPage");
		Map<String, String> operationIds = Map.of(
				CARD_HISTORY, "listCardBalanceChanges",
				ACCOUNT_HISTORY, "listAccountFundMovements",
				WISH_HISTORY, "listWishFundMovements");
		for (String path : responseSchemas.keySet()) {
			Map<String, Object> operation = operation(generated, path, "get");
			assertThat(operation).containsEntry("operationId", operationIds.get(path));
			assertThat(operation.get("security"))
					.isEqualTo(List.of(Map.of("SyntheticBearer", List.of())));
			assertThat(object(operation.get("responses")).keySet())
					.containsExactlyInAnyOrder("200", "400", "401", "403", "404");
			assertThat(responseSchemaRef(generated, object(object(operation.get("responses")).get("200"))))
					.isEqualTo("#/components/schemas/" + responseSchemas.get(path));
			assertThat(operation.get("description").toString().replaceAll("\\s+", " ")).contains(
					"occurredAt DESC", "eventId DESC", "정렬 버전",
					"부분 페이지 없이", "엄격히", "같은 타임스탬프",
					"유효한 limit", "권한과 소유권",
					"캐시 가능성을 보장하지 않습니다");
			Map<String, Object> limit = schema(parameter(generated, path, "get", "limit"));
			assertThat(limit).containsEntry("minimum", 1).containsEntry("maximum", 100)
					.containsEntry("default", 20);
			assertParameter(generated, path, "get", "cursor", "query", false, null, null, null);
		}

		assertThat(list(componentSchema(generated, "AccountFundMovement"), "oneOf"))
				.extracting(WishOpenApiDocumentationTest::object)
				.extracting(branch -> branch.get("$ref"))
				.containsExactlyInAnyOrder(
						"#/components/schemas/AccountCardBalanceChange",
						"#/components/schemas/AccountWishDeposit",
						"#/components/schemas/AccountWishWithdrawal",
						"#/components/schemas/AccountWishTransfer",
						"#/components/schemas/AccountWishCompletionReturn",
						"#/components/schemas/AccountWishAbandonmentReturn",
						"#/components/schemas/AccountWishDeletionReturn");
		assertThat(list(componentSchema(generated, "WishFundMovement"), "oneOf")).hasSize(6);
		assertThat(property(componentSchema(generated, "WishFundMovementPage"), "wish"))
				.containsEntry("$ref", "#/components/schemas/WishHistorySubject");
		assertThat(list(property(componentSchema(generated, "CardBalanceChange"), "balanceAdjustment"), "oneOf"))
				.anySatisfy(branch -> assertThat(object(branch))
						.containsEntry("$ref", "#/components/schemas/BalanceAdjustmentEventReference"));
	}

	@Test
	void generatedAccountDetailOperationMatchesTheApprovedCanonicalContract() throws Exception {
		Map<String, Object> generated = document();
		Map<String, Object> canonical = canonicalDocument();
		Map<String, Object> operation = operation(generated, ACCOUNT_DETAIL, "get");

		assertThat(operation)
				.containsEntry("operationId", "getCardBalanceAccount")
				.containsEntry("summary", "소유한 카드 잔액 계정 조회")
				.containsEntry("tags", List.of("Card Balance Accounts"))
				.containsEntry("security", List.of(Map.of("SyntheticBearer", List.of())))
				.doesNotContainKey("requestBody");
		assertThat(operation.get("description").toString()).contains(
				"인증된 학생의 활성 계정", "현재 저장된 프로젝션",
				"임의 식별자, 종료된 계정, 소유권 불일치, 학원 불일치",
				"같은 리소스 없음 응답", "외부 잔액 조회를 수행하지 않으며",
				"영속 상태를 변경하지 않습니다", "UNKNOWN 금액은 null",
				"후속 시도가 실패", "마지막으로 성공한 금액",
				"lastRefreshStatus", "FAILED");

		assertParameter(generated, ACCOUNT_DETAIL, "get", "cardBalanceAccountId", "path", true, "uuid", null, null);
		Map<String, Object> responses = object(operation.get("responses"));
		assertThat(responses.keySet()).containsExactlyInAnyOrder("200", "401", "403", "404");
		assertThat(responseSchemaRef(generated, object(responses.get("200"))))
				.isEqualTo("#/components/schemas/CardBalanceAccount");
		for (String status : List.of("401", "403", "404")) {
			assertThat(responseSchemaRef(generated, object(responses.get(status))))
					.as("detail %s schema", status)
					.isEqualTo("#/components/schemas/ErrorEnvelope");
		}
		Map<String, Object> unauthorized = resolve(generated, object(responses.get("401")));
		assertThat(object(object(object(unauthorized.get("headers"))
				.get("WWW-Authenticate")).get("schema"))).containsEntry("const", "Bearer");
		assertThat(resolve(generated, object(responses.get("404"))).get("description").toString())
				.contains("CARD_BALANCE_ACCOUNT_NOT_FOUND", "없거나 종료되었거나", "소유하지 않거나", "다른 학원 소속", "숨");

		Map<String, Object> generatedExamples = object(object(object(responses.get("200"))
				.get("content")).get("application/json"));
		generatedExamples = object(generatedExamples.get("examples"));
		assertThat(generatedExamples)
				.containsOnlyKeys("unknown", "failed-refresh-known", "adjustment-open-known");
		Map<String, Object> canonicalExamples = object(value(canonical, "components", "examples"));
		Map<String, Object> canonicalSuccess = object(object(
				operation(canonical, ACCOUNT_DETAIL, "get").get("responses")).get("200"));
		Map<String, Object> canonicalDetailExamples = object(object(object(
				canonicalSuccess.get("content")).get("application/json")).get("examples"));
		assertThat(object(object(generatedExamples.get("unknown")).get("value")))
				.isEqualTo(object(object(canonicalDetailExamples.get("unknown")).get("value")));
		assertThat(object(resolve(generated, object(generatedExamples.get("failed-refresh-known"))).get("value")))
				.isEqualTo(object(object(canonicalExamples.get("FailedRefreshKnownBalance")).get("value")));
		assertThat(object(resolve(generated, object(generatedExamples.get("adjustment-open-known"))).get("value")))
				.isEqualTo(object(object(canonicalExamples.get("KnownBalanceAdjustmentOpen")).get("value")));
	}

	@Test
	void canonicalContractMaterializesTheApprovedAdjustmentProjectionAndOperationMatrix() throws Exception {
		Map<String, Object> canonical = canonicalDocument();
		Map<String, Object> unknown = componentSchema(canonical, "UnknownCardBalanceAccount");
		Map<String, Object> known = componentSchema(canonical, "KnownCardBalanceAccount");
		Map<String, Object> wish = componentSchema(canonical, "Wish");

		assertThat(list(unknown, "required")).contains("balanceAdjustmentInProgress");
		assertThat(property(unknown, "balanceAdjustmentInProgress"))
				.containsEntry("type", "boolean")
				.containsEntry("const", false);
		assertThat(list(known, "required")).contains("balanceAdjustmentInProgress");
		assertThat(property(known, "balanceAdjustmentInProgress"))
				.containsEntry("type", "boolean");
		assertThat(property(known, "balanceAdjustmentInProgress").get("description").toString())
				.contains("OPEN", "응답 조회 시점", "RESOLVED 이력만", "이후 조회가 실패");
		assertThat(list(wish, "required")).contains("balanceAdjustmentInProgress");
		assertThat(property(wish, "balanceAdjustmentInProgress"))
				.containsEntry("type", "boolean");
		assertThat(property(wish, "balanceAdjustmentInProgress").get("description").toString())
				.contains("변경이 커밋된 후의 값", "위시의 version이나 updatedAt은 증가하지 않습니다",
						"boolean 값만 노출하며 부족액", "절대 노출하지 않습니다");

		Map<String, Object> create = operation(canonical, COLLECTION, "post");
		assertThat(create.get("description").toString()).contains(
				"현재 불일치 방어 조건보다 먼저",
				"BALANCE_MISMATCH_LOCKED", "새 위시를 저장하기 전에");
		assertThat(object(object(create.get("responses")).get("409")))
				.containsEntry("$ref", "#/components/responses/CreateConflict");
		assertThat(list(object(value(canonical, "components", "responses", "CreateConflict")),
				"x-error-codes"))
				.containsExactly("BALANCE_MISMATCH_LOCKED", "IDEMPOTENCY_KEY_REUSED");

		assertThat(operation(canonical, ITEM, "patch").get("description").toString()).contains(
				"모든 요청 필드", "공개 범위를 확대·축소하거나 PRIVATE로 바꾸는",
				"PRIVATE");
		assertThat(list(object(value(canonical, "components", "responses", "PatchConflict")),
				"x-error-codes"))
				.contains("BALANCE_MISMATCH_LOCKED");
		assertThat(list(object(value(canonical, "components", "responses", "DeleteConflict")),
				"x-error-codes"))
				.doesNotContain("BALANCE_MISMATCH_LOCKED");
		assertThat(list(object(value(canonical, "components", "responses", "StateMutationConflict")),
				"x-error-codes"))
				.doesNotContain("BALANCE_MISMATCH_LOCKED");
		assertThat(list(object(value(canonical, "components", "responses", "WithdrawalConflict")),
				"x-error-codes"))
				.doesNotContain("BALANCE_MISMATCH_LOCKED");
		assertThat(list(object(value(canonical, "components", "responses", "DepositConflict")),
				"x-error-codes"))
				.contains("BALANCE_MISMATCH_LOCKED");
		assertThat(list(object(value(canonical, "components", "responses", "TransferConflict")),
				"x-error-codes"))
				.contains("BALANCE_MISMATCH_LOCKED");

		Map<String, Object> examples = object(value(canonical, "components", "examples"));
		assertThat(object(examples.get("KnownBalanceAdjustmentOpen")))
				.containsEntry("x-schema-ref", "#/components/schemas/KnownCardBalanceAccount");
		assertThat(object(examples.get("KnownBalanceAdjustmentOpen")).get("summary").toString())
				.containsPattern(".*[가-힣].*");
		assertThat(object(object(examples.get("KnownBalanceAdjustmentOpen")).get("value")))
				.containsEntry("balanceAdjustmentInProgress", true);
		assertThat(object(examples.get("WishBalanceAdjustmentOpen")))
				.containsEntry("x-schema-ref", "#/components/schemas/Wish");
		assertThat(object(examples.get("WishBalanceAdjustmentOpen")).get("summary").toString())
				.containsPattern(".*[가-힣].*");
		assertThat(object(object(examples.get("WishBalanceAdjustmentOpen")).get("value")))
				.containsEntry("balanceAdjustmentInProgress", true)
				.doesNotContainKeys("unresolvedShortage", "adjustmentCaseId", "observationId");
	}

	@Test
	void documentsMetadataSecurityAndEveryWishLifecycleOperation() throws Exception {
		Map<String, Object> document = document();
		Map<String, Object> canonical = canonicalDocument();

		assertThat(value(document, "info")).isEqualTo(value(canonical, "info"));

		Map<String, Object> wishesTag = list(document, "tags").stream()
				.map(WishOpenApiDocumentationTest::object)
				.filter(tag -> "Wishes".equals(tag.get("name")))
				.findFirst()
				.orElseThrow();
		Map<String, Object> canonicalWishesTag = list(canonical, "tags").stream()
				.map(WishOpenApiDocumentationTest::object)
				.filter(tag -> "Wishes".equals(tag.get("name")))
				.findFirst()
				.orElseThrow();
		assertThat(wishesTag).isEqualTo(canonicalWishesTag);

		Map<String, Object> securitySchemes = object(value(document, "components", "securitySchemes"));
		assertThat(securitySchemes).containsOnlyKeys("SyntheticBearer").doesNotContainKey("SeedBearer");
		Map<String, Object> syntheticBearer = object(securitySchemes.get("SyntheticBearer"));
		assertThat(syntheticBearer)
				.containsEntry("type", "http")
				.containsEntry("scheme", "bearer")
				.containsEntry("bearerFormat", "opaque-synthetic-token");

		Map<String, OperationContract> expected = new LinkedHashMap<>();
		expected.put("get " + COLLECTION, new OperationContract(
				"listWishes", "List owned Wishes",
				List.of("owned, non-tombstoned", "createdAt descending", "opaque cursor", "state filtering")));
		expected.put("post " + COLLECTION, new OperationContract(
				"createWish", "Create a Wish",
				List.of("IN_PROGRESS", "PRIVATE", "zero allocated amount",
						"replayed before evaluating the current mismatch guard",
						"BALANCE_MISMATCH_LOCKED", "before a new Wish is persisted")));
		expected.put("get " + ITEM, new OperationContract(
				"getWish", "Get an owned Wish",
				List.of("owned, non-tombstoned", "tombstones", "404")));
		expected.put("get " + REPRESENTATIVE, new OperationContract(
				"getRepresentativeWish", "Get the current representative Wish",
				List.of("authenticated student", "nondeleted", "204", "OPEN Balance Adjustment",
						"no external balance lookup")));
		expected.put("put " + REPRESENTATIVE, new OperationContract(
				"selectRepresentativeWish", "Select the representative Wish",
				List.of("Atomically replaces", "200 no-op", "OPEN Balance Adjustment",
						"no ledger event", "account-first lock")));
		expected.put("patch " + ITEM, new OperationContract(
				"patchWish", "Edit a Wish",
				List.of("optimistic atomic merge patch", "Omitted", "targetDate null", "completed Wishes",
						"balance mismatch")));
		expected.put("delete " + ITEM, new OperationContract(
				"deleteWish", "Delete a Wish",
				List.of("tombstones", "allocated amount", "shared-card projection", "hides subsequent reads")));
		expected.put("post " + COMPLETION, new OperationContract(
				"completeWish", "Complete a funded Wish",
				List.of("AMOUNT_REACHED", "completion ledger event", "amount to zero", "completedAt")));
		expected.put("post " + ABANDONMENT, new OperationContract(
				"abandonWish", "Abandon a Wish",
				List.of("active Wish", "abandonment ledger event", "PRIVATE visibility", "shared-card projection")));
		expected.put("post " + DEPOSIT, new OperationContract(
				"depositToWish", "Deposit Card Balance Account funds into one Wish",
				List.of("PRE_DEPOSIT", "Provider failure", "mismatch observation")));
		expected.put("post " + WITHDRAWAL, new OperationContract(
				"withdrawFromWish", "Withdraw funds from one Wish",
				List.of()));
		expected.put("post " + TRANSFER, new OperationContract(
				"transferWishFunds", "Atomically transfer funds between two Wishes in one account",
				List.of()));
		expected.put("get " + WISH_HISTORY, new OperationContract(
				"listWishFundMovements", "List immutable fund movements projected for one Wish",
				List.of("owned tombstoned Wish", "occurredAt DESC then eventId DESC",
						"Authorization and ownership are")));

		assertThat(operationInventory(document)).containsExactlyInAnyOrderElementsOf(expected.keySet());
			expected.forEach((key, contract) -> {
			String[] parts = key.split(" ", 2);
			Map<String, Object> operation = operation(document, parts[1], parts[0]);
			assertThat(operation)
					.containsEntry("operationId", contract.operationId())
					.isEqualTo(operation(canonical, parts[1], parts[0]));
			assertThat(operation.get("tags")).as(key + " tag").isEqualTo(List.of("Wishes"));
			assertThat(operation.get("security")).as(key + " security")
					.isEqualTo(List.of(Map.of("SyntheticBearer", List.of())));
		});
	}

	@Test
	void documentsExactParameterAndRequestBodyConstraints() throws Exception {
		Map<String, Object> document = document();
		Map<String, Object> list = operation(document, COLLECTION, "get");
		assertParameter(document, COLLECTION, "get", "cardBalanceAccountId", "path", true, "uuid", null, null);
		assertThat(parameter(document, COLLECTION, "get", "cursor").get("description").toString())
				.contains("불투명", "고정 정렬 순서");
		assertThat(schema(parameter(document, COLLECTION, "get", "cursor")))
				.containsEntry("$ref", "#/components/schemas/Cursor");
		Map<String, Object> limit = schema(parameter(document, COLLECTION, "get", "limit"));
		assertThat(limit).containsEntry("minimum", 1).containsEntry("maximum", 100)
				.containsEntry("default", 20);
		Map<String, Object> state = schema(parameter(document, COLLECTION, "get", "state"));
		assertThat(state).containsEntry("type", "array").containsEntry("uniqueItems", true);
		assertThat(object(state.get("items")))
				.containsEntry("$ref", "#/components/schemas/WishState");
		assertThat(componentSchema(document, "WishState").get("enum"))
				.isEqualTo(List.of("IN_PROGRESS", "AMOUNT_REACHED", "COMPLETED", "ABANDONED"));
		for (String key : operationInventory(document)) {
			String[] parts = key.split(" ", 2);
			if (MOVEMENT_PATHS.contains(parts[1])) continue;
			Map<String, Object> operation = operation(document, parts[1], parts[0]);
			assertParameter(document, parts[1], parts[0], "cardBalanceAccountId", "path", true, "uuid", null, null);
			if (parts[1].contains("{wishId}")) {
				assertParameter(document, parts[1], parts[0], "wishId", "path", true, "uuid", null, null);
			}
		}

		Map<String, Object> create = operation(document, COLLECTION, "post");
		assertParameter(document, COLLECTION, "post", "Idempotency-Key", "header", true, null, 1, 200);
		assertRequestSchema(create, "application/json", "CreateWishRequest");
		Map<String, Object> createSchema = componentSchema(document, "CreateWishRequest");
		assertThat(createSchema.get("additionalProperties")).isEqualTo(false);
		assertThat(createSchema.get("required")).isEqualTo(List.of("purpose", "targetAmount"));
		assertProperty(createSchema, "purpose", "normalization", 1, 200, null, null, null);
		assertProperty(createSchema, "targetAmount", "integer Korean won", null, null,
				1L, 9_007_199_254_740_991L, null);
		assertProperty(createSchema, "targetDate", "nullable ISO calendar date", null, null,
				null, null, "date");

		Map<String, Object> patch = operation(document, ITEM, "patch");
		assertRequestSchema(patch, "application/merge-patch+json", "WishMergePatch");
		Map<String, Object> patchSchema = componentSchema(document, "WishMergePatch");
		assertThat(patchSchema.get("additionalProperties")).isEqualTo(false);
		assertThat(patchSchema.get("required")).isEqualTo(List.of("expectedVersion"));
		assertThat(optionalList(patchSchema, "anyOf"))
				.extracting(entry -> list(object(entry), "required"))
				.containsExactly(List.of("purpose"), List.of("targetAmount"),
						List.of("targetDate"), List.of("visibility"));
		assertProperty(patchSchema, "expectedVersion", "non-negative", null, null, 0L, null, null);
		assertProperty(patchSchema, "targetAmount", "currently allocated amount", null, null,
				1L, 9_007_199_254_740_991L, null);
		assertProperty(patchSchema, "targetDate", "null clears", null, null, null, null, "date");
		assertThat(resolve(canonicalDocument(), property(patchSchema, "visibility")).get("enum"))
				.isEqualTo(List.of("PRIVATE", "FRIENDS", "ACADEMY"));

		Map<String, Object> delete = operation(document, ITEM, "delete");
		assertParameter(document, ITEM, "delete", "If-Match", "header", true, null, null, null);
		assertThat(resolve(canonicalDocument(), schema(parameter(document, ITEM, "delete", "If-Match"))))
				.containsEntry("minimum", 0);
		assertThat(parameter(document, ITEM, "delete", "If-Match").get("description").toString())
				.contains("음수 아닌 정수");
		assertParameter(document, ITEM, "delete", "Idempotency-Key", "header", true, null, 1, 200);

		for (String path : List.of(COMPLETION, ABANDONMENT)) {
			Map<String, Object> operation = operation(document, path, "post");
			assertParameter(document, path, "post", "Idempotency-Key", "header", true, null, 1, 200);
			assertRequestSchema(operation, "application/json", "WishVersionCommand");
		}
		Map<String, Object> version = componentSchema(document, "WishVersionCommand");
		assertThat(version.get("additionalProperties")).isEqualTo(false);
		assertThat(version.get("required")).isEqualTo(List.of("expectedVersion"));
		assertProperty(version, "expectedVersion", "non-negative", null, null, 0L, null, null);

		for (String path : List.of(DEPOSIT, WITHDRAWAL)) {
			Map<String, Object> operation = operation(document, path, "post");
			assertThat(optionalList(operation, "parameters"))
					.extracting(parameter -> object(parameter).get("$ref"))
					.containsExactly("#/components/parameters/IdempotencyKey");
			assertRequestSchema(operation, "application/json", "WishAmountCommand");
		}
		Map<String, Object> amountCommand = componentSchema(document, "WishAmountCommand");
		assertThat(amountCommand.get("additionalProperties")).isEqualTo(false);
		assertThat(amountCommand.get("required"))
				.isEqualTo(List.of("amount", "expectedVersion"));
		assertThat(property(amountCommand, "amount"))
				.containsEntry("$ref", "#/components/schemas/KrwPositive");
		assertThat(property(amountCommand, "expectedVersion"))
				.containsEntry("$ref", "#/components/schemas/WishVersion");

		Map<String, Object> transfer = operation(document, TRANSFER, "post");
		assertThat(optionalList(transfer, "parameters"))
				.extracting(parameter -> object(parameter).get("$ref"))
				.containsExactly("#/components/parameters/IdempotencyKey");
		assertRequestSchema(transfer, "application/json", "WishTransferRequest");
		Map<String, Object> transferRequest = componentSchema(document, "WishTransferRequest");
		assertThat(transferRequest.get("additionalProperties")).isEqualTo(false);
		assertThat(list(transferRequest, "required")).containsExactlyInAnyOrderElementsOf(List.of(
				"sourceWishId", "destinationWishId", "amount",
				"sourceExpectedVersion", "destinationExpectedVersion"));
		assertThat(property(transferRequest, "sourceWishId"))
				.containsEntry("$ref", "#/components/schemas/Uuid");
		assertThat(property(transferRequest, "destinationWishId"))
				.containsEntry("$ref", "#/components/schemas/Uuid");
		assertThat(property(transferRequest, "amount"))
				.containsEntry("$ref", "#/components/schemas/KrwPositive");
		assertThat(property(transferRequest, "sourceExpectedVersion"))
				.containsEntry("$ref", "#/components/schemas/WishVersion");
		assertThat(property(transferRequest, "destinationExpectedVersion"))
				.containsEntry("$ref", "#/components/schemas/WishVersion");
		Map<String, Object> representative = operation(document, REPRESENTATIVE, "put");
		assertRequestSchema(representative, "application/json", "RepresentativeWishSelectionRequest");
		Map<String, Object> representativeRequest =
				componentSchema(document, "RepresentativeWishSelectionRequest");
		assertThat(representativeRequest.get("additionalProperties")).isEqualTo(false);
		assertThat(list(representativeRequest, "required")).containsExactly("wishId");
		assertThat(property(representativeRequest, "wishId"))
				.containsEntry("$ref", "#/components/schemas/Uuid");
		for (String requestSchema : List.of(
				"CreateWishRequest", "WishMergePatch", "WishVersionCommand",
				"WishAmountCommand", "WishTransferRequest", "RepresentativeWishSelectionRequest")) {
			assertThat(normalizeRequiredOrder(componentSchema(document, requestSchema)))
					.isEqualTo(normalizeRequiredOrder(componentSchema(canonicalDocument(), requestSchema)));
		}
	}

	@Test
	void documentsExactResponsesErrorCodesHeadersAndNamedExamples() throws Exception {
		Map<String, Object> document = document();
		Map<String, Set<String>> expectedStatuses = Map.ofEntries(
				Map.entry("get " + COLLECTION, Set.of("200", "400", "401", "403", "404")),
				Map.entry("post " + COLLECTION, Set.of("201", "400", "401", "403", "404", "409", "415", "422")),
				Map.entry("get " + ITEM, Set.of("200", "400", "401", "403", "404")),
				Map.entry("get " + REPRESENTATIVE, Set.of("200", "204", "400", "401", "403", "404")),
				Map.entry("put " + REPRESENTATIVE, Set.of("200", "400", "401", "403", "404", "409", "415")),
				Map.entry("patch " + ITEM, Set.of("200", "400", "401", "403", "404", "409", "415", "422")),
				Map.entry("delete " + ITEM, Set.of("200", "400", "401", "403", "404", "409", "422")),
				Map.entry("post " + COMPLETION, Set.of("200", "400", "401", "403", "404", "409", "415", "422")),
				Map.entry("post " + ABANDONMENT, Set.of("200", "400", "401", "403", "404", "409", "415", "422")),
				Map.entry("post " + DEPOSIT, Set.of("200", "400", "401", "403", "404", "409", "422", "503")),
				Map.entry("post " + WITHDRAWAL, Set.of("200", "400", "401", "403", "404", "409", "422")),
				Map.entry("post " + TRANSFER, Set.of("200", "400", "401", "403", "404", "409", "422")));
		Map<String, String> successSchemas = Map.ofEntries(
				Map.entry("get " + COLLECTION, "WishPage"),
				Map.entry("post " + COLLECTION, "WishMutationResult"),
				Map.entry("get " + ITEM, "Wish"),
				Map.entry("get " + REPRESENTATIVE, "Wish"),
				Map.entry("put " + REPRESENTATIVE, "Wish"),
				Map.entry("patch " + ITEM, "WishMutationResult"),
				Map.entry("delete " + ITEM, "WishMutationResult"),
				Map.entry("post " + COMPLETION, "WishMutationResult"),
				Map.entry("post " + ABANDONMENT, "WishMutationResult"),
				Map.entry("post " + DEPOSIT, "WishMutationResult"),
				Map.entry("post " + WITHDRAWAL, "WishMutationResult"),
				Map.entry("post " + TRANSFER, "WishTransferResult"));

		expectedStatuses.forEach((key, statuses) -> {
			String[] parts = key.split(" ", 2);
			if (MOVEMENT_PATHS.contains(parts[1])) return;
			Map<String, Object> operation = operation(document, parts[1], parts[0]);
			Map<String, Object> responses = object(operation.get("responses"));
			assertThat(responses.keySet()).as(key + " statuses").containsExactlyInAnyOrderElementsOf(statuses);
			statuses.stream().filter(status -> !status.startsWith("2")).forEach(status -> {
				Map<String, Object> response = object(responses.get(status));
				assertThat(responseSchemaRef(document, response)).as(key + " " + status + " schema")
						.isEqualTo("#/components/schemas/ErrorEnvelope");
				assertThat(resolve(document, response).get("description").toString()).as(key + " " + status + " description")
						.isNotBlank();
			});
			Map<String, Object> unauthorized = resolve(document, object(responses.get("401")));
			Map<String, Object> authenticateHeader = object(
					object(unauthorized.get("headers")).get("WWW-Authenticate"));
			assertThat(object(authenticateHeader.get("schema")))
					.containsEntry("const", "Bearer");
			String successStatus = key.equals("post " + COLLECTION) ? "201" : "200";
			Map<String, Object> success = resolve(document, object(responses.get(successStatus)));
			assertThat(responseSchemaRef(document, success))
					.isEqualTo("#/components/schemas/" + successSchemas.get(key));
		});

		expectedErrorCodes().forEach((key, byStatus) -> {
			String[] parts = key.split(" ", 2);
			if (MOVEMENT_PATHS.contains(parts[1])) return;
			Map<String, Object> responses = object(operation(document, parts[1], parts[0]).get("responses"));
			byStatus.forEach((status, codes) -> assertThat(
					resolve(document, object(responses.get(status))).get("description").toString())
						.as(key + " " + status + " error-code inventory")
						.contains(codes.toArray(String[]::new)));
		});

		assertResponse(document, COLLECTION, "get", "400",
				"MALFORMED_REQUEST", "account UUID", "cursor", "limit", "state", "duplicate");
		assertResponse(document, COLLECTION, "post", "400",
				"MALFORMED_REQUEST", "JSON", "unsupported field", "wrong type", "missing", "200",
				"IDEMPOTENCY_KEY_REQUIRED", "absent or blank");
		assertResponse(document, COLLECTION, "post", "422",
				"INVALID_AMOUNT", "JavaScript-safe", "INVALID_PURPOSE", "normalization", "length");
		assertResponse(document, COLLECTION, "post", "409",
				"BALANCE_MISMATCH_LOCKED", "open mismatch", "before a new Wish is persisted",
				"IDEMPOTENCY_KEY_REUSED", "fingerprint");
		assertThat(responseExamples(document, object(
				object(operation(document, COLLECTION, "post").get("responses")).get("409"))))
				.contains("balance-mismatch-locked");
		assertResponse(document, ITEM, "get", "404",
				"CARD_BALANCE_ACCOUNT_NOT_FOUND", "closed", "principal academy",
				"WISH_NOT_FOUND", "tombstoned", "outside the account");
		assertResponse(document, ITEM, "patch", "409",
				"VERSION_CONFLICT", "stale", "INVALID_STATE_TRANSITION", "current Wish state",
				"BALANCE_MISMATCH_LOCKED", "open mismatch");
		assertResponse(document, ITEM, "patch", "422",
				"INVALID_AMOUNT", "current-amount", "INVALID_PURPOSE", "character",
				"INVALID_VERSION", "negative");
		assertResponse(document, ITEM, "delete", "400",
				"MALFORMED_REQUEST", "If-Match", "200", "EXPECTED_VERSION_REQUIRED",
				"absent or blank", "IDEMPOTENCY_KEY_REQUIRED");
		assertResponse(document, ITEM, "delete", "409",
				"VERSION_CONFLICT", "If-Match", "IDEMPOTENCY_KEY_REUSED", "fingerprint");
		Map<String, Object> deleteConflict = object(
				object(operation(document, ITEM, "delete").get("responses")).get("409"));
		assertThat(resolve(document, deleteConflict).get("description").toString())
				.as("delete Wish 409 excludes unreachable state-transition errors")
				.doesNotContain("INVALID_STATE_TRANSITION");
		assertThat(responseExamples(document, deleteConflict))
				.as("delete Wish 409 examples exclude unreachable state-transition errors")
				.doesNotContain("invalidStateTransition");
		assertResponse(document, COMPLETION, "post", "409",
				"VERSION_CONFLICT", "INVALID_STATE_TRANSITION", "IDEMPOTENCY_KEY_REUSED");
		assertResponse(document, ABANDONMENT, "post", "409",
				"VERSION_CONFLICT", "already terminal", "IDEMPOTENCY_KEY_REUSED");
		for (String key : List.of("post " + COLLECTION, "delete " + ITEM,
				"post " + COMPLETION, "post " + ABANDONMENT)) {
			String[] parts = key.split(" ", 2);
			Map<String, Object> responses = object(operation(document, parts[1], parts[0]).get("responses"));
			String successStatus = key.equals("post " + COLLECTION) ? "201" : "200";
			Map<String, Object> success = resolve(document, object(responses.get(successStatus)));
			assertThat(object(success.get("headers"))).as(key + " replay header")
					.containsKey("Idempotency-Replayed");
		}

		Set<String> namedExamples = operationInventory(document).stream()
				.flatMap(key -> {
					String[] parts = key.split(" ", 2);
					return object(operation(document, parts[1], parts[0]).get("responses")).values().stream();
				})
				.map(WishOpenApiDocumentationTest::object)
				.flatMap(response -> responseExamples(document, response).stream())
				.collect(Collectors.toSet());
		Map<String, Object> canonical = canonicalDocument();
		Set<String> canonicalNamedExamples = operationInventory(canonical).stream()
				.flatMap(key -> {
					String[] parts = key.split(" ", 2);
					return object(operation(canonical, parts[1], parts[0]).get("responses")).values().stream();
				})
				.map(WishOpenApiDocumentationTest::object)
				.flatMap(response -> responseExamples(canonical, response).stream())
				.collect(Collectors.toSet());
		assertThat(namedExamples).isEqualTo(canonicalNamedExamples);
	}

	@Test
	void generatedWishExamplesRespectTheTerminalTimeStateMatrix() {
		Map<String, Map<String, Object>> activeExamples = Map.of(
				"createdWish", controllerWishExample("create", "createdWish"),
				"currentWish", controllerWishExample("get", "currentWish"),
				"editedWish", controllerWishExample("patch", "editedWish"));

		activeExamples.forEach((name, wish) -> {
			assertThat(wish.get("state")).as(name).isIn("IN_PROGRESS", "AMOUNT_REACHED");
			assertThat(wish).as(name)
					.containsEntry("completedAt", null)
					.containsEntry("closedAt", null)
					.containsEntry("actualDurationSeconds", null);
		});

		Map<String, Object> completed = controllerWishExample("complete", "completedWish");
		assertThat(completed).containsEntry("state", "COMPLETED");
		assertThat(completed.get("closedAt")).isEqualTo(completed.get("completedAt"));
		assertThat(completed.get("actualDurationSeconds")).isInstanceOf(Number.class);
		assertUtcInstant(completed.get("closedAt"), "completedWish.closedAt");

		Map<String, Map<String, Object>> abandonedExamples = Map.of(
				"abandonedWish", controllerWishExample("abandon", "abandonedWish"),
				"deletedWish", controllerWishExample("delete", "deletedWish"));
		abandonedExamples.forEach((name, wish) -> {
			assertThat(wish).as(name)
					.containsEntry("state", "ABANDONED")
					.containsEntry("completedAt", null)
					.containsEntry("actualDurationSeconds", null);
			assertUtcInstant(wish.get("closedAt"), name + ".closedAt");
		});
	}

	@Test
	void documentsEveryResponseComponentAndProperty() throws Exception {
		Map<String, Object> document = document();
		Map<String, List<String>> expectedProperties = Map.of(
				"WishPage", List.of("items", "nextCursor"),
				"Wish", List.of("id", "cardBalanceAccountId", "purpose", "targetAmount",
						"amount", "targetDate", "state", "visibility",
						"balanceAdjustmentInProgress", "createdAt", "updatedAt",
						"completedAt", "closedAt", "actualDurationSeconds", "version"),
				"WishMutationResult", List.of("wish", "eventId"),
				"WishTransferResult", List.of(
						"sourceWish", "destinationWish", "eventId", "occurredAt"),
				"ErrorEnvelope", List.of("error"),
				"ApiError", List.of("code", "message", "retryable", "traceId", "fieldErrors", "details"),
				"FieldError", List.of("field", "message"));

		expectedProperties.forEach((name, properties) -> {
			if (!(value(document, "components", "schemas") instanceof Map<?, ?> schemas)
					|| !schemas.containsKey(name)) return;
			Map<String, Object> schema = componentSchema(document, name);
			if (schema.containsKey("description")) {
				assertThat(schema.get("description").toString()).as(name + " description").isNotBlank();
			}
			assertThat(object(schema.get("properties")).keySet()).as(name + " properties")
					.containsExactlyInAnyOrderElementsOf(properties);
			properties.forEach(property -> assertThat(property(schema, property).get("description"))
					.as(name + "." + property + " description").isInstanceOfSatisfying(
							String.class, description -> assertThat(description).isNotBlank()));
		});

		for (String responseSchema : expectedProperties.keySet()) {
			Object raw = object(value(document, "components", "schemas")).get(responseSchema);
			if (raw instanceof Map<?, ?> schema && schema.containsKey("example")) {
				assertThat(schema.get("example")).as(responseSchema + " example").isNotNull();
			}
		}

		Map<String, Object> snapshot = componentSchema(document, "Wish");
		assertThat(property(snapshot, "purpose"))
				.containsEntry("$ref", "#/components/schemas/Purpose");
		assertThat(property(snapshot, "targetAmount"))
				.containsEntry("$ref", "#/components/schemas/KrwPositive");
		assertThat(property(snapshot, "amount"))
				.containsEntry("$ref", "#/components/schemas/KrwNonNegative");
		assertProperty(snapshot, "targetDate", "Optional calendar date", null, null,
				null, null, "date");
		assertThat(property(snapshot, "createdAt"))
				.containsEntry("$ref", "#/components/schemas/UtcInstant");
		assertThat(property(snapshot, "version"))
				.containsEntry("$ref", "#/components/schemas/WishVersion");
		assertThat(property(snapshot, "state"))
				.containsEntry("$ref", "#/components/schemas/WishState");
		assertThat(property(snapshot, "visibility"))
				.containsEntry("$ref", "#/components/schemas/WishVisibility");
	}

	@Test
	void documentsExactApprovedResponsePropertyDescriptions() throws Exception {
		Map<String, Object> document = document();
		Map<String, Object> canonical = canonicalDocument();
		Map<String, Map<String, String>> expected = Map.of(
				"Wish", Map.ofEntries(
						Map.entry("id", "Stable UUID of this Wish."),
						Map.entry("cardBalanceAccountId", "UUID of the owner Card Balance Account to which "
								+ "this Wish is permanently attached."),
						Map.entry("purpose", "NFC-normalized, boundary-space-free purpose text persisted "
								+ "for this Wish."),
						Map.entry("targetAmount", "Positive integer KRW goal for this Wish."),
						Map.entry("amount", "Non-negative integer KRW currently allocated to this Wish; "
								+ "it is distinct from actual card balance and never exceeds targetAmount."),
						Map.entry("targetDate", "Optional calendar date that may be in the past, present, "
								+ "or future."),
						Map.entry("state", "Lifecycle state: IN_PROGRESS below target, AMOUNT_REACHED at "
								+ "target before explicit completion, COMPLETED after completion, or ABANDONED "
								+ "after abandonment."),
						Map.entry("visibility", "Requested publication scope PRIVATE, FRIENDS, or ACADEMY; "
								+ "current relationship and blocking checks may further hide any Shared Card."),
						Map.entry("createdAt", "RFC 3339 UTC Z instant at which the Wish was created."),
						Map.entry("updatedAt", "RFC 3339 UTC Z instant of the most recent successful Wish "
								+ "content or lifecycle mutation."),
						Map.entry("completedAt", "RFC 3339 UTC Z instant of explicit completion for a "
								+ "COMPLETED Wish; null for every other state."),
						Map.entry("closedAt", "RFC 3339 UTC Z lifecycle closure instant. Equal to "
								+ "completedAt for COMPLETED, the internal persisted abandonment instant for "
								+ "ABANDONED, and null for active states. Independent of targetDate, updatedAt, "
								+ "and deletion time."),
						Map.entry("actualDurationSeconds", "For completed Wishes, the elapsed whole seconds "
								+ "from createdAt through completedAt; null otherwise."),
						Map.entry("version", "Non-negative optimistic concurrency version of this snapshot; "
								+ "successful state-changing mutations advance it and idempotent replay returns "
								+ "the original value.")),
				"WishPage", Map.of(
						"items", "Non-deleted owned Wishes in createdAt descending, id descending order.",
						"nextCursor", "Opaque cursor for the next Wish page; null when no further page exists."),
				"WishMutationResult", Map.of(
						"wish", "Authoritative Wish snapshot after the mutation, or the original snapshot "
								+ "returned by an identical idempotent replay.",
						"eventId", "UUID of the immutable ledger event created by the mutation; null when "
								+ "the mutation moves no funds and therefore creates no ledger event."),
				"WishTransferResult", Map.of(
						"sourceWish", "Authoritative source Wish snapshot after the atomic transfer.",
						"destinationWish", "Authoritative destination Wish snapshot after the atomic transfer.",
						"eventId", "UUID of the single immutable event containing both transfer effects.",
						"occurredAt", "RFC 3339 UTC Z instant shared by both transfer effects."),
				"FieldError", Map.of(
						"field", "Name of the invalid request field, parameter, or header associated with "
								+ "this validation failure.",
						"message", "Human-readable explanation of the field-specific failure."),
				"ErrorEnvelope", Map.of(
						"error", "Structured error payload shared by every declared non-success JSON response."),
				"ApiError", Map.of(
						"code", "Stable machine-readable ErrorCode; clients should branch on this value "
								+ "rather than message text.",
						"message", "Human-readable explanation of this occurrence; it is not the stable "
								+ "machine decision key.",
						"retryable", "True only for BALANCE_SYNC_FAILED; false for every defined client, "
								+ "authorization, not-found, validation, and state-conflict error.",
						"traceId", "Opaque server correlation identifier for diagnostics and support; it "
								+ "has no domain meaning.",
						"fieldErrors", "Field-specific validation failures; empty when the error is not "
								+ "attributable to individual request fields.",
						"details", "Extensible code-specific metadata object; empty when no details apply, "
								+ "and clients must ignore unrecognized keys."));

		expected.forEach((schemaName, properties) -> {
			if (!(value(document, "components", "schemas") instanceof Map<?, ?> schemas)
					|| !schemas.containsKey(schemaName)) return;
			Map<String, Object> schema = componentSchema(document, schemaName);
			properties.forEach((propertyName, description) -> assertThat(
					property(schema, propertyName).get("description"))
					.as(schemaName + "." + propertyName + " description")
					.isEqualTo(property(componentSchema(canonical, schemaName), propertyName)
							.get("description")));
		});
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> document() throws Exception {
		String json = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$");
	}

	private static Map<String, Object> canonicalDocument() throws Exception {
		try (InputStream input = Files.newInputStream(Path.of("api", "openapi.yaml"))) {
			return object(new Yaml().load(input));
		}
	}

	private static Map<String, Object> movementOperationProjection(
			Map<String, Object> document, String path) {
		Map<String, Object> pathItem = object(object(document.get("paths")).get(path));
		Map<String, Object> operation = object(pathItem.get("post"));
		Map<String, Object> projected = new LinkedHashMap<>();
		for (String key : List.of(
				"tags", "operationId", "summary", "description")) {
			if (operation.containsKey(key)) projected.put(key, operation.get(key));
		}

		List<Object> parameters = new ArrayList<>();
		parameters.addAll(optionalList(pathItem, "parameters"));
		parameters.addAll(optionalList(operation, "parameters"));
		projected.put("parameters", parameters.stream()
				.map(WishOpenApiDocumentationTest::parameterShape)
				.sorted(Comparator.comparing(Object::toString))
				.toList());

		Map<String, Object> requestBody = object(operation.get("requestBody"));
		Map<String, Object> media = object(object(requestBody.get("content"))
				.get("application/json"));
		projected.put("requestBody", Map.of(
				"required", requestBody.get("required"),
				"application/json", schemaShape(object(media.get("schema")))));

		Map<String, Object> responses = new TreeMap<>();
		object(operation.get("responses")).forEach((status, response) ->
				responses.put(status, responseShape(object(response))));
		projected.put("responses", responses);
		return projected;
	}

	private static Map<String, Object> parameterShape(Object raw) {
		Map<String, Object> parameter = object(raw);
		if (parameter.containsKey("$ref")) return Map.of("$ref", parameter.get("$ref"));
		Map<String, Object> shape = new LinkedHashMap<>();
		for (String key : List.of("name", "in", "required")) {
			if (parameter.containsKey(key)) shape.put(key, parameter.get(key));
		}
		shape.put("schema", schemaShape(object(parameter.get("schema"))));
		return shape;
	}

	private static Map<String, Object> responseShape(Map<String, Object> response) {
		if (response.containsKey("$ref")) return Map.of("$ref", response.get("$ref"));
		Map<String, Object> shape = new LinkedHashMap<>();
		if (response.containsKey("description")) {
			shape.put("description", response.get("description"));
		}
		if (response.containsKey("headers")) {
			Map<String, Object> headers = new TreeMap<>();
			object(response.get("headers")).forEach((name, header) ->
					headers.put(name, headerShape(object(header))));
			shape.put("headers", headers);
		}
		if (response.containsKey("content")) {
			Map<String, Object> media = object(object(response.get("content"))
					.get("application/json"));
			shape.put("application/json", schemaShape(object(media.get("schema"))));
		}
		return shape;
	}

	private static Map<String, Object> headerShape(Map<String, Object> header) {
		if (header.containsKey("$ref")) return Map.of("$ref", header.get("$ref"));
		Map<String, Object> shape = new LinkedHashMap<>();
		for (String key : List.of("required")) {
			if (header.containsKey(key)) shape.put(key, header.get(key));
		}
		if (header.get("schema") instanceof Map<?, ?> rawSchema) {
			shape.put("schema", schemaShape(object(rawSchema)));
		}
		return shape;
	}

	private static Map<String, Object> securitySchemeShape(Map<String, Object> securityScheme) {
		Map<String, Object> shape = new LinkedHashMap<>();
		for (String key : List.of("type", "scheme", "bearerFormat")) {
			if (securityScheme.containsKey(key)) shape.put(key, securityScheme.get(key));
		}
		return shape;
	}

	private static Map<String, Object> componentShape(
			Map<String, Object> document, String schemaName) {
		Map<String, Object> schema = componentSchema(document, schemaName);
		Map<String, Object> shape = new LinkedHashMap<>();
		for (String key : List.of("type", "additionalProperties")) {
			if (schema.containsKey(key)) shape.put(key, schema.get(key));
		}
		shape.put("required", optionalList(schema, "required").stream()
				.map(Object::toString).sorted().toList());
		Map<String, Object> properties = new TreeMap<>();
		object(schema.get("properties")).forEach((name, property) ->
				properties.put(name, schemaShape(object(property))));
		shape.put("properties", properties);
		return shape;
	}

	private static Map<String, Object> schemaShape(Map<String, Object> schema) {
		Map<String, Object> shape = new LinkedHashMap<>();
		if (schema.containsKey("$ref")) shape.put("$ref", schema.get("$ref"));
		List<String> types = new ArrayList<>();
		Object type = schema.get("type");
		if (type instanceof List<?> values) {
			types.addAll(values.stream().map(Object::toString).toList());
		} else if (type != null) {
			types.add(type.toString());
		}
		if (Boolean.TRUE.equals(schema.get("nullable")) && !types.contains("null")) {
			types.add("null");
		}
		if (!types.isEmpty()) shape.put("type", types.stream().sorted().toList());
		for (String key : List.of(
				"format", "minimum", "maximum", "minLength", "maxLength",
				"pattern", "enum", "const")) {
			if (schema.containsKey(key)) shape.put(key, schema.get(key));
		}
		return shape;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> optionalList(Map<String, Object> value, String key) {
		Object raw = value.get(key);
		return raw instanceof List<?> list ? (List<Object>) list : List.of();
	}

	private static Set<String> operationInventory(Map<String, Object> document) {
		Set<String> methods = Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
		return object(document.get("paths")).entrySet().stream()
				.flatMap(path -> object(path.getValue()).entrySet().stream()
						.filter(operation -> methods.contains(operation.getKey()))
						.filter(operation -> list(object(operation.getValue()), "tags")
								.contains("Wishes"))
						.map(operation -> operation.getKey() + " " + path.getKey()))
				.collect(Collectors.toSet());
	}

	private static Map<String, Object> operation(
			Map<String, Object> document, String path, String method) {
		return object(object(object(document.get("paths")).get(path)).get(method));
	}

	private static Map<String, Object> parameter(
			Map<String, Object> document, String path, String method, String name) {
		Map<String, Object> pathItem = object(object(document.get("paths")).get(path));
		List<Object> parameters = new ArrayList<>();
		parameters.addAll(optionalList(pathItem, "parameters"));
		parameters.addAll(optionalList(object(pathItem.get(method)), "parameters"));
		return parameters.stream()
				.map(WishOpenApiDocumentationTest::object)
				.map(parameter -> resolve(document, parameter))
				.filter(parameter -> name.equals(parameter.get("name")))
				.findFirst()
				.orElseThrow();
	}

	private static Map<String, Object> schema(Map<String, Object> parameter) {
		return object(parameter.get("schema"));
	}

	private static void assertParameter(
			Map<String, Object> document,
			String path,
			String method,
			String name,
			String location,
			boolean required,
			String format,
			Integer minLength,
			Integer maxLength) {
		Map<String, Object> parameter = parameter(document, path, method, name);
		assertThat(parameter).containsEntry("in", location).containsEntry("required", required);
		Map<String, Object> schema = schema(parameter);
		if (format != null) {
			if (schema.containsKey("$ref")) {
				assertThat(schema).containsEntry("$ref", "#/components/schemas/Uuid");
			} else {
				assertThat(schema).containsEntry("format", format);
			}
		}
		if (minLength != null) assertThat(schema).containsEntry("minLength", minLength);
		if (maxLength != null) assertThat(schema).containsEntry("maxLength", maxLength);
	}

	private static void assertRequestSchema(
			Map<String, Object> operation, String mediaType, String schemaName) {
		Map<String, Object> requestBody = object(operation.get("requestBody"));
		assertThat(requestBody).containsEntry("required", true);
		Map<String, Object> media = object(object(requestBody.get("content")).get(mediaType));
		assertThat(object(media.get("schema"))).containsEntry("$ref", "#/components/schemas/" + schemaName);
	}

	private static Map<String, Object> componentSchema(Map<String, Object> document, String name) {
		return object(value(document, "components", "schemas", name));
	}

	private static Map<String, Object> property(Map<String, Object> schema, String property) {
		return object(object(schema.get("properties")).get(property));
	}

	private static Object normalizeRequiredOrder(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> normalized = new LinkedHashMap<>();
			map.forEach((key, child) -> {
				Object normalizedChild = normalizeRequiredOrder(child);
				if ("required".equals(key) && normalizedChild instanceof List<?> required) {
					normalizedChild = required.stream().map(Object::toString).sorted().toList();
				}
				normalized.put(key.toString(), normalizedChild);
			});
			return normalized;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(WishOpenApiDocumentationTest::normalizeRequiredOrder).toList();
		}
		return value;
	}

	private static void assertProperty(
			Map<String, Object> schema,
			String name,
			String descriptionFragment,
			Integer minLength,
			Integer maxLength,
			Long minimum,
			Long maximum,
			String format) throws Exception {
		Map<String, Object> property = property(schema, name);
		property = resolve(canonicalDocument(), property);
		Object description = property.get("description");
		if (description != null) {
			assertThat(description.toString()).containsPattern(".*[가-힣].*");
		}
		if (minLength != null) {
			if (property.containsKey("minLength")) assertThat(property).containsEntry("minLength", minLength);
			else assertThat(property.get("description").toString()).contains(minLength.toString());
		}
		if (maxLength != null) {
			if (property.containsKey("maxLength")) assertThat(property).containsEntry("maxLength", maxLength);
			else assertThat(property.get("description").toString()).contains(maxLength.toString());
		}
		if (minimum != null) assertThat(((Number) property.get("minimum")).longValue()).isEqualTo(minimum);
		if (maximum != null) assertThat(((Number) property.get("maximum")).longValue()).isEqualTo(maximum);
		if (format != null) assertThat(property).containsEntry("format", format);
	}

	private static void assertResponse(
			Map<String, Object> document,
			String path,
			String method,
			String status,
			String... fragments) {
		Map<String, Object> response = resolve(document, object(object(operation(document, path, method).get("responses"))
				.get(status)));
		String description = response.get("description").toString();
		assertThat(description).containsPattern(".*[가-힣].*");
		for (String fragment : fragments) {
			if (fragment.matches("[A-Z][A-Z0-9]*_[A-Z0-9_]+")) assertThat(description).contains(fragment);
		}
	}

	private static Map<String, Map<String, List<String>>> expectedErrorCodes() {
		return Map.of(
				"get " + COLLECTION, Map.of(
						"400", List.of("MALFORMED_REQUEST"),
						"401", List.of("AUTH_REQUIRED"),
						"403", List.of("FORBIDDEN"),
						"404", List.of("CARD_BALANCE_ACCOUNT_NOT_FOUND")),
				"post " + COLLECTION, Map.of(
						"400", List.of("MALFORMED_REQUEST", "IDEMPOTENCY_KEY_REQUIRED"),
						"401", List.of("AUTH_REQUIRED"),
						"403", List.of("FORBIDDEN"),
						"404", List.of("CARD_BALANCE_ACCOUNT_NOT_FOUND"),
						"409", List.of("BALANCE_MISMATCH_LOCKED", "IDEMPOTENCY_KEY_REUSED"),
						"415", List.of("UNSUPPORTED_MEDIA_TYPE"),
						"422", List.of("INVALID_AMOUNT", "INVALID_PURPOSE")),
				"get " + ITEM, Map.of(
						"400", List.of("MALFORMED_REQUEST"),
						"401", List.of("AUTH_REQUIRED"),
						"403", List.of("FORBIDDEN"),
						"404", List.of("CARD_BALANCE_ACCOUNT_NOT_FOUND", "WISH_NOT_FOUND")),
				"patch " + ITEM, Map.of(
						"400", List.of("MALFORMED_REQUEST"),
						"401", List.of("AUTH_REQUIRED"),
						"403", List.of("FORBIDDEN"),
						"404", List.of("CARD_BALANCE_ACCOUNT_NOT_FOUND", "WISH_NOT_FOUND"),
						"409", List.of("VERSION_CONFLICT", "INVALID_STATE_TRANSITION",
								"BALANCE_MISMATCH_LOCKED"),
						"415", List.of("UNSUPPORTED_MEDIA_TYPE"),
						"422", List.of("INVALID_AMOUNT", "INVALID_PURPOSE", "INVALID_VERSION")),
				"delete " + ITEM, Map.of(
						"400", List.of("MALFORMED_REQUEST", "EXPECTED_VERSION_REQUIRED",
								"IDEMPOTENCY_KEY_REQUIRED"),
						"401", List.of("AUTH_REQUIRED"),
						"403", List.of("FORBIDDEN"),
						"404", List.of("CARD_BALANCE_ACCOUNT_NOT_FOUND", "WISH_NOT_FOUND"),
						"409", List.of("VERSION_CONFLICT", "IDEMPOTENCY_KEY_REUSED"),
						"422", List.of("INVALID_VERSION")),
				"post " + COMPLETION, terminalErrorCodes(),
				"post " + ABANDONMENT, terminalErrorCodes(),
				"post " + DEPOSIT, Map.of(
						"400", List.of("MALFORMED_REQUEST", "IDEMPOTENCY_KEY_REQUIRED"),
						"401", List.of("AUTH_REQUIRED"),
						"403", List.of("FORBIDDEN"),
						"404", List.of("CARD_BALANCE_ACCOUNT_NOT_FOUND", "WISH_NOT_FOUND"),
						"409", List.of("VERSION_CONFLICT", "INVALID_STATE_TRANSITION",
								"BALANCE_MISMATCH_LOCKED", "INSUFFICIENT_AVAILABLE_BALANCE",
								"TARGET_AMOUNT_EXCEEDED", "IDEMPOTENCY_KEY_REUSED"),
						"422", List.of("INVALID_AMOUNT", "INVALID_VERSION"),
						"503", List.of("BALANCE_SYNC_FAILED")),
				"post " + WITHDRAWAL, movementErrorCodes(false),
				"post " + TRANSFER, movementErrorCodes(true));
	}

	private static Map<String, List<String>> movementErrorCodes(boolean transfer) {
		return Map.of(
				"400", List.of("MALFORMED_REQUEST", "IDEMPOTENCY_KEY_REQUIRED"),
				"401", List.of("AUTH_REQUIRED"),
				"403", List.of("FORBIDDEN"),
				"404", List.of("CARD_BALANCE_ACCOUNT_NOT_FOUND", "WISH_NOT_FOUND"),
				"409", transfer
						? List.of("VERSION_CONFLICT", "INVALID_STATE_TRANSITION",
								"CROSS_ACCOUNT_TRANSFER_FORBIDDEN", "INSUFFICIENT_WISH_AMOUNT",
								"TARGET_AMOUNT_EXCEEDED", "BALANCE_MISMATCH_LOCKED",
								"IDEMPOTENCY_KEY_REUSED")
						: List.of("VERSION_CONFLICT", "INVALID_STATE_TRANSITION",
								"INSUFFICIENT_WISH_AMOUNT", "IDEMPOTENCY_KEY_REUSED"),
				"422", List.of("INVALID_AMOUNT", "INVALID_VERSION"));
	}

	private static Map<String, List<String>> terminalErrorCodes() {
		return Map.of(
				"400", List.of("MALFORMED_REQUEST", "IDEMPOTENCY_KEY_REQUIRED"),
				"401", List.of("AUTH_REQUIRED"),
				"403", List.of("FORBIDDEN"),
				"404", List.of("CARD_BALANCE_ACCOUNT_NOT_FOUND", "WISH_NOT_FOUND"),
				"409", List.of("VERSION_CONFLICT", "INVALID_STATE_TRANSITION",
						"IDEMPOTENCY_KEY_REUSED"),
				"415", List.of("UNSUPPORTED_MEDIA_TYPE"),
				"422", List.of("INVALID_VERSION"));
	}

	private static String responseSchemaRef(
			Map<String, Object> document, Map<String, Object> response) {
		response = resolve(document, response);
		Map<String, Object> media = object(object(response.get("content")).get("application/json"));
		return object(media.get("schema")).get("$ref").toString();
	}

	private static Set<String> responseExamples(
			Map<String, Object> document, Map<String, Object> response) {
		response = resolve(document, response);
		Object rawContent = response.get("content");
		if (!(rawContent instanceof Map<?, ?> content)) return Set.of();
		return content.values().stream()
				.map(WishOpenApiDocumentationTest::object)
				.map(media -> media.get("examples"))
				.filter(Map.class::isInstance)
				.flatMap(examples -> object(examples).keySet().stream())
				.collect(Collectors.toSet());
	}

	private static Map<String, Object> controllerWishExample(
			String methodName, String exampleName) {
		Method method = Arrays.stream(WishController.class.getDeclaredMethods())
				.filter(candidate -> candidate.getName().equals(methodName))
				.findFirst()
				.orElseThrow();
		ApiResponses responses = method.getAnnotation(ApiResponses.class);
		ExampleObject example = Arrays.stream(responses.value())
				.flatMap(response -> Arrays.stream(response.content()))
				.flatMap(content -> Arrays.stream(content.examples()))
				.filter(candidate -> candidate.name().equals(exampleName))
				.findFirst()
				.orElseThrow();
		Map<String, Object> value = object(new Yaml().load(example.value()));
		return value.get("wish") instanceof Map<?, ?> ? object(value.get("wish")) : value;
	}

	private static void assertUtcInstant(Object value, String description) {
		assertThat(value).as(description).isInstanceOf(String.class);
		String instant = value.toString();
		assertThat(instant).as(description).endsWith("Z");
		assertThat(Instant.parse(instant)).as(description).isNotNull();
	}

	private static Map<String, Object> resolve(
			Map<String, Object> document, Map<String, Object> value) {
		Object rawRef = value.get("$ref");
		if (!(rawRef instanceof String ref) || !ref.startsWith("#/")) return value;
		Object current = document;
		for (String encoded : ref.substring(2).split("/")) {
			current = object(current).get(encoded.replace("~1", "/").replace("~0", "~"));
		}
		return object(current);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Map<String, Object> value, String key) {
		return (List<Object>) value.get(key);
	}

	private static Object value(Map<String, Object> root, String... path) {
		Object value = root;
		for (String segment : path) value = object(value).get(segment);
		return value;
	}

	private static String text(Map<String, Object> root, String... path) {
		return value(root, path).toString();
	}

	private record OperationContract(
			String operationId, String summary, List<String> descriptionFragments) {
	}
}
