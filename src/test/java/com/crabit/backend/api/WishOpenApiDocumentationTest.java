package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
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

	@Autowired
	private MockMvc mockMvc;

	@Test
	void documentsMetadataSecurityAndEveryWishLifecycleOperation() throws Exception {
		Map<String, Object> document = document();

		assertThat(value(document, "info", "title")).isEqualTo("Crabit Wish API");
		assertThat(text(document, "info", "description"))
				.contains("seven Wish lifecycle operations")
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
				.contains("Create, query, edit, complete, abandon, and tombstone Wishes")
				.contains("Card Balance Account");

		Map<String, Object> seedBearer = object(value(document,
				"components", "securitySchemes", "SeedBearer"));
		assertThat(seedBearer)
				.containsEntry("type", "http")
				.containsEntry("scheme", "bearer")
				.containsEntry("bearerFormat", "Seed token");

		Map<String, OperationContract> expected = new LinkedHashMap<>();
		expected.put("get " + COLLECTION, new OperationContract(
				"listWishes", "List owned Wishes",
				List.of("owned, non-tombstoned", "createdAt descending", "opaque cursor", "state filtering")));
		expected.put("post " + COLLECTION, new OperationContract(
				"createWish", "Create a Wish",
				List.of("IN_PROGRESS", "PRIVATE", "zero allocated amount", "identical Idempotency-Key replay")));
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

		assertThat(operationInventory(document)).containsExactlyInAnyOrderElementsOf(expected.keySet());
		expected.forEach((key, contract) -> {
			String[] parts = key.split(" ", 2);
			Map<String, Object> operation = operation(document, parts[1], parts[0]);
			assertThat(operation)
					.containsEntry("operationId", contract.operationId())
					.containsEntry("summary", contract.summary());
			assertThat(operation.get("tags")).as(key + " tag").isEqualTo(List.of("Wishes"));
			assertThat(operation.get("security")).as(key + " security")
					.isEqualTo(List.of(Map.of("SeedBearer", List.of())));
			assertThat(operation.get("description").toString())
					.as(key + " lifecycle description")
					.contains(contract.descriptionFragments().toArray(String[]::new));
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
		for (String requestSchema : List.of(
				"CreateWishRequest", "PatchWishRequest", "VersionCommandRequest")) {
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
				"post " + ABANDONMENT, Set.of("200", "400", "401", "403", "404", "409", "415", "422"));
		Map<String, String> successSchemas = Map.of(
				"get " + COLLECTION, "WishPage",
				"post " + COLLECTION, "WishMutationResponse",
				"get " + ITEM, "WishSnapshot",
				"patch " + ITEM, "WishMutationResponse",
				"delete " + ITEM, "WishMutationResponse",
				"post " + COMPLETION, "WishMutationResponse",
				"post " + ABANDONMENT, "WishMutationResponse");

		expectedStatuses.forEach((key, statuses) -> {
			String[] parts = key.split(" ", 2);
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
				"VERSION_CONFLICT", "If-Match", "INVALID_STATE_TRANSITION", "tombstoned",
				"IDEMPOTENCY_KEY_REUSED", "fingerprint");
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
				"WishSnapshot", List.of("id", "cardBalanceAccountId", "purpose", "targetAmount",
						"amount", "targetDate", "state", "visibility", "createdAt", "updatedAt",
						"completedAt", "actualDurationSeconds", "version"),
				"WishMutationResponse", List.of("wish", "eventId"),
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

		Map<String, Object> snapshot = componentSchema(document, "WishSnapshot");
		assertProperty(snapshot, "purpose", "1..200 Unicode code points", 1, 200, null, null, null);
		assertProperty(snapshot, "targetAmount", "integer Korean won", null, null,
				1L, 9_007_199_254_740_991L, null);
		assertProperty(snapshot, "amount", "no greater than targetAmount", null, null, 0L, null, null);
		assertProperty(snapshot, "targetDate", "Nullable ISO target date", null, null, null, null, "date");
		assertProperty(snapshot, "createdAt", "creation timestamp", null, null, null, null, "date-time");
		assertProperty(snapshot, "version", "optimistic version", null, null, 0L, null, null);
		assertThat(property(snapshot, "state").get("enum"))
				.isEqualTo(List.of("IN_PROGRESS", "AMOUNT_REACHED", "COMPLETED", "ABANDONED"));
		assertThat(property(snapshot, "visibility").get("enum"))
				.isEqualTo(List.of("PRIVATE", "FRIENDS", "ACADEMY"));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> document() throws Exception {
		String json = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$");
	}

	private static Set<String> operationInventory(Map<String, Object> document) {
		Set<String> methods = Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
		return object(document.get("paths")).entrySet().stream()
				.flatMap(path -> object(path.getValue()).keySet().stream()
						.filter(methods::contains)
						.map(method -> method + " " + path.getKey()))
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
						"409", List.of("IDEMPOTENCY_KEY_REUSED"),
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
						"409", List.of("VERSION_CONFLICT", "INVALID_STATE_TRANSITION",
								"IDEMPOTENCY_KEY_REUSED"),
						"422", List.of("INVALID_VERSION")),
				"post " + COMPLETION, terminalErrorCodes(),
				"post " + ABANDONMENT, terminalErrorCodes());
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
