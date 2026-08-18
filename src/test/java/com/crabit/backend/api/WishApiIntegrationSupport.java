package com.crabit.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.crabit.backend.e2e.SeedFixtureCatalog;
import com.crabit.backend.e2e.SeedFixtureService;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
		"spring.main.banner-mode=off",
		"logging.level.root=warn"
})
@AutoConfigureMockMvc
@ActiveProfiles("e2e")
@Import(WishApiIntegrationSupport.FixedClockConfiguration.class)
public abstract class WishApiIntegrationSupport {

	protected static final String WISHES_PATH =
			"/v1/card-balance-accounts/" + SeedFixtureCatalog.OWNER_ACCOUNT_ID + "/wishes";
	protected static final Instant COMMAND_TIME = Instant.parse("2026-08-18T00:00:00Z");

	private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(
			DockerImageName.parse("postgres:16-alpine"));

	static {
		DATABASE.start();
	}

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	private SeedFixtureService fixtures;

	@Autowired
	protected MutableClock clock;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry properties) {
		properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
		properties.add("spring.datasource.username", DATABASE::getUsername);
		properties.add("spring.datasource.password", DATABASE::getPassword);
	}

	@BeforeEach
	void resetFixture() {
		clock.set(COMMAND_TIME);
		fixtures.resetAndInitialize();
	}

	protected ResultActions asOwner(MockHttpServletRequestBuilder request) throws Exception {
		return asToken(SeedFixtureCatalog.OWNER_TOKEN, request);
	}

	protected ResultActions asToken(
			String token, MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	protected String createWish(String key, String purpose, long targetAmount) throws Exception {
		String body = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"purpose":"%s","targetAmount":%d}
						""".formatted(purpose, targetAmount)))
				.andReturn()
				.getResponse()
				.getContentAsString();
		return JsonPath.read(body, "$.wish.id");
	}

	protected void setBalanceScenario(String steps) throws Exception {
		mockMvc.perform(put("/e2e/card-balance-accounts/{accountId}/balance-scenario",
				SeedFixtureCatalog.OWNER_ACCOUNT_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"steps\":" + steps + "}"))
				.andReturn();
	}

	protected static <T> T json(String body, String path) {
		return JsonPath.read(body, path);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(COMMAND_TIME);
		}
	}

	public static final class MutableClock extends Clock {

		private final AtomicReference<Instant> instant;

		private MutableClock(Instant initial) {
			instant = new AtomicReference<>(initial);
		}

		public void set(Instant value) {
			instant.set(value);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			if (!ZoneOffset.UTC.equals(zone)) {
				throw new IllegalArgumentException("Wish lifecycle tests use UTC only");
			}
			return this;
		}

		@Override
		public Instant instant() {
			return instant.get();
		}
	}
}
