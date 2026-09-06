package com.crabit.backend.demo;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.CrabitBackendApplication;
import com.crabit.backend.e2e.PostgresTestDatabase;
import com.crabit.backend.e2e.SeedFixtureService;
import com.crabit.backend.wish.SharedCardQueryRepository;
import com.crabit.backend.wish.WishLifecycleService;
import com.crabit.backend.wish.WishPatch;
import com.crabit.backend.wish.WishVisibility;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class DemoFixtureLifecycleIT {

	@BeforeEach
	void resetDatabase() {
		PostgresTestDatabase.fixtures().resetAndInitialize();
	}

	@Test
	void ordinaryDemoStartupFillsMissingFixturesWithoutRestoringExistingMutations() {
		PostgresTestDatabase.JDBC.update(
				"UPDATE wish SET purpose = '사용자가 바꾼 이름' WHERE id = ?", LAPTOP_WISH_ID);
		PostgresTestDatabase.JDBC.update("DELETE FROM shared_card WHERE wish_id = ?", CAMP_WISH_ID);
		PostgresTestDatabase.JDBC.update("DELETE FROM wish WHERE id = ?", CAMP_WISH_ID);

		try (ConfigurableApplicationContext ignored = startDemo("serve")) {
			assertThat(PostgresTestDatabase.JDBC.queryForObject(
					"SELECT purpose FROM wish WHERE id = ?", String.class, LAPTOP_WISH_ID))
					.isEqualTo("사용자가 바꾼 이름");
			assertThat(PostgresTestDatabase.JDBC.queryForObject(
					"SELECT count(*) FROM wish WHERE id = ?", Long.class, CAMP_WISH_ID))
					.isOne();
		}
	}

	@Test
	void ordinaryDemoRestartPreservesPrivateVisibilityWithoutRepublishingTheWish() {
		try (ConfigurableApplicationContext context = startDemo("serve")) {
			context.getBean(WishLifecycleService.class).patch(
					OWNER_ID,
					PRIMARY_ACADEMY_ID,
					OWNER_ACCOUNT_ID,
					LAPTOP_WISH_ID,
					0,
					new WishPatch(null, null, false, null, WishVisibility.PRIVATE));
		}

		try (ConfigurableApplicationContext context = startDemo("serve")) {
			assertThat(PostgresTestDatabase.JDBC.queryForObject(
					"SELECT visibility FROM wish WHERE id = ?", String.class, LAPTOP_WISH_ID))
					.isEqualTo("PRIVATE");
			assertThat(PostgresTestDatabase.JDBC.queryForObject(
					"SELECT count(*) FROM shared_card WHERE wish_id = ?", Long.class,
					LAPTOP_WISH_ID)).isZero();
			assertThat(context.getBean(SharedCardQueryRepository.class)
					.findVisiblePage(FRIEND_ID, PRIMARY_ACADEMY_ID, null, 10))
					.noneMatch(card -> card.purpose().equals("노트북"));
		}
	}

	@Test
	void ordinaryDemoRestartPreservesTheCardCreatedByAPrivateToPublicTransition() {
		UUID transitionedCardId;
		try (ConfigurableApplicationContext context = startDemo("serve")) {
			WishLifecycleService wishes = context.getBean(WishLifecycleService.class);
			wishes.patch(
					OWNER_ID,
					PRIMARY_ACADEMY_ID,
					OWNER_ACCOUNT_ID,
					LAPTOP_WISH_ID,
					0,
					new WishPatch(null, null, false, null, WishVisibility.PRIVATE));
			wishes.patch(
					OWNER_ID,
					PRIMARY_ACADEMY_ID,
					OWNER_ACCOUNT_ID,
					LAPTOP_WISH_ID,
					1,
					new WishPatch(null, null, false, null, WishVisibility.FOLLOWERS));
			transitionedCardId = PostgresTestDatabase.JDBC.queryForObject(
					"SELECT id FROM shared_card WHERE wish_id = ?", UUID.class, LAPTOP_WISH_ID);
		}

		try (ConfigurableApplicationContext context = startDemo("serve")) {
			assertThat(PostgresTestDatabase.JDBC.queryForList(
					"SELECT id, visibility FROM shared_card WHERE wish_id = ?", LAPTOP_WISH_ID))
					.singleElement()
					.satisfies(card -> assertThat(card)
							.containsEntry("id", transitionedCardId)
							.containsEntry("visibility", "FOLLOWERS"));
			assertThat(context.getBean(SharedCardQueryRepository.class)
					.findVisiblePage(FRIEND_ID, PRIMARY_ACADEMY_ID, null, 10))
					.anyMatch(card -> card.sharedCardId().equals(transitionedCardId));
		}
	}

	@Test
	void resetModeRestoresTheFixtureAndTerminatesWithoutAWebContext() {
		PostgresTestDatabase.JDBC.update(
				"UPDATE wish SET purpose = 'reset 전 이름' WHERE id = ?", LAPTOP_WISH_ID);

		ConfigurableApplicationContext context = startDemo("reset");

		assertThat(context.isActive()).isFalse();
		assertThat(PostgresTestDatabase.JDBC.queryForObject(
				"SELECT purpose FROM wish WHERE id = ?", String.class, LAPTOP_WISH_ID))
				.isEqualTo("노트북");
	}

	@Test
	void unknownLifecycleFailsClosedBeforeTheApplicationBecomesReady() {
		assertThatThrownBy(() -> startDemo("unknown"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Unsupported crabit.demo.lifecycle: unknown");
	}

	@Test
	void resetRollsBackTheWholePriorStateWhenFixtureInsertionFails() {
		try (ConfigurableApplicationContext context = startDemo("serve")) {
			SeedFixtureService fixtures = context.getBean(SeedFixtureService.class);
			PostgresTestDatabase.JDBC.update(
					"UPDATE wish SET purpose = 'rollback 대상' WHERE id = ?", LAPTOP_WISH_ID);
			PostgresTestDatabase.JDBC.execute("""
					CREATE OR REPLACE FUNCTION fail_demo_fixture_insert()
					RETURNS trigger LANGUAGE plpgsql AS $$
					BEGIN
					    RAISE EXCEPTION 'injected reset failure';
					END
					$$
					""");
			PostgresTestDatabase.JDBC.execute("""
					CREATE TRIGGER fail_demo_fixture_insert
					BEFORE INSERT ON wish
					FOR EACH ROW EXECUTE FUNCTION fail_demo_fixture_insert()
					""");
			try {
				assertThatThrownBy(fixtures::resetAndInitialize)
						.hasStackTraceContaining("injected reset failure");
				assertThat(PostgresTestDatabase.JDBC.queryForObject(
						"SELECT purpose FROM wish WHERE id = ?", String.class, LAPTOP_WISH_ID))
						.isEqualTo("rollback 대상");
			} finally {
				PostgresTestDatabase.JDBC.execute(
						"DROP TRIGGER IF EXISTS fail_demo_fixture_insert ON wish");
				PostgresTestDatabase.JDBC.execute(
						"DROP FUNCTION IF EXISTS fail_demo_fixture_insert()");
			}
		}
	}

	@Test
	void concurrentResetInvocationsSerializeAndLeaveOneCanonicalGraph() {
		try (ConfigurableApplicationContext context = startDemo("serve");
				ExecutorService executor = Executors.newFixedThreadPool(2)) {
			SeedFixtureService fixtures = context.getBean(SeedFixtureService.class);
			CompletableFuture<Void> first = CompletableFuture.runAsync(
					fixtures::resetAndInitialize, executor);
			CompletableFuture<Void> second = CompletableFuture.runAsync(
					fixtures::resetAndInitialize, executor);

			CompletableFuture.allOf(first, second).join();

			assertThat(PostgresTestDatabase.JDBC.queryForObject(
					"SELECT count(*) FROM wish", Long.class)).isEqualTo(2);
			assertThat(PostgresTestDatabase.JDBC.queryForObject(
					"SELECT purpose FROM wish WHERE id = ?", String.class, LAPTOP_WISH_ID))
					.isEqualTo("노트북");
		}
	}

	private static ConfigurableApplicationContext startDemo(String lifecycle) {
		return new SpringApplicationBuilder(CrabitBackendApplication.class)
				.profiles("demo")
				.web(WebApplicationType.NONE)
				.run(
						"--spring.datasource.url=" + PostgresTestDatabase.URL,
						"--spring.datasource.username=test",
						"--spring.datasource.password=test",
						"--crabit.demo.lifecycle=" + lifecycle,
						"--crabit.demo.token.owner=demo-owner-secret",
						"--crabit.demo.token.friend=demo-friend-secret",
						"--crabit.demo.token.nonfriend=demo-nonfriend-secret",
						"--crabit.demo.token.blocked=demo-blocked-secret",
						"--crabit.demo.token.other-academy=demo-other-academy-secret",
						"--crabit.demo.token.staff=demo-staff-secret",
						"--crabit.demo.balance-provider.url="
								+ "https://console.example.test/api/provider/balance-lookups",
						"--crabit.demo.balance-provider.token="
								+ "demo-provider-machine-token-123456789");
	}
}
