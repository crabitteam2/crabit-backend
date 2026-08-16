package com.crabit.backend.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.crabit.backend.CrabitBackendApplication;
import javax.sql.DataSource;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class PostgresMigrationIT {

	@Test
	void migratesAnEmptyPostgresDatabaseAndIsIdempotent() {
		Set<String> tables = Set.copyOf(PostgresTestDatabase.JDBC.queryForList("""
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema = 'public'
				""", String.class));

		assertThat(tables).contains(
				"academy", "student", "academy_membership", "friendship", "student_block",
				"card_balance_account", "balance_observation", "wish", "ledger_event",
				"ledger_wish_effect", "balance_adjustment_case",
				"balance_adjustment_case_event", "mismatch_notification_outbox", "shared_card");
		assertThat(PostgresTestDatabase.FLYWAY.migrate().migrationsExecuted).isZero();

		Set<String> indexes = Set.copyOf(PostgresTestDatabase.JDBC.queryForList("""
				SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
				""", String.class));
		assertThat(indexes).contains(
				"uk_card_account_active", "uk_adjustment_case_open",
				"uk_shared_card_current_wish", "uk_mismatch_notification_case");
	}

	@Test
	void startsTheE2eApplicationOnEmptyPostgresAndKeepsSeedComponentsOutOfProduction() {
		try (PostgreSQLContainer postgres = new PostgreSQLContainer(
				DockerImageName.parse("postgres:16-alpine"))) {
			postgres.start();

			try (ConfigurableApplicationContext e2e = startApplication(postgres, "e2e")) {
				assertThat(e2e.getBeansOfType(SeedFixtureInitializer.class)).hasSize(1);
				assertThat(e2e.getBeansOfType(SeedTokenRegistry.class)).hasSize(1);
			}

			JdbcTemplate jdbc = new JdbcTemplate(dataSource(postgres));
			assertThat(jdbc.queryForObject(
					"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'",
					Long.class)).isEqualTo(15L);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM student", Long.class)).isEqualTo(5L);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM wish", Long.class)).isEqualTo(2L);

			try (ConfigurableApplicationContext e2e = startApplication(postgres, "e2e")) {
				assertThat(e2e.getBeansOfType(SeedFixtureInitializer.class)).hasSize(1);
			}
			assertThat(jdbc.queryForObject("SELECT count(*) FROM student", Long.class)).isEqualTo(5L);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM wish", Long.class)).isEqualTo(2L);

			try (ConfigurableApplicationContext prod = startApplication(postgres, "prod")) {
				assertThat(prod.getBeansOfType(SeedFixtureCatalog.class)).isEmpty();
				assertThat(prod.getBeansOfType(SeedFixtureService.class)).isEmpty();
				assertThat(prod.getBeansOfType(SeedFixtureInitializer.class)).isEmpty();
				assertThat(prod.getBeansOfType(SeedTokenRegistry.class)).isEmpty();
				assertThat(prod.getBeansOfType(SeedBearerAuthenticationFilter.class)).isEmpty();
			}
		}
	}

	private static ConfigurableApplicationContext startApplication(
			PostgreSQLContainer postgres,
			String profile) {
		return new SpringApplicationBuilder(CrabitBackendApplication.class)
				.web(WebApplicationType.NONE)
				.profiles(profile)
				.properties("spring.main.banner-mode=off", "logging.level.root=warn")
				.run(
						"--spring.datasource.url=" + postgres.getJdbcUrl(),
						"--spring.datasource.username=" + postgres.getUsername(),
						"--spring.datasource.password=" + postgres.getPassword());
	}

	private static DataSource dataSource(PostgreSQLContainer postgres) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName(postgres.getDriverClassName());
		dataSource.setUrl(postgres.getJdbcUrl());
		dataSource.setUsername(postgres.getUsername());
		dataSource.setPassword(postgres.getPassword());
		return dataSource;
	}
}
