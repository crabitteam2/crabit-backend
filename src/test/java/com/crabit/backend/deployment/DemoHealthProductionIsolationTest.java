package com.crabit.backend.deployment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:prod-health-isolation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=false",
		"spring.main.banner-mode=off",
		"logging.level.root=warn"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class DemoHealthProductionIsolationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void productionExposesNoOperationalHealthRoute() throws Exception {
		mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound());
	}
}
