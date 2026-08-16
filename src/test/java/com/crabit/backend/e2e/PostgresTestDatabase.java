package com.crabit.backend.e2e;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class PostgresTestDatabase {

	static final String URL = "jdbc:tc:postgresql:16-alpine:///crabit_e2e?TC_REUSABLE=true";
	static final DataSource DATA_SOURCE = dataSource();
	static final JdbcTemplate JDBC = new JdbcTemplate(DATA_SOURCE);
	static final Flyway FLYWAY = Flyway.configure()
			.dataSource(DATA_SOURCE)
			.locations("classpath:db/migration")
			.load();

	static {
		FLYWAY.migrate();
	}

	private PostgresTestDatabase() {
	}

	static SeedFixtureService fixtures() {
		return new SeedFixtureService(JDBC, new SeedFixtureCatalog());
	}

	private static DataSource dataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.testcontainers.jdbc.ContainerDatabaseDriver");
		dataSource.setUrl(URL);
		dataSource.setUsername("test");
		dataSource.setPassword("test");
		return dataSource;
	}
}
