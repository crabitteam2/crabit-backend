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
				.containsEntry("summary", "List currently visible Shared Cards in an academy");
		assertThat(list.get("description").toString()).contains(
				"current academy membership", "canonical friendship", "bilateral blocking",
				"filtered before opaque keyset pagination", "never reorder");
		assertThat(detail)
				.containsEntry("operationId", "getAcademySharedCard")
				.containsEntry("summary", "Get one currently visible Shared Card");
		assertThat(detail.get("description").toString()).contains(
				"owner", "currently enrolled", "SHARED_CARD_NOT_FOUND");
		for (Map<String, Object> operation : List.of(list, detail)) {
			assertThat(operation.get("tags")).isEqualTo(List.of("Shared Cards"));
			assertThat(operation.get("security")).isEqualTo(List.of(Map.of("SeedBearer", List.of())));
		}
		assertThat(object(list.get("responses")).keySet())
				.containsExactlyInAnyOrder("200", "400", "401", "403", "404");
		assertThat(object(detail.get("responses")).keySet())
				.containsExactlyInAnyOrder("200", "401", "403", "404");
		String hiddenDescription = object(object(detail.get("responses")).get("404"))
				.get("description").toString();
		assertThat(hiddenDescription).contains("ACADEMY_NOT_FOUND", "SHARED_CARD_NOT_FOUND");
	}

	@Test
	void documentsOpaqueKeysetParametersClosedVariantsAndPrivacyExclusions() throws Exception {
		Map<String, Object> document = document();
		Map<String, Object> list = operation(document, COLLECTION, "get");
		Map<String, Object> academyId = parameter(list, "academyId");
		Map<String, Object> cursor = parameter(list, "cursor");
		Map<String, Object> limit = parameter(list, "limit");

		assertThat(academyId).containsEntry("in", "path").containsEntry("required", true);
		assertThat(object(academyId.get("schema"))).containsEntry("format", "uuid");
		assertThat(cursor.get("description").toString()).contains("Opaque cursor", "fixed card order");
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
				"progressPercent", "balanceAdjustmentInProgress", "contentUpdatedAt");
		assertThat(completionProperties).containsExactlyInAnyOrder(
				"sharedCardId", "kind", "ownerNickname", "purpose", "targetAmount",
				"progressPercent", "targetDate", "createdAt", "completedAt",
				"actualDurationSeconds", "contentUpdatedAt");
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
		assertThat(adjustmentDescription).contains("OPEN", "owning account");
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

	private static Map<String, Object> parameter(Map<String, Object> operation, String name) {
		return list(operation, "parameters").stream()
				.map(SharedCardOpenApiDocumentationTest::object)
				.filter(value -> name.equals(value.get("name")))
				.findFirst().orElseThrow();
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
