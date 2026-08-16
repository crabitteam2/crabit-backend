package com.crabit.backend.config;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
		prefix = "crabit.documentation",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = true)
public final class SwaggerUiConfiguration {

	private static final MediaType YAML = MediaType.parseMediaType("application/yaml");
	private static final String PACKAGED_CONTRACT = "META-INF/crabit/openapi.yaml";

	private final Resource contract = new ClassPathResource(PACKAGED_CONTRACT);

	@GetMapping(value = "/openapi/openapi.yaml", produces = "application/yaml")
	public ResponseEntity<Resource> approvedOpenApiContract() throws IOException {
		return ResponseEntity.ok()
				.contentType(YAML)
				.contentLength(contract.contentLength())
				.body(contract);
	}
}
