package com.crabit.backend.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.crabit.backend.openapi.CanonicalOpenApiDocument.OperationKey;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CanonicalOpenApiDocumentTest {

	@Test
	void loadsTheApprovedContractFromThePackagedClasspathResource() {
		CanonicalOpenApiDocument document = new CanonicalOpenApiDocument(
				new ClassPathResource(CanonicalOpenApiDocument.RESOURCE_PATH));

		assertThat(document.operationKeys()).hasSize(32);
		assertThat(document.root().path("info").path("description").asText())
				.contains("위시", "카드 잔액");
	}

	@Test
	void projectsOnlyImplementedOperationsAndTheirRecursiveComponentClosure() {
		CanonicalOpenApiDocument document = new CanonicalOpenApiDocument(
				new ClassPathResource(CanonicalOpenApiDocument.RESOURCE_PATH));
		String path = "/v1/card-balance-accounts/{cardBalanceAccountId}/wishes";

		ObjectNode projected = new CanonicalOpenApiProjection(document)
				.project(Set.of(new OperationKey("get", path)));

		assertThat(projected.path("paths").propertyStream().map(java.util.Map.Entry::getKey))
				.containsExactly(path);
		assertThat(projected.path("paths").path(path).has("get")).isTrue();
		assertThat(projected.path("paths").path(path).has("post")).isFalse();
		assertThat(projected.path("tags").findValuesAsText("name")).containsExactly("Wishes");
		assertThat(projected.path("components").path("securitySchemes").has("SyntheticBearer")).isTrue();
		assertThat(projected.path("components").path("schemas").has("WishPage")).isTrue();
		assertThat(projected.path("components").path("schemas").has("Wish")).isTrue();
		assertThat(projected.path("components").path("schemas").has("UtcDate")).isFalse();
		assertThat(projected.path("components").path("responses").has("InvalidAmount")).isFalse();
		assertThat(projected.path("components").path("examples").has("EmptyWishPage")).isTrue();
		assertThat(projected.path("components").path("examples").has("IdempotentReplay")).isFalse();
	}
}
