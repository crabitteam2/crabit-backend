package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
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
		void servesSwaggerUiConfiguredForOnlyTheApprovedStaticContract() throws Exception {
			mockMvc.perform(get("/swagger-ui/index.html"))
					.andExpect(status().isOk())
					.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

			mockMvc.perform(get("/v3/api-docs/swagger-config"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.url").value("/openapi/openapi.yaml"));

			mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
			mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isNotFound());
		}

		@Test
		void servesThePackagedApprovedContractWithoutChangingAnyByte() throws Exception {
			byte[] approved = Files.readAllBytes(Path.of("api", "openapi.yaml"));
			byte[] packaged = new ClassPathResource("META-INF/crabit/openapi.yaml")
					.getContentAsByteArray();

			assertThat(packaged).isEqualTo(approved);
			mockMvc.perform(get("/openapi/openapi.yaml"))
					.andExpect(status().isOk())
					.andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/yaml")))
					.andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
							.isEqualTo(approved));
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
