package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:shared-card-openapi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=none",
	"spring.flyway.enabled=false",
	"spring.main.banner-mode=off",
	"logging.level.root=warn",
	"crabit.documentation.enabled=true"
})
@AutoConfigureMockMvc
class SharedCardOpenApiDocumentationTest {

	private static final String COLLECTION = "/v1/academies/{academyId}/shared-cards";
	private static final String ITEM = COLLECTION + "/{cardId}";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void documentsBothCurrentRelationshipAwareOperationsAndTheirExactStatusInventories()
			throws Exception {
		Map<String, Object> document = document();
		Map<String, Object> list = operation(document, COLLECTION, "get");
		Map<String, Object> detail = operation(document, ITEM, "get");

		assertThat(list)
					.containsEntry("operationId", "listAcademySharedCards")
					.containsEntry("summary", "학원에서 현재 볼 수 있는 공유 카드 목록 조회");
		assertThat(list.get("description").toString()).contains(
				"학원 소속", "친구 관계", "양방향 차단",
				"contentUpdatedAt DESC", "sharedCardId DESC");
		assertThat(detail)
					.containsEntry("operationId", "getAcademySharedCard")
					.containsEntry("summary", "현재 볼 수 있는 공유 카드 조회");
		assertThat(detail.get("description").toString()).contains(
				"소유자", "공개 범위 조건 위반", "숨깁니다");
		for (Map<String, Object> operation : List.of(list, detail)) {
			assertThat(operation.get("tags")).isEqualTo(List.of("Shared Cards"));
			assertThat(operation.get("security")).isEqualTo(List.of(Map.of("SyntheticBearer", List.of())));
		}
		assertThat(object(list.get("responses")).keySet())
				.containsExactlyInAnyOrder("200", "400", "401", "403", "404", "503");
		assertThat(object(detail.get("responses")).keySet())
				.containsExactlyInAnyOrder("200", "401", "403", "404", "503");
		String hiddenDescription = resolve(document, object(object(detail.get("responses")).get("404")))
				.get("description").toString();
		assertThat(hiddenDescription).contains("ACADEMY_NOT_FOUND", "SHARED_CARD_NOT_FOUND");
	}

	@Test
	void documentsOpaqueKeysetParametersClosedVariantsAndPrivacyExclusions() throws Exception {
		Map<String, Object> document = document();
		Map<String, Object> list = operation(document, COLLECTION, "get");
		Map<String, Object> academyId = parameter(document, COLLECTION, "get", "academyId");
		Map<String, Object> cursor = parameter(document, COLLECTION, "get", "cursor");
		Map<String, Object> limit = parameter(document, COLLECTION, "get", "limit");

		assertThat(academyId).containsEntry("in", "path").containsEntry("required", true);
		assertThat(object(academyId.get("schema")))
				.containsEntry("$ref", "#/components/schemas/Uuid");
		assertThat(cursor.get("description").toString()).contains("불투명", "고정 정렬 순서");
		assertThat(object(limit.get("schema")))
				.containsEntry("minimum", 1)
				.containsEntry("maximum", 100)
				.containsEntry("default", 20);

		Map<String, Object> shared = schema(document, "SharedCard");
		assertThat(list(shared, "oneOf")).extracting(value -> object(value).get("$ref"))
				.containsExactly(
						"#/components/schemas/ProgressSharedCard",
						"#/components/schemas/CompletionSharedCard");
		assertThat(object(shared.get("discriminator")))
				.containsEntry("propertyName", "kind");

		Map<String, Object> progress = schema(document, "ProgressSharedCard");
		Map<String, Object> completion = schema(document, "CompletionSharedCard");
		Set<String> progressProperties = object(progress.get("properties")).keySet();
		Set<String> completionProperties = object(completion.get("properties")).keySet();
		assertThat(progressProperties).containsExactlyInAnyOrder(
				"sharedCardId", "kind", "ownerNickname", "purpose", "targetAmount",
				"progressPercent", "balanceAdjustmentInProgress", "photo", "contentUpdatedAt");
		assertThat(completionProperties).containsExactlyInAnyOrder(
				"sharedCardId", "kind", "ownerNickname", "purpose", "targetAmount",
				"progressPercent", "targetDate", "createdAt", "completedAt",
				"actualDurationSeconds", "photo", "contentUpdatedAt");
		assertThat(completionProperties).doesNotContain("balanceAdjustmentInProgress");
		for (String forbidden : List.of(
				"wishId", "wishAmount", "amount", "accountId", "cardBalanceAccountId",
				"studentId", "ownerId", "realName", "physicalCardNumber")) {
			assertThat(progressProperties).doesNotContain(forbidden);
			assertThat(completionProperties).doesNotContain(forbidden);
		}
		assertThat(list(progress, "required"))
				.containsExactlyInAnyOrderElementsOf(progressProperties);
		assertThat(list(completion, "required"))
				.containsExactlyInAnyOrderElementsOf(completionProperties);
		assertThat(progress).containsEntry("additionalProperties", false);
		assertThat(completion).containsEntry("additionalProperties", false);
		String adjustmentDescription = object(
				object(progress.get("properties")).get("balanceAdjustmentInProgress"))
				.get("description").toString();
		assertThat(adjustmentDescription).contains("OPEN", "소유자의 카드 잔액 계정");
	}

