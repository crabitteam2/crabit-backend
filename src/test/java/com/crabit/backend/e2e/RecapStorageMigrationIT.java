package com.crabit.backend.e2e;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class RecapStorageMigrationIT {
	@Test void upgradesVersion16WithoutChangingAnyExistingIdentityInputResultOrTimestamp() {
		try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
			postgres.start(); var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			var jdbc = new JdbcTemplate(source);
			Flyway.configure().dataSource(source).locations("classpath:db/migration").target("16").load().migrate();
			var before = seedLegacy(jdbc);
			var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
			assertThat(flyway.migrate().migrationsExecuted).isOne();
			assertThat(jdbc.queryForList("select (to_jsonb(g)-'stage'-'preparation_attempt_count'-'reservation_key')::text from recap_generation g order by generation_version", String.class)).isEqualTo(before);
			assertThat(jdbc.queryForObject("select bool_and(stage='GENERATION' and preparation_attempt_count=0 and reservation_key is null) from recap_generation", Boolean.class)).isTrue();
			assertThat(flyway.migrate().migrationsExecuted).isZero();
		}
	}

	@Test void originalPr62Version17ThenVersion18InstallsAndRerunsWithoutOutOfOrder() throws Exception {
		String fixture = System.getenv("CRABIT_RECAP_COMPATIBILITY_V17");
		Assumptions.assumeTrue(fixture != null, "Set CRABIT_RECAP_COMPATIBILITY_V17 to the byte-verified original PR62 SQL for release-order verification");
		assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(fixture)))))
				.isEqualTo("f7457c91dd1323755063efa3930b14a5a74fabd07c7f2219a1a8ca1b04484dc7");
		Path directory = Files.createTempDirectory("recap-v17-compatibility-");
		try {
			Files.copy(Path.of(fixture), directory.resolve("V17__historical_balance_progress.sql"));
			try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
				postgres.start(); var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				var baseline = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("16").load(); baseline.migrate();
				var jdbc = new JdbcTemplate(source); var before = seedLegacy(jdbc);
				var combined = Flyway.configure().dataSource(source).locations("classpath:db/migration", "filesystem:" + directory).load();
				assertThat(combined.migrate().migrationsExecuted).isEqualTo(2); assertThat(combined.migrate().migrationsExecuted).isZero();
				assertThat(jdbc.queryForList("select (to_jsonb(g)-'stage'-'preparation_attempt_count'-'reservation_key')::text from recap_generation g order by generation_version", String.class)).isEqualTo(before);
				assertThat(new JdbcTemplate(source).queryForList("select version from flyway_schema_history where version in ('17','18') order by installed_rank", String.class)).containsExactly("17", "18");
			}
		} finally { Files.deleteIfExists(directory.resolve("V17__historical_balance_progress.sql")); Files.deleteIfExists(directory); }
	}

	private static List<String> seedLegacy(JdbcTemplate jdbc) {
		UUID academy = UUID.randomUUID(), student = UUID.randomUUID(), account = UUID.randomUUID();
		jdbc.update("insert into academy(id,name) values (?,'migration')", academy);
		jdbc.update("insert into student(id,nickname,age) values (?,'legacy',15)", student);
		jdbc.update("insert into card_balance_account(id,student_id,academy_id,opened_at) values (?,?,?,?)", account, student, academy, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
		int version = 0;
		for (String state : List.of("SUCCEEDED", "FAILED", "NOT_ELIGIBLE", "SUPERSEDED")) {
			jdbc.update("""
				insert into recap_generation(id,account_id,student_id,academy_id,kind,period_start,period_end_exclusive,
				 schema_version,algorithm_version,generation_version,input_digest,request_json,view_json,internal_metrics_json,
				 state,attempt_count,created_at,generated_at,current_version)
				 values (?,?,?,?,'MONTHLY','2026-08-01','2026-09-01',1,'recap-1',?,'sha256:legacy',?, ?, ?, ?,1,
				 '2026-09-01T00:00:00Z',?::timestamptz,?)
				""", UUID.randomUUID(), account, student, academy, ++version, "{ \"request\": " + version + " }",
				state.equals("NOT_ELIGIBLE") || state.equals("FAILED") ? null : "{ \"view\": " + version + " }",
				state.equals("NOT_ELIGIBLE") || state.equals("FAILED") ? null : "{ \"qa\": " + version + " }", state,
				state.equals("FAILED") ? null : "2026-09-01T00:01:00Z", state.equals("SUCCEEDED"));
		}
		return jdbc.queryForList("select to_jsonb(g)::text as value from recap_generation g order by generation_version", String.class);
	}
}
