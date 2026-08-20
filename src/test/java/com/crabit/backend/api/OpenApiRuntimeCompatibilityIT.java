package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.e2e.CardBalanceScenarioController;
import com.jayway.jsonpath.JsonPath;
import io.swagger.v3.oas.annotations.Hidden;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.yaml.snakeyaml.Yaml;

class OpenApiRuntimeCompatibilityIT extends WishApiIntegrationSupport {

	private static final Set<String> HTTP_METHODS = Set.of(
			"get", "post", "put", "patch", "delete", "options", "head", "trace");

	@Test
	void canonicalGeneratedAndPostgresBackedRuntimeStayCompatible() throws Exception {
		Map<String, Object> canonical;
		try (InputStream input = Files.newInputStream(Path.of("api", "openapi.yaml"))) {
			canonical = map(new Yaml().load(input));
		}
		String generatedBody = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		Map<String, Object> generated = JsonPath.read(generatedBody, "$");

		Map<String, Map<String, Object>> canonicalOperations = operations(canonical);
		Map<String, Map<String, Object>> generatedOperations = new LinkedHashMap<>();
		operations(generated).forEach((key, value) -> {
			if (!key.contains(" /e2e/")) generatedOperations.put(key, value);
		});
		assertThat(generatedOperations.keySet())
				.containsExactlyInAnyOrderElementsOf(canonicalOperations.keySet());

		canonicalOperations.forEach((key, expected) -> {
			Map<String, Object> actual = generatedOperations.get(key);
			assertThat(actual.get("operationId")).as(key + " operationId")
					.isEqualTo(expected.get("operationId"));
			assertThat(map(actual.get("responses")).keySet()).as(key + " statuses")
					.containsExactlyInAnyOrderElementsOf(map(expected.get("responses")).keySet());
			assertThat(list(actual.get("security"))).as(key + " security")
					.containsExactlyElementsOf(list(expected.get("security")));
		});

		for (String schema : List.of(
				"Wish", "ErrorEnvelope", "ProgressSharedCard", "CompletionSharedCard")) {
			assertThat(schemaProperties(generated, schema)).as(schema + " runtime schema")
					.containsExactlyInAnyOrderElementsOf(schemaProperties(canonical, schema));
		}

		String wishBody = asOwner(get(WISHES_PATH + "/" + LAPTOP_WISH_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(LAPTOP_WISH_ID.toString()))
				.andReturn().getResponse().getContentAsString();
		assertThat(OpenApiRuntimeCompatibilityIT.<String, Object>map(JsonPath.read(wishBody, "$"))
				.keySet()).containsExactlyInAnyOrderElementsOf(schemaProperties(canonical, "Wish"));

		String unauthorizedBody = mockMvc.perform(get(WISHES_PATH + "/" + LAPTOP_WISH_ID))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andReturn().getResponse().getContentAsString();
		String actualErrorCode = JsonPath.read(unauthorizedBody, "$.error.code");
		assertThat(errorCodes(canonical, response(canonical, "get",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}", "401")))
				.as("GET Wish 401 canonical error codes")
				.contains(actualErrorCode);
	}

	@Test
	void generatedPublicDocumentExcludesIsolatedE2eControls() throws Exception {
		assumeTrue(CardBalanceScenarioController.class.isAnnotationPresent(Hidden.class),
				"Public-document exclusion is enforced after @Hidden materialization.");
		String generatedBody = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		Map<String, Object> generated = JsonPath.read(generatedBody, "$");
		assertThat(OpenApiRuntimeCompatibilityIT.<String, Object>map(generated.get("paths")).keySet())
				.noneMatch(path -> path.startsWith("/e2e"));
	}

	private static Map<String, Map<String, Object>> operations(Map<String, Object> document) {
		Map<String, Map<String, Object>> result = new LinkedHashMap<>();
		OpenApiRuntimeCompatibilityIT.<String, Object>map(document.get("paths"))
				.forEach((path, pathValue) ->
				OpenApiRuntimeCompatibilityIT.<String, Object>map(pathValue)
						.forEach((method, operation) -> {
					if (HTTP_METHODS.contains(method)) {
						result.put(method.toUpperCase() + " " + path, map(operation));
					}
				}));
		return result;
	}

	private static Set<String> schemaProperties(Map<String, Object> document, String name) {
		Map<String, Object> components = map(document.get("components"));
		Map<String, Object> schemas = map(components.get("schemas"));
		Map<String, Object> schema = map(schemas.get(name));
		return schema.containsKey("properties")
				? OpenApiRuntimeCompatibilityIT.<String, Object>map(schema.get("properties")).keySet()
				: Set.of();
	}

	private static List<Object> errorCodes(Map<String, Object> document, Object responseValue) {
		Map<String, Object> response = resolve(document, map(responseValue));
		Object codes = response.get("x-error-codes");
		return codes == null ? List.of() : list(codes);
	}

	private static Object response(
			Map<String, Object> document, String method, String path, String status) {
		Map<String, Object> pathItem = map(map(document.get("paths")).get(path));
		Map<String, Object> operation = map(pathItem.get(method));
		return map(operation.get("responses")).get(status);
	}

	private static Map<String, Object> resolve(
			Map<String, Object> document, Map<String, Object> value) {
		Object reference = value.get("$ref");
		if (!(reference instanceof String path) || !path.startsWith("#/")) return value;
		Object current = document;
		for (String segment : path.substring(2).split("/")) current = map(current).get(segment);
		return map(current);
	}

	@SuppressWarnings("unchecked")
	private static <K, V> Map<K, V> map(Object value) {
		return (Map<K, V>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return (List<Object>) value;
	}
}
