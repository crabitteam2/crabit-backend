package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
			assertThat(responseSchemaRef(object(object(operation.get("responses")).get("200"))))
					.isEqualTo("#/components/schemas/" + responseSchemas.get(path));
			assertThat(operation.get("description").toString().replaceAll("\\s+", " ")).contains(
					"occurredAt DESC then eventId DESC", "ordering version",
					"without a partial page", "strictly below", "equal timestamps",
					"Any valid limit", "Authorization and ownership are",
					"no cacheability guarantee");
			Map<String, Object> limit = schema(parameter(operation, "limit"));
			assertThat(limit).containsEntry("minimum", 1).containsEntry("maximum", 100)
					.containsEntry("default", 20);
			assertParameter(operation, "cursor", "query", false, null, null, null);
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
		assertThat(property(componentSchema(generated, "CardBalanceChange"), "balanceAdjustment"))
				.containsEntry("$ref", "#/components/schemas/BalanceAdjustmentEventReference");
	}

	@Test
	void generatedAccountDetailOperationMatchesTheApprovedCanonicalContract() throws Exception {
		Map<String, Object> generated = document();
		Map<String, Object> canonical = canonicalDocument();
		Map<String, Object> operation = operation(generated, ACCOUNT_DETAIL, "get");

		assertThat(operation)
				.containsEntry("operationId", "getCardBalanceAccount")
				.containsEntry("summary", "Get an owned Card Balance Account")
				.containsEntry("tags", List.of("Card Balance Accounts"))
				.containsEntry("security", List.of(Map.of("SyntheticBearer", List.of())))
				.doesNotContainKey("requestBody");
		assertThat(operation.get("description").toString()).contains(
				"authenticated student's active account", "current persisted projection",
				"random identifier, closed account, ownership mismatch, and academy mismatch",
				"same not-found response", "no external balance lookup",
				"mutates no persistent state", "UNKNOWN amounts remain null",
				"later failed attempt retains the latest successful",
				"lastRefreshStatus FAILED");

		assertThat(list(operation, "parameters")).hasSize(1);
		assertParameter(operation, "cardBalanceAccountId", "path", true, "uuid", null, null);
		Map<String, Object> responses = object(operation.get("responses"));
		assertThat(responses.keySet()).containsExactlyInAnyOrder("200", "401", "403", "404");
		assertThat(responseSchemaRef(object(responses.get("200"))))
				.isEqualTo("#/components/schemas/CardBalanceAccount");
		for (String status : List.of("401", "403", "404")) {
			assertThat(responseSchemaRef(object(responses.get(status))))
					.as("detail %s schema", status)
					.isEqualTo("#/components/schemas/ErrorEnvelope");
		}
		assertThat(object(object(object(responses.get("401")).get("headers"))
				.get("WWW-Authenticate"))).containsEntry("example", "Bearer");
		assertThat(object(responses.get("404")).get("description").toString())
				.contains("CARD_BALANCE_ACCOUNT_NOT_FOUND", "absent", "closed", "non-owned",
						"cross-academy", "hidden");

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
		assertThat(object(object(generatedExamples.get("failed-refresh-known")).get("value")))
				.isEqualTo(object(object(canonicalExamples.get("FailedRefreshKnownBalance")).get("value")));
		assertThat(object(object(generatedExamples.get("adjustment-open-known")).get("value")))
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
				.contains("OPEN", "response read time", "RESOLVED-only history", "later failed lookup");
		assertThat(list(wish, "required")).contains("balanceAdjustmentInProgress");
		assertThat(property(wish, "balanceAdjustmentInProgress"))
				.containsEntry("type", "boolean");
		assertThat(property(wish, "balanceAdjustmentInProgress").get("description").toString())
				.contains("committed post-mutation state", "does not advance Wish version or updatedAt",
						"never shortage amount");

		Map<String, Object> create = operation(canonical, COLLECTION, "post");
		assertThat(create.get("description").toString()).contains(
				"replayed before evaluating the current mismatch guard",
				"BALANCE_MISMATCH_LOCKED", "before a new Wish is persisted");
		assertThat(object(object(create.get("responses")).get("409")))
				.containsEntry("$ref", "#/components/responses/CreateConflict");
		assertThat(list(object(value(canonical, "components", "responses", "CreateConflict")),
				"x-error-codes"))
				.containsExactly("BALANCE_MISMATCH_LOCKED", "IDEMPOTENCY_KEY_REUSED");

		assertThat(operation(canonical, ITEM, "patch").get("description").toString()).contains(
				"rejects every requested patch field", "every visibility change",
				"widening", "narrowing", "PRIVATE");
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
				.containsEntry("summary", "known-balance-adjustment-open")
				.containsEntry("x-schema-ref", "#/components/schemas/KnownCardBalanceAccount");
		assertThat(object(object(examples.get("KnownBalanceAdjustmentOpen")).get("value")))
				.containsEntry("balanceAdjustmentInProgress", true);
		assertThat(object(examples.get("WishBalanceAdjustmentOpen")))
				.containsEntry("summary", "wish-balance-adjustment-open")
				.containsEntry("x-schema-ref", "#/components/schemas/Wish");
		assertThat(object(object(examples.get("WishBalanceAdjustmentOpen")).get("value")))
				.containsEntry("balanceAdjustmentInProgress", true)
				.doesNotContainKeys("unresolvedShortage", "adjustmentCaseId", "observationId");
	}

	@Test
	void documentsMetadataSecurityAndEveryWishLifecycleOperation() throws Exception {
		Map<String, Object> document = document();

		assertThat(value(document, "info", "title")).isEqualTo("Crabit Wish API");
		assertThat(text(document, "info", "description"))
				.contains("eight Wish lifecycle and immutable-history operations")
				.contains("integer Korean won")
				.contains("optimistic versions")
				.contains("resource-specific 404")
				.contains("distinct from api/openapi.yaml");

		Map<String, Object> wishesTag = list(document, "tags").stream()
				.map(WishOpenApiDocumentationTest::object)
				.filter(tag -> "Wishes".equals(tag.get("name")))
				.findFirst()
				.orElseThrow();
		assertThat(wishesTag.get("description").toString())
				.contains("Create, query, edit, complete, abandon, tombstone, and inspect immutable history for Wishes")
				.contains("Card Balance Account");

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
					.containsEntry("summary", contract.summary());
			assertThat(operation.get("tags")).as(key + " tag").isEqualTo(List.of("Wishes"));
			assertThat(operation.get("security")).as(key + " security")
					.isEqualTo(List.of(Map.of("SyntheticBearer", List.of())));
			if (!contract.descriptionFragments().isEmpty()) {
				assertThat(operation.get("description").toString())
						.as(key + " lifecycle description")
						.contains(contract.descriptionFragments().toArray(String[]::new));
			}
		});
	}

	@Test
	void documentsExactParameterAndRequestBodyConstraints() throws Exception {
		Map<String, Object> document = document();
		Map<String, Object> list = operation(document, COLLECTION, "get");
		assertParameter(list, "cardBalanceAccountId", "path", true, "uuid", null, null);
		assertThat(parameter(list, "cursor").get("description").toString())
				.contains("opaque URL-safe cursor", "malformed");
		assertThat(schema(parameter(list, "cursor"))).containsEntry("type", "string");
		Map<String, Object> limit = schema(parameter(list, "limit"));
		assertThat(limit).containsEntry("minimum", 1).containsEntry("maximum", 100)
				.containsEntry("default", 20);
		Map<String, Object> state = schema(parameter(list, "state"));
		assertThat(state).containsEntry("type", "array").containsEntry("uniqueItems", true);
		assertThat(object(state.get("items")).get("enum"))
				.isEqualTo(List.of("IN_PROGRESS", "AMOUNT_REACHED", "COMPLETED", "ABANDONED"));
		for (String key : operationInventory(document)) {
			String[] parts = key.split(" ", 2);
			if (MOVEMENT_PATHS.contains(parts[1])) continue;
			Map<String, Object> operation = operation(document, parts[1], parts[0]);
			assertParameter(operation, "cardBalanceAccountId", "path", true, "uuid", null, null);
			if (parts[1].contains("{wishId}")) {
				assertParameter(operation, "wishId", "path", true, "uuid", null, null);
			}
		}

		Map<String, Object> create = operation(document, COLLECTION, "post");
		assertParameter(create, "Idempotency-Key", "header", true, null, 1, 200);
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
		assertRequestSchema(patch, "application/merge-patch+json", "PatchWishRequest");
		Map<String, Object> patchSchema = componentSchema(document, "PatchWishRequest");
		assertThat(patchSchema.get("additionalProperties")).isEqualTo(false);
		assertThat(patchSchema.get("required")).isEqualTo(List.of("expectedVersion"));
		assertThat(patchSchema.get("description").toString())
				.contains("at least one of purpose, targetAmount, targetDate, or visibility")
				.contains("Omission preserves")
				.contains("targetDate null clears")
				.contains("Unknown fields are rejected");
		assertProperty(patchSchema, "expectedVersion", "non-negative", null, null, 0L, null, null);
		assertProperty(patchSchema, "targetAmount", "currently allocated amount", null, null,
				1L, 9_007_199_254_740_991L, null);
		assertProperty(patchSchema, "targetDate", "null clears", null, null, null, null, "date");
		assertThat(property(patchSchema, "visibility").get("enum"))
				.isEqualTo(List.of("PRIVATE", "FRIENDS", "ACADEMY"));

		Map<String, Object> delete = operation(document, ITEM, "delete");
		assertParameter(delete, "If-Match", "header", true, null, null, null);
		assertThat(schema(parameter(delete, "If-Match"))).containsEntry("minimum", 0);
		assertThat(parameter(delete, "If-Match").get("description").toString())
				.contains("plain non-negative integer", "quoted entity-tag syntax is not accepted");
		assertParameter(delete, "Idempotency-Key", "header", true, null, 1, 200);

		for (String path : List.of(COMPLETION, ABANDONMENT)) {
			Map<String, Object> operation = operation(document, path, "post");
			assertParameter(operation, "Idempotency-Key", "header", true, null, 1, 200);
			assertRequestSchema(operation, "application/json", "VersionCommandRequest");
		}
		Map<String, Object> version = componentSchema(document, "VersionCommandRequest");
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
		for (String requestSchema : List.of(
				"CreateWishRequest", "PatchWishRequest", "VersionCommandRequest",
				"WishAmountCommand", "WishTransferRequest")) {
			assertThat(componentSchema(document, requestSchema)).containsKey("example");
		}
	}

	@Test
	void documentsExactResponsesErrorCodesHeadersAndNamedExamples() throws Exception {
		Map<String, Object> document = document();
		Map<String, Set<String>> expectedStatuses = Map.of(
				"get " + COLLECTION, Set.of("200", "400", "401", "403", "404"),
				"post " + COLLECTION, Set.of("201", "400", "401", "403", "404", "409", "415", "422"),
				"get " + ITEM, Set.of("200", "400", "401", "403", "404"),
				"patch " + ITEM, Set.of("200", "400", "401", "403", "404", "409", "415", "422"),
				"delete " + ITEM, Set.of("200", "400", "401", "403", "404", "409", "422"),
				"post " + COMPLETION, Set.of("200", "400", "401", "403", "404", "409", "415", "422"),
				"post " + ABANDONMENT, Set.of("200", "400", "401", "403", "404", "409", "415", "422"),
				"post " + DEPOSIT, Set.of("200", "400", "401", "403", "404", "409", "422", "503"),
				"post " + WITHDRAWAL, Set.of("200", "400", "401", "403", "404", "409", "422"),
				"post " + TRANSFER, Set.of("200", "400", "401", "403", "404", "409", "422"));
		Map<String, String> successSchemas = Map.of(
				"get " + COLLECTION, "WishPage",
				"post " + COLLECTION, "WishMutationResult",
				"get " + ITEM, "Wish",
				"patch " + ITEM, "WishMutationResult",
				"delete " + ITEM, "WishMutationResult",
				"post " + COMPLETION, "WishMutationResult",
				"post " + ABANDONMENT, "WishMutationResult",
				"post " + DEPOSIT, "WishMutationResult",
				"post " + WITHDRAWAL, "WishMutationResult",
				"post " + TRANSFER, "WishTransferResult");

		expectedStatuses.forEach((key, statuses) -> {
			String[] parts = key.split(" ", 2);
			if (MOVEMENT_PATHS.contains(parts[1])) return;
			Map<String, Object> operation = operation(document, parts[1], parts[0]);
			Map<String, Object> responses = object(operation.get("responses"));
			assertThat(responses.keySet()).as(key + " statuses").containsExactlyInAnyOrderElementsOf(statuses);
			statuses.stream().filter(status -> !status.startsWith("2")).forEach(status -> {
				Map<String, Object> response = object(responses.get(status));
				assertThat(responseSchemaRef(response)).as(key + " " + status + " schema")
						.isEqualTo("#/components/schemas/ErrorEnvelope");
				assertThat(response.get("description").toString()).as(key + " " + status + " description")
						.isNotBlank();
			});
			Map<String, Object> unauthorized = object(responses.get("401"));
			assertThat(object(object(unauthorized.get("headers")).get("WWW-Authenticate")))
					.containsEntry("example", "Bearer");
			String successStatus = key.equals("post " + COLLECTION) ? "201" : "200";
			Map<String, Object> success = object(responses.get(successStatus));
			assertThat(responseSchemaRef(success))
					.isEqualTo("#/components/schemas/" + successSchemas.get(key));
			assertThat(responseExamples(success)).as(key + " success examples").isNotEmpty();
		});

		expectedErrorCodes().forEach((key, byStatus) -> {
			String[] parts = key.split(" ", 2);
			if (MOVEMENT_PATHS.contains(parts[1])) return;
			Map<String, Object> responses = object(operation(document, parts[1], parts[0]).get("responses"));
			byStatus.forEach((status, codes) -> assertThat(
					object(responses.get(status)).get("description").toString())
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
		assertThat(responseExamples(object(
				object(operation(document, COLLECTION, "post").get("responses")).get("409"))))
				.contains("balanceMismatchLocked", "idempotencyKeyReused");
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
		assertThat(deleteConflict.get("description").toString())
				.as("delete Wish 409 excludes unreachable state-transition errors")
				.doesNotContain("INVALID_STATE_TRANSITION");
		assertThat(responseExamples(deleteConflict))
				.as("delete Wish 409 examples exclude unreachable state-transition errors")
				.doesNotContain("invalidStateTransition");
		assertResponse(document, COMPLETION, "post", "409",
				"VERSION_CONFLICT", "AMOUNT_REACHED", "already terminal", "IDEMPOTENCY_KEY_REUSED");
		assertResponse(document, ABANDONMENT, "post", "409",
				"VERSION_CONFLICT", "already terminal", "IDEMPOTENCY_KEY_REUSED");
		for (String key : List.of("post " + COLLECTION, "patch " + ITEM, "delete " + ITEM,
				"post " + COMPLETION, "post " + ABANDONMENT)) {
			String[] parts = key.split(" ", 2);
			Map<String, Object> responses = object(operation(document, parts[1], parts[0]).get("responses"));
			String successStatus = key.equals("post " + COLLECTION) ? "201" : "200";
			Map<String, Object> success = object(responses.get(successStatus));
			assertThat(object(success.get("headers"))).as(key + " replay header")
					.containsKey("Idempotency-Replayed");
		}

		Set<String> namedExamples = operationInventory(document).stream()
				.flatMap(key -> {
					String[] parts = key.split(" ", 2);
					return object(operation(document, parts[1], parts[0]).get("responses")).values().stream();
				})
				.map(WishOpenApiDocumentationTest::object)
				.flatMap(response -> responseExamples(response).stream())
				.collect(Collectors.toSet());
		assertThat(namedExamples).contains(
				"malformedRequest", "idempotencyKeyRequired", "expectedVersionRequired",
				"authRequired", "forbidden", "accountNotFound", "wishNotFound", "versionConflict",
				"invalidStateTransition", "balanceMismatchLocked", "idempotencyKeyReused",
				"unsupportedMediaType", "invalidAmount", "invalidPurpose", "invalidVersion");
	}

	@Test
	void documentsEveryResponseComponentAndProperty() throws Exception {
		Map<String, Object> document = document();
		Map<String, List<String>> expectedProperties = Map.of(
				"WishPage", List.of("items", "nextCursor"),
				"Wish", List.of("id", "cardBalanceAccountId", "purpose", "targetAmount",
						"amount", "targetDate", "state", "visibility",
						"balanceAdjustmentInProgress", "createdAt", "updatedAt",
						"completedAt", "actualDurationSeconds", "version"),
				"WishMutationResult", List.of("wish", "eventId"),
				"WishTransferResult", List.of(
						"sourceWish", "destinationWish", "eventId", "occurredAt"),
				"ErrorEnvelope", List.of("error"),
				"ApiError", List.of("code", "message", "retryable", "traceId", "fieldErrors", "details"),
				"FieldError", List.of("field", "message"));

		expectedProperties.forEach((name, properties) -> {
			Map<String, Object> schema = componentSchema(document, name);
			assertThat(schema.get("description").toString()).as(name + " description").isNotBlank();
			assertThat(object(schema.get("properties")).keySet()).as(name + " properties")
					.containsExactlyInAnyOrderElementsOf(properties);
			properties.forEach(property -> assertThat(property(schema, property).get("description"))
					.as(name + "." + property + " description").isInstanceOfSatisfying(
							String.class, description -> assertThat(description).isNotBlank()));
		});

		for (String responseSchema : expectedProperties.keySet()) {
			assertThat(componentSchema(document, responseSchema))
					.as(responseSchema + " example").containsKey("example");
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
			Map<String, Object> schema = componentSchema(document, schemaName);
			properties.forEach((propertyName, description) -> assertThat(
					property(schema, propertyName).get("description"))
					.as(schemaName + "." + propertyName + " description")
					.isEqualTo(description));
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

	private static Map<String, Object> parameter(Map<String, Object> operation, String name) {
		return list(operation, "parameters").stream()
				.map(WishOpenApiDocumentationTest::object)
				.filter(parameter -> name.equals(parameter.get("name")))
				.findFirst()
				.orElseThrow();
	}

	private static Map<String, Object> schema(Map<String, Object> parameter) {
		return object(parameter.get("schema"));
	}

	private static void assertParameter(
			Map<String, Object> operation,
			String name,
			String location,
			boolean required,
			String format,
			Integer minLength,
			Integer maxLength) {
		Map<String, Object> parameter = parameter(operation, name);
		assertThat(parameter).containsEntry("in", location).containsEntry("required", required);
		Map<String, Object> schema = schema(parameter);
		if (format != null) assertThat(schema).containsEntry("format", format);
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

	private static void assertProperty(
			Map<String, Object> schema,
			String name,
			String descriptionFragment,
			Integer minLength,
			Integer maxLength,
			Long minimum,
			Long maximum,
			String format) {
		Map<String, Object> property = property(schema, name);
		assertThat(property.get("description").toString()).contains(descriptionFragment);
		if (minLength != null) assertThat(property).containsEntry("minLength", minLength);
		if (maxLength != null) assertThat(property).containsEntry("maxLength", maxLength);
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
		Map<String, Object> response = object(object(operation(document, path, method).get("responses"))
				.get(status));
		assertThat(response.get("description").toString()).contains(fragments);
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

	private static String responseSchemaRef(Map<String, Object> response) {
		Map<String, Object> media = object(object(response.get("content")).get("application/json"));
		return object(media.get("schema")).get("$ref").toString();
	}

	private static Set<String> responseExamples(Map<String, Object> response) {
		Object rawContent = response.get("content");
		if (!(rawContent instanceof Map<?, ?> content)) return Set.of();
		return content.values().stream()
				.map(WishOpenApiDocumentationTest::object)
				.map(media -> media.get("examples"))
				.filter(Map.class::isInstance)
				.flatMap(examples -> object(examples).keySet().stream())
				.collect(Collectors.toSet());
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
