package com.crabit.backend.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
		"spring.main.banner-mode=off",
		"logging.level.root=warn",
		"spring.datasource.hikari.connection-timeout=1000",
		"spring.datasource.hikari.validation-timeout=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("e2e")
class OperationalHealthIT {
	private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(
			DockerImageName.parse("postgres:16-alpine"));

	static {
		DATABASE.start();
	}

	@Autowired
	private MockMvc mockMvc;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
		properties.add("spring.datasource.username", DATABASE::getUsername);
		properties.add("spring.datasource.password", DATABASE::getPassword);
	}

	@Test
	void e2eExposesOnlyAggregateLivenessAndDatabaseBackedReadiness() throws Exception {
		for (String path : new String[] {
				"/actuator/health/liveness", "/actuator/health/readiness"
		}) {
			String body = mockMvc.perform(get(path))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("UP"))
					.andReturn()
					.getResponse()
					.getContentAsString();
			Map<String, Object> response = JsonPath.read(body, "$");
			assertThat(response).as(path).containsOnlyKeys("status");
		}

		DATABASE.stop();

		mockMvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
		String unavailable = mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andReturn()
				.getResponse()
				.getContentAsString();
		assertThat(JsonPath.<Map<String, Object>>read(unavailable, "$"))
				.containsOnlyKeys("status");
	}
}
