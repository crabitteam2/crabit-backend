package com.crabit.backend.e2e.swagger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

class E2eSwaggerProfileIsolationTest {

	@Test
	void defaultProfileRegistersNeitherPersonaEndpointNorSelector() {
		assertE2eSwaggerBeansAbsent();
	}

	@Test
	void demoProfileRegistersNeitherPersonaEndpointNorSelector() {
		assertE2eSwaggerBeansAbsent("demo");
	}

	@Test
	void prodProfileRegistersNeitherPersonaEndpointNorSelector() {
		assertE2eSwaggerBeansAbsent("prod");
	}

	@Test
	void e2eProfileRegistersNeitherPersonaEndpointNorSelectorInANonWebApplication() {
		assertE2eSwaggerBeansAbsent("e2e");
	}

	private static void assertE2eSwaggerBeansAbsent(String... profiles) {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles(profiles);
			TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
					context, "crabit.documentation.enabled=true");
			context.register(E2eSwaggerConfiguration.class, E2eSwaggerPersonaController.class);
			context.refresh();

			assertThat(context.getBeansOfType(E2eSwaggerConfiguration.class)).isEmpty();
			assertThat(context.getBeansOfType(E2eSwaggerPersonaController.class)).isEmpty();
		}
	}
}
