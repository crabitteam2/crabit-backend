package com.crabit.backend.demo;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;

import com.crabit.backend.e2e.SeedFixtureService;
import java.time.Clock;
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

	public static final String FIXTURE_SUCCESS_MARKER =
			"CRABIT_DEMO_FIXTURE_RESET_COMPLETED";

	private final SeedFixtureService fixtures;
	private final ConfigurableApplicationContext context;
	private final Clock clock;
	private final String lifecycle;

	public DemoFixtureInitializer(
			SeedFixtureService fixtures,
			ConfigurableApplicationContext context,
			Clock clock,
			@Value("${crabit.demo.lifecycle:serve}") String lifecycle) {
		this.fixtures = fixtures;
		this.context = context;
		this.clock = clock;
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
		var recap = fixtures.resetDemoAndInitialize(clock.instant());
		System.out.printf(
				"%s account_id=%s weekly_start=%s weekly_end=%s monthly_start=%s "
						+ "monthly_end=%s weekly_request_key=%s monthly_request_key=%s%n",
				FIXTURE_SUCCESS_MARKER,
				OWNER_ACCOUNT_ID,
				recap.weekly().start(),
				recap.weekly().endExclusive(),
				recap.monthly().start(),
				recap.monthly().endExclusive(),
				recap.weeklyRequestKey(),
				recap.monthlyRequestKey());
		SpringApplication.exit(context);
	}
}
