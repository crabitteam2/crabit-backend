package com.crabit.backend.e2e;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
@ConditionalOnProperty(name = "crabit.e2e.seed.enabled", havingValue = "true")
public final class SeedFixtureInitializer implements ApplicationRunner {

	private final SeedFixtureService fixtures;
	private final boolean resetOnStartup;

	public SeedFixtureInitializer(
			SeedFixtureService fixtures,
			@Value("${crabit.e2e.seed.reset-on-startup:false}") boolean resetOnStartup) {
		this.fixtures = fixtures;
		this.resetOnStartup = resetOnStartup;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (resetOnStartup) {
			fixtures.resetAndInitialize();
		} else {
			fixtures.initialize();
		}
	}
}
