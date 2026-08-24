package com.crabit.backend.e2e.swagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.demo.DemoFixtureInitializer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;

class E2eSwaggerProfileIsolationTest {

	@Test
	void e2eProfileRegistersNeitherPersonaEndpointNorSelectorInANonWebApplication() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("e2e");
			TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
					context, "crabit.documentation.enabled=true");
			context.register(E2eSwaggerConfiguration.class, E2eSwaggerPersonaController.class);
			context.refresh();

			assertThat(context.getBeansOfType(E2eSwaggerConfiguration.class)).isEmpty();
			assertThat(context.getBeansOfType(E2eSwaggerPersonaController.class)).isEmpty();
		}
	}

	@Nested
	@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:swagger-profile-default;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=false",
		"spring.main.banner-mode=off",
		"logging.level.root=warn",
		"crabit.e2e.seed.enabled=false"
	})
	@AutoConfigureMockMvc
	class DefaultServletProfile {
		@Autowired MockMvc mockMvc;

		@Test
		void servesOrdinaryDocumentationWithoutE2eCredentials() throws Exception {
			assertOrdinaryDocumentationHasNoE2eCredentials(mockMvc);
		}
	}

	@Nested
	@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:swagger-profile-demo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=false",
		"spring.main.banner-mode=off",
		"logging.level.root=warn",
		"crabit.e2e.seed.enabled=false",
		"crabit.demo.token.owner=demo-owner-secret",
		"crabit.demo.token.friend=demo-friend-secret",
		"crabit.demo.token.nonfriend=demo-nonfriend-secret",
		"crabit.demo.token.blocked=demo-blocked-secret",
		"crabit.demo.token.other-academy=demo-other-academy-secret",
		"crabit.demo.token.staff=demo-staff-secret",
		"crabit.demo.balance-provider.url=https://console.example.test/api/provider/balance-lookups",
		"crabit.demo.balance-provider.token=demo-provider-machine-token-123456789"
	})
	@AutoConfigureMockMvc
	@ActiveProfiles("demo")
	class DemoServletProfile {
		@Autowired MockMvc mockMvc;
		@MockitoBean DemoFixtureInitializer demoFixtureInitializer;

		@Test
		void servesOrdinaryDocumentationWithoutE2eCredentials() throws Exception {
			assertOrdinaryDocumentationHasNoE2eCredentials(mockMvc);
		}
	}

	@Nested
	@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:swagger-profile-prod;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=false",
		"spring.main.banner-mode=off",
		"logging.level.root=warn"
	})
	@AutoConfigureMockMvc
	@ActiveProfiles("prod")
	class ProdServletProfile {
		@Autowired MockMvc mockMvc;

		@Test
		void exposesNeitherDocumentationNorE2eCredentials() throws Exception {
			assertDocumentationSurfaceAndE2eCredentialsAbsent(mockMvc);
		}
	}

	@Nested
	@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:swagger-profile-e2e-disabled;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=false",
		"spring.main.banner-mode=off",
		"logging.level.root=warn",
		"crabit.e2e.seed.enabled=false",
		"crabit.documentation.enabled=false"
	})
	@AutoConfigureMockMvc
	@ActiveProfiles("e2e")
	class E2eServletProfileWithDocumentationDisabled {
		@Autowired MockMvc mockMvc;

		@Test
		void exposesNeitherDocumentationNorE2eCredentials() throws Exception {
			assertDocumentationSurfaceAndE2eCredentialsAbsent(mockMvc);
		}
	}

	private static void assertOrdinaryDocumentationHasNoE2eCredentials(MockMvc mockMvc)
			throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/v3/api-docs/e2e-personas"))
				.andExpect(status().isNotFound());

		String initializer = mockMvc.perform(get("/swagger-ui/swagger-initializer.js"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		assertThat(initializer).doesNotContain(
				"crabit-e2e-persona-selector",
				"crabit.e2e.swagger.persona",
				"E2E 테스트 사용자");
	}

	private static void assertDocumentationSurfaceAndE2eCredentialsAbsent(MockMvc mockMvc)
			throws Exception {
		for (String path : new String[] {
				"/v3/api-docs/e2e-personas",
				"/v3/api-docs",
				"/v3/api-docs.yaml",
				"/v3/api-docs/swagger-config",
				"/swagger-ui/index.html",
				"/swagger-ui/swagger-initializer.js"
		}) {
			mockMvc.perform(get(path)).andExpect(status().isNotFound());
		}
	}
}
