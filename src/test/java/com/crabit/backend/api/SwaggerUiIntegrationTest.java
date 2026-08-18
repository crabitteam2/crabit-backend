package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class SwaggerUiIntegrationTest {

	@Nested
	@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:swagger-ui-enabled;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=false",
		"spring.main.banner-mode=off",
		"logging.level.root=warn",
		"crabit.documentation.enabled=true"
	})
	@AutoConfigureMockMvc
	class EnabledDocumentation {

		@Autowired
		private MockMvc mockMvc;

		@Test
		void servesSwaggerUiConfiguredForTheGeneratedOpenApiDocument() throws Exception {
			mockMvc.perform(get("/swagger-ui/index.html"))
					.andExpect(status().isOk())
					.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

			mockMvc.perform(get("/v3/api-docs/swagger-config"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.url").value("/v3/api-docs"));

			mockMvc.perform(get("/v3/api-docs.yaml"))
					.andExpect(status().isOk())
					.andExpect(content().contentTypeCompatibleWith(
							MediaType.parseMediaType("application/vnd.oai.openapi")))
					.andExpect(content().string(org.hamcrest.Matchers.containsString("openapi:")));
		}

		@Test
		void generatedDocumentDescribesTheImplementedWishApiAndResponseDtos() throws Exception {
			String document = mockMvc.perform(get("/v3/api-docs"))
					.andExpect(status().isOk())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
					.andReturn()
					.getResponse()
					.getContentAsString();

			Map<String, Map<String, Object>> paths = JsonPath.read(document, "$.paths");
			assertThat(paths).containsOnlyKeys(
					"/v1/me/card-balance-accounts",
					"/v1/card-balance-accounts/{cardBalanceAccountId}/balance-refreshes",
					"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes",
					"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}",
					"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/completion",
					"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/abandonment",
					"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/deposits",
					"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/withdrawals",
					"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers",
					"/v1/academies/{academyId}/shared-cards",
					"/v1/academies/{academyId}/shared-cards/{cardId}");
			assertThat(operationInventory(paths)).containsExactlyInAnyOrder(
					"GET /v1/me/card-balance-accounts",
					"POST /v1/card-balance-accounts/{cardBalanceAccountId}/balance-refreshes",
					"GET /v1/card-balance-accounts/{cardBalanceAccountId}/wishes",
					"POST /v1/card-balance-accounts/{cardBalanceAccountId}/wishes",
					"GET /v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}",
					"PATCH /v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}",
					"DELETE /v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}",
					"POST /v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/completion",
					"POST /v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/abandonment",
					"POST /v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/deposits",
					"POST /v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/withdrawals",
					"POST /v1/card-balance-accounts/{cardBalanceAccountId}/transfers",
					"GET /v1/academies/{academyId}/shared-cards",
					"GET /v1/academies/{academyId}/shared-cards/{cardId}");

			Map<String, Map<String, Object>> schemas = JsonPath.read(document, "$.components.schemas");
			assertThat(schemas).containsKeys(
					"WishPage", "Wish", "WishMutationResult", "WishAmountCommand",
					"WishTransferRequest", "WishTransferResult", "SharedCardPage",
					"SharedCard", "ProgressSharedCard", "CompletionSharedCard");
			assertThat(properties(schemas, "WishPage")).contains("items", "nextCursor");
			assertThat(properties(schemas, "Wish")).contains(
					"id", "cardBalanceAccountId", "purpose", "targetAmount", "amount",
					"targetDate", "state", "visibility", "createdAt", "updatedAt",
					"completedAt", "actualDurationSeconds", "version");
			assertThat(properties(schemas, "WishMutationResult")).contains("wish", "eventId");
			assertThat(properties(schemas, "WishTransferResult"))
					.contains("sourceWish", "destinationWish", "eventId", "occurredAt");
			assertThat(properties(schemas, "SharedCardPage")).contains("items", "nextCursor");
			assertThat(properties(schemas, "ProgressSharedCard")).containsExactlyInAnyOrder(
					"sharedCardId", "kind", "ownerNickname", "purpose", "targetAmount",
					"progressPercent", "balanceAdjustmentInProgress", "contentUpdatedAt");
			assertThat(properties(schemas, "CompletionSharedCard")).containsExactlyInAnyOrder(
					"sharedCardId", "kind", "ownerNickname", "purpose", "targetAmount",
					"progressPercent", "targetDate", "createdAt", "completedAt",
					"actualDurationSeconds", "contentUpdatedAt");
		}

		@Test
		void doesNotServeTheSupersededStaticContractEndpoint() throws Exception {
			mockMvc.perform(get("/openapi/openapi.yaml"))
					.andExpect(status().isNotFound());
		}

		private static Set<String> operationInventory(Map<String, Map<String, Object>> paths) {
			Set<String> httpMethods = Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
			return paths.entrySet().stream()
					.flatMap(path -> path.getValue().keySet().stream()
							.filter(httpMethods::contains)
							.map(method -> method.toUpperCase() + " " + path.getKey()))
					.collect(java.util.stream.Collectors.toSet());
		}

		@SuppressWarnings("unchecked")
		private static Set<String> properties(
				Map<String, Map<String, Object>> schemas,
				String schemaName) {
			return ((Map<String, Object>) schemas.get(schemaName).get("properties")).keySet();
		}
	}

	@Nested
	@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:swagger-ui-disabled;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=false",
		"spring.main.banner-mode=off",
		"logging.level.root=warn",
		"crabit.documentation.enabled=false"
	})
	@AutoConfigureMockMvc
	class DisabledDocumentation {

		@Autowired
		private MockMvc mockMvc;

		@Test
		void exposesNoDocumentationSurface() throws Exception {
			for (String path : new String[] {
					"/swagger-ui/index.html",
					"/swagger-ui.html",
					"/v3/api-docs/swagger-config",
					"/v3/api-docs",
					"/v3/api-docs.yaml",
					"/openapi/openapi.yaml"
			}) {
				mockMvc.perform(get(path)).andExpect(status().isNotFound());
			}
		}
	}
}
