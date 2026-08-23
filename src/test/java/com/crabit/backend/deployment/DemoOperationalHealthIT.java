package com.crabit.backend.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.e2e.PostgresTestDatabase;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
		"spring.main.banner-mode=off",
		"logging.level.root=warn"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class DemoOperationalHealthIT {

	@Autowired
	private MockMvc mockMvc;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", () -> PostgresTestDatabase.URL);
		properties.add("spring.datasource.username", () -> "test");
		properties.add("spring.datasource.password", () -> "test");
		properties.add("crabit.demo.token.owner", () -> "demo-owner-secret");
		properties.add("crabit.demo.token.friend", () -> "demo-friend-secret");
		properties.add("crabit.demo.token.nonfriend", () -> "demo-nonfriend-secret");
		properties.add("crabit.demo.token.blocked", () -> "demo-blocked-secret");
		properties.add("crabit.demo.token.other-academy", () -> "demo-other-academy-secret");
		properties.add("crabit.demo.token.staff", () -> "demo-staff-secret");
		properties.add("crabit.demo.balance-provider.url",
				() -> "https://console.example.test/api/provider/balance-lookups");
		properties.add("crabit.demo.balance-provider.token",
				() -> "demo-provider-machine-token-123456789");
	}

	@Test
	void demoExposesStatusOnlyHealthWithoutAddingResetOrActuatorToProductOpenApi()
			throws Exception {
		for (String path : new String[] {
				"/actuator/health/liveness", "/actuator/health/readiness"
		}) {
			String body = mockMvc.perform(get(path))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("UP"))
					.andReturn()
					.getResponse()
					.getContentAsString();
			assertThat(JsonPath.<Map<String, Object>>read(body, "$"))
					.as(path)
					.containsOnlyKeys("status");
		}

		String document = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		Map<String, Object> paths = JsonPath.read(document, "$.paths");
		assertThat(paths.keySet()).noneMatch(path ->
				path.startsWith("/actuator") || path.toLowerCase().contains("reset"));

		mockMvc.perform(get("/demo/reset")
				.header(HttpHeaders.AUTHORIZATION, "Bearer demo-owner-secret"))
				.andExpect(status().isNotFound());
	}
}