	@Test
	void documentsCanonicalFieldConstraintsAndNullableCursorShape() throws Exception {
		Map<String, Object> document = document();
		Map<String, Object> progressProperties = object(
				schema(document, "ProgressSharedCard").get("properties"));
		Map<String, Object> completionProperties = object(
				schema(document, "CompletionSharedCard").get("properties"));

		for (Map<String, Object> properties : List.of(progressProperties, completionProperties)) {
			assertThat(object(properties.get("sharedCardId")))
					.containsEntry("$ref", "#/components/schemas/Uuid");
			assertThat(object(properties.get("ownerNickname")))
					.containsEntry("type", "string")
					.containsEntry("minLength", 1);
			assertThat(object(properties.get("purpose")))
					.containsEntry("$ref", "#/components/schemas/Purpose");
			assertThat(object(properties.get("targetAmount")))
					.containsEntry("$ref", "#/components/schemas/KrwPositive");
			assertThat(object(properties.get("contentUpdatedAt")))
					.containsEntry("$ref", "#/components/schemas/UtcInstant");
		}

		Map<String, Object> uuid = schema(document, "Uuid");
		assertThat(uuid).containsEntry("type", "string").containsEntry("format", "uuid");
		Map<String, Object> purpose = schema(document, "Purpose");
		assertThat(purpose)
				.containsEntry("type", "string")
				.containsEntry("minLength", 1)
				.containsEntry("maxLength", 200)
				.containsEntry("pattern",
						"^(?!\\p{Zs})(?!.*\\p{Zs}$)(?!.*[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]).+$");
		Map<String, Object> krwPositive = schema(document, "KrwPositive");
		assertThat(krwPositive)
				.containsEntry("type", "integer")
				.containsEntry("format", "int64")
				.containsEntry("minimum", 1)
				.containsEntry("maximum", 9_007_199_254_740_991L);
		Map<String, Object> utcInstant = schema(document, "UtcInstant");
		assertThat(utcInstant)
				.containsEntry("type", "string")
				.containsEntry("format", "date-time")
				.containsEntry("pattern", "Z$");

		assertThat(object(completionProperties.get("createdAt")))
				.containsEntry("$ref", "#/components/schemas/UtcInstant");
		assertThat(object(completionProperties.get("completedAt")))
				.containsEntry("$ref", "#/components/schemas/UtcInstant");
		assertThat(object(completionProperties.get("targetDate")))
				.containsEntry("type", List.of("string", "null"))
				.containsEntry("format", "date");
		assertThat(object(completionProperties.get("actualDurationSeconds")))
				.containsEntry("type", "integer")
				.containsEntry("format", "int64")
				.containsEntry("minimum", 0);

		Map<String, Object> nextCursor = object(
				object(schema(document, "SharedCardPage").get("properties")).get("nextCursor"));
		assertThat(nextCursor)
				.containsEntry("type", List.of("string", "null"))
				.containsEntry("minLength", 1);
	}

	private Map<String, Object> document() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$");
	}

	private static Map<String, Object> operation(
			Map<String, Object> document, String path, String method) {
		return object(object(object(document.get("paths")).get(path)).get(method));
	}

	private static Map<String, Object> parameter(
			Map<String, Object> document, String path, String method, String name) {
		Map<String, Object> pathItem = object(object(document.get("paths")).get(path));
		java.util.ArrayList<Object> parameters = new java.util.ArrayList<>();
		Object inherited = pathItem.get("parameters");
		if (inherited instanceof List<?> values) parameters.addAll(values);
		Object local = object(pathItem.get(method)).get("parameters");
		if (local instanceof List<?> values) parameters.addAll(values);
		return parameters.stream()
				.map(SharedCardOpenApiDocumentationTest::object)
				.map(parameter -> resolve(document, parameter))
				.filter(value -> name.equals(value.get("name")))
				.findFirst().orElseThrow();
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

	private static Map<String, Object> schema(Map<String, Object> document, String name) {
		return object(object(object(document.get("components")).get("schemas")).get(name));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Map<String, Object> value, String key) {
		return (List<Object>) value.get(key);
	}
}
