package com.crabit.backend.recap;

import com.crabit.backend.account.CardBalanceAccountRepository;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.web.context.WebApplicationContext;

/** Explicit non-web entry point. It never scans the web application, schedulers or fixture initializers. */
public final class RecapRegenerationCommand {
	private RecapRegenerationCommand() {}
	public static void main(String[] args) {
		Options options = parse(args, Clock.systemUTC()); // Reject malformed operations before opening any database connection.
		try (var context = start()) {
			if (context instanceof WebApplicationContext) throw new IllegalStateException("Recap reservation requires non-web mode");
			var generation = context.getBean(RecapGenerationCoordinator.class).reserveRegeneration(
					options.requestKey(), options.account(), options.kind(), options.period(), java.time.Instant.now());
			System.out.printf("CRABIT_RECAP_RESERVED generation_id=%s generation_version=%d%n", generation.id(), generation.generationVersion());
		}
	}
	static ConfigurableApplicationContext start(String... databaseArguments) {
		String[] args = new String[databaseArguments.length + 3];
		System.arraycopy(databaseArguments, 0, args, 0, databaseArguments.length);
		args[databaseArguments.length] = "--spring.main.web-application-type=none";
		args[databaseArguments.length + 1] = "--crabit.recap.generation.enabled=false";
		args[databaseArguments.length + 2] = "--spring.main.banner-mode=off";
		return new SpringApplicationBuilder(ReservationConfiguration.class).web(WebApplicationType.NONE).run(args);
	}
	static Options parse(String[] args, Clock clock) {
		Set<String> names = Set.of("account", "kind", "period", "request-key");
		Map<String, String> options = new HashMap<>();
		for (String arg : args) {
			int separator = arg.indexOf('=');
			if (!arg.startsWith("--") || separator < 3) throw invalid();
			String name = arg.substring(2, separator), value = arg.substring(separator + 1);
			if (!names.contains(name) || value.isBlank() || options.putIfAbsent(name, value) != null) throw invalid();
		}
		if (!options.keySet().equals(names)) throw invalid();
		try {
			UUID account = uuid(options.get("account")), request = uuid(options.get("request-key"));
			RecapKind kind = RecapKind.valueOf(options.get("kind"));
			var period = kind == RecapKind.WEEKLY ? RecapPeriods.weekly(options.get("period"), clock) : RecapPeriods.monthly(options.get("period"), clock);
			return new Options(account, kind, period, request);
		} catch (RuntimeException e) { throw invalid(); }
	}
	private static UUID uuid(String value) {
		UUID result = UUID.fromString(value); if (!result.toString().equalsIgnoreCase(value)) throw invalid(); return result;
	}
	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("Expected exactly --account=<UUID> --kind=<WEEKLY|MONTHLY> --period=<completed week start|month> --request-key=<UUID>");
	}
	record Options(UUID account, RecapKind kind, RecapPeriods.Period period, UUID requestKey) {}

	// No @Configuration/@Component: normal application component scanning must not import this context.
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.crabit.backend")
	@EnableJpaRepositories(basePackageClasses = {RecapGenerationRepository.class, CardBalanceAccountRepository.class})
	@Import(RecapGenerationCoordinator.class)
	static class ReservationConfiguration {}
}
