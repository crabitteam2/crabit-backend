package com.crabit.backend.demo;

import com.crabit.backend.e2e.SeedFixtureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

@Component
@Profile("demo & !e2e")
public final class DemoFixtureInitializer implements ApplicationRunner {

	public static final String SUCCESS_MARKER = "CRABIT_DEMO_RESET_COMPLETED";

	private static final Logger LOG = LoggerFactory.getLogger(DemoFixtureInitializer.class);

	private final SeedFixtureService fixtures;
	private final ConfigurableApplicationContext context;
	private final String lifecycle;

	public DemoFixtureInitializer(
			SeedFixtureService fixtures,
			ConfigurableApplicationContext context,
			@Value("${crabit.demo.lifecycle:serve}") String lifecycle) {
		this.fixtures = fixtures;
		this.context = context;
		this.lifecycle = lifecycle;
	}

	@Override
	public void run(ApplicationArguments args) {
		switch (lifecycle) {
			case "serve" -> fixtures.initialize();
			case "reset" -> resetAndExit();
			default -> throw new IllegalStateException(
					"Unsupported crabit.demo.lifecycle: " + lifecycle);
		}
	}

	private void resetAndExit() {
		if (context instanceof WebApplicationContext) {
			throw new IllegalStateException(
					"Demo reset requires spring.main.web-application-type=none");
		}
		fixtures.resetAndInitialize();
		LOG.info(SUCCESS_MARKER);
		SpringApplication.exit(context);
	}
}
