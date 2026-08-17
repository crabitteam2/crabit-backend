package com.crabit.backend;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CrabitBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CrabitBackendApplication.class, args);
	}

	@Bean
	@Profile("!e2e")
	Clock systemClock() {
		return Clock.systemUTC();
	}

	@Bean
	@Profile("e2e")
	Clock e2eClock(@Value("${crabit.e2e.clock.instant}") String instant) {
		return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
	}

}
