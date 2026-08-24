package com.crabit.backend.e2e.swagger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("e2e")
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:e2e-swagger;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=none",
	"spring.flyway.enabled=false",
	"crabit.e2e.seed.enabled=false",
	"crabit.documentation.enabled=true",
	"logging.level.root=warn"
})
@AutoConfigureMockMvc
class E2eSwaggerIntegrationTest {
	@Autowired MockMvc mockMvc;

	@Test
	void exposesSixNoStorePersonasAndInjectsAKeyOnlySelector() throws Exception {
		mockMvc.perform(get("/v3/api-docs/e2e-personas"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.length()").value(6))
				.andExpect(jsonPath("$[0].key").value("owner"))
				.andExpect(jsonPath("$[0].label").value("오너"))
				.andExpect(jsonPath("$[0].token").value("seed-owner-token"));

		String initializer = mockMvc.perform(get("/swagger-ui/swagger-initializer.js"))
				.andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		assertThat(initializer).contains(
				"E2E 테스트 사용자", "crabit.e2e.swagger.persona", "preauthorizeApiKey",
				"sessionStorage.setItem(storageKey, persona.key)", "window.ui.logout([scheme])")
				.doesNotContain(
						"sessionStorage.setItem(storageKey, persona.token)",
						"/e2e/reset", "resetSeedFixtures", "fixture-reset");
	}
}
