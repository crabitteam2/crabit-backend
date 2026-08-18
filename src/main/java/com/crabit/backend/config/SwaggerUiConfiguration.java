package com.crabit.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
		name = "crabit.documentation.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class SwaggerUiConfiguration {

	public static final String WISH_TAG = "Wishes";
	public static final String SEED_BEARER = "SeedBearer";

	@Bean
	OpenAPI wishOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("Crabit Wish API")
						.description("The generated implementation document for the seven Wish lifecycle "
								+ "operations. Amounts are integer Korean won, mutations use optimistic "
								+ "versions, unowned or tombstoned resources are hidden behind "
								+ "resource-specific 404 responses, and this generated projection is "
								+ "distinct from api/openapi.yaml."))
				.addTagsItem(new Tag()
						.name(WISH_TAG)
						.description("Create, query, edit, complete, abandon, and tombstone Wishes "
								+ "owned through a Card Balance Account."))
				.components(new Components().addSecuritySchemes(SEED_BEARER,
						new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("Seed token")));
	}
}
