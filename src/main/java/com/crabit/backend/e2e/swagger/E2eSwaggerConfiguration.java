package com.crabit.backend.e2e.swagger;

import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerIndexTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("e2e")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "crabit.documentation.enabled", havingValue = "true")
public class E2eSwaggerConfiguration {
	@Bean
	SwaggerIndexTransformer e2eSwaggerIndexTransformer(
			SwaggerUiConfigProperties config,
			SwaggerUiOAuthProperties oauth,
			SwaggerWelcomeCommon welcome,
			ObjectMapperProvider mapperProvider) {
		return new E2eSwaggerIndexTransformer(
				new SwaggerIndexPageTransformer(config, oauth, welcome, mapperProvider));
	}
}
