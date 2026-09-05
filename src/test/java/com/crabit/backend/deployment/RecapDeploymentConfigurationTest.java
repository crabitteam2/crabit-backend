package com.crabit.backend.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecapDeploymentConfigurationTest {

	@ParameterizedTest
	@ValueSource(strings = {"application-e2e.properties", "application-demo.properties"})
	void recapGenerationIsDisabledWithoutTheCompleteRuntimeBinding(String resource) throws IOException {
		Properties properties = load(resource);

		assertThat(properties)
				.containsEntry("crabit.recap.generation.enabled",
						"${CRABIT_RECAP_GENERATION_ENABLED:false}")
				.containsEntry("crabit.recap.generation.url",
						"${CRABIT_RECAP_GENERATION_URL:}")
				.containsEntry("crabit.recap.generation.credential",
						"${CRABIT_RECAP_GENERATION_CREDENTIAL:}")
				.containsEntry("crabit.recap.generation.poll-delay-ms",
						"${CRABIT_RECAP_GENERATION_POLL_DELAY_MS:30000}");
	}

	private static Properties load(String resource) throws IOException {
		Properties properties = new Properties();
		try (InputStream input = RecapDeploymentConfigurationTest.class
				.getClassLoader().getResourceAsStream(resource)) {
			assertThat(input).as(resource).isNotNull();
			properties.load(input);
		}
		return properties;
	}
}
