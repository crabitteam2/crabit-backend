package com.crabit.backend.config;

import com.crabit.backend.openapi.CanonicalOpenApiCustomizer;
import com.crabit.backend.openapi.CanonicalOpenApiDocument;
import com.crabit.backend.openapi.CanonicalOpenApiProjection;
import com.crabit.backend.openapi.ImplementedOpenApiRoutes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
		name = "crabit.documentation.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class SwaggerUiConfiguration {

	public static final String WISH_TAG = "Wishes";
	public static final String SHARED_CARD_TAG = "Shared Cards";
	public static final String SYNTHETIC_BEARER = "SyntheticBearer";

	@Bean
	CanonicalOpenApiDocument canonicalOpenApiDocument() {
		return new CanonicalOpenApiDocument(new ClassPathResource(CanonicalOpenApiDocument.RESOURCE_PATH));
	}

	@Bean
	CanonicalOpenApiProjection canonicalOpenApiProjection(CanonicalOpenApiDocument document) {
		return new CanonicalOpenApiProjection(document);
	}

	@Bean
	ImplementedOpenApiRoutes implementedOpenApiRoutes(
			@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings,
			CanonicalOpenApiDocument document) {
		return new ImplementedOpenApiRoutes(mappings, document);
	}
	@Bean
	OpenApiCustomizer canonicalOpenApiCustomizer(
			CanonicalOpenApiProjection projection, ImplementedOpenApiRoutes routes) {
		return new CanonicalOpenApiCustomizer(projection, routes);
	}
}
