package com.crabit.backend.e2e.swagger;

import com.crabit.backend.e2e.SeedFixtureCatalog;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@Profile("e2e")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "crabit.documentation.enabled", havingValue = "true")
public final class E2eSwaggerPersonaController {
	private final SeedFixtureCatalog fixtures;

	public E2eSwaggerPersonaController(SeedFixtureCatalog fixtures) {
		this.fixtures = fixtures;
	}

	@GetMapping("/v3/api-docs/e2e-personas")
	ResponseEntity<List<PersonaView>> personas() {
		List<PersonaView> body = fixtures.personas().stream()
				.map(persona -> new PersonaView(persona.key(), persona.displayName(), persona.token()))
				.toList();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	public record PersonaView(String key, String label, String token) {}
}
