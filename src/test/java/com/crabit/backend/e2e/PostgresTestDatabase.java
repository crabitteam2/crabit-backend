package com.crabit.backend.e2e;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;

public final class PostgresTestDatabase {

	public static final String URL = "jdbc:tc:postgresql:16-alpine:///crabit_e2e?TC_REUSABLE=true";
	public static final DataSource DATA_SOURCE = dataSource();
	public static final JdbcTemplate JDBC = new JdbcTemplate(DATA_SOURCE);
	public static final Flyway FLYWAY = Flyway.configure()
			.dataSource(DATA_SOURCE)
			.locations("classpath:db/migration")
			.load();

	static {
		FLYWAY.migrate();
	}

	private PostgresTestDatabase() {
	}

	public static SeedFixtureService fixtures() {
		var proxy = new TransactionProxyFactoryBean();
		proxy.setTarget(new SeedFixtureService(JDBC, new SeedFixtureCatalog()));
		proxy.setTransactionManager(new DataSourceTransactionManager(DATA_SOURCE));
		proxy.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
		proxy.setProxyTargetClass(true);
		proxy.afterPropertiesSet();
		return (SeedFixtureService) proxy.getObject();
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
