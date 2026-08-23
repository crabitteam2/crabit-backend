package com.crabit.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.crabit.backend.CrabitBackendApplication;
import com.crabit.backend.balance.CardBalanceProvider;
import com.crabit.backend.balance.CardBalanceProviderResult;
import com.crabit.backend.balance.CardBalanceScriptControl;
import com.crabit.backend.balance.DemoHttpCardBalanceProvider;
import com.crabit.backend.balance.DeterministicCardBalanceAdapter;
import com.crabit.backend.e2e.CardBalanceScenarioController;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;

class DemoRuntimeIsolationTest {

	@Test
	void demoUsesOnlyTheHttpProviderWithoutScriptControl() throws Exception {
		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("demo");
			Class<?> unavailableProvider = Class.forName(
					"com.crabit.backend.balance.UnavailableCardBalanceProvider");
			Class<?> settings = Class.forName(
					"com.crabit.backend.balance.DemoBalanceProviderSettings");
			TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
					"crabit.demo.balance-provider.url="
							+ "https://console.example.test/api/provider/balance-lookups",
					"crabit.demo.balance-provider.token="
							+ "demo-provider-machine-token-123456789");
			context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
			context.register(DeterministicCardBalanceAdapter.class, unavailableProvider,
					settings, DemoHttpCardBalanceProvider.class);
			context.refresh();

			assertThat(context.getBeansOfType(CardBalanceScriptControl.class)).isEmpty();
			assertThat(context.getBeansOfType(CardBalanceProvider.class)).hasSize(1);
			assertThat(context.getBean(CardBalanceProvider.class))
					.isExactlyInstanceOf(DemoHttpCardBalanceProvider.class);
		}
	}

	@Test
	void e2eUsesOnlyTheDeterministicProviderAndScriptControl() throws Exception {
		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("e2e");
			Class<?> unavailableProvider = Class.forName(
					"com.crabit.backend.balance.UnavailableCardBalanceProvider");
			Class<?> settings = Class.forName(
					"com.crabit.backend.balance.DemoBalanceProviderSettings");
			context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
			context.register(DeterministicCardBalanceAdapter.class, unavailableProvider,
					settings, DemoHttpCardBalanceProvider.class);
			context.refresh();

			assertThat(context.getBeansOfType(CardBalanceProvider.class)).hasSize(1);
			assertThat(context.getBean(CardBalanceProvider.class))
					.isExactlyInstanceOf(DeterministicCardBalanceAdapter.class);
			assertThat(context.getBeansOfType(CardBalanceScriptControl.class)).hasSize(1);
		}
	}

	@Test
	void ordinaryProfilesKeepOnlyTheUnavailableProvider() throws Exception {
		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("prod");
			Class<?> unavailableProvider = Class.forName(
					"com.crabit.backend.balance.UnavailableCardBalanceProvider");
			Class<?> settings = Class.forName(
					"com.crabit.backend.balance.DemoBalanceProviderSettings");
			context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
			context.register(DeterministicCardBalanceAdapter.class, unavailableProvider,
					settings, DemoHttpCardBalanceProvider.class);
			context.refresh();

			assertThat(context.getBeansOfType(CardBalanceProvider.class)).hasSize(1);
			CardBalanceProvider provider = context.getBean(CardBalanceProvider.class);
			assertThat(provider.getClass().getSimpleName()).isEqualTo("UnavailableCardBalanceProvider");
			assertThat(provider.lookup(UUID.randomUUID()))
					.isEqualTo(new CardBalanceProviderResult.Failure());
		}
	}

	@Test
	void demoHasNoE2eScenarioControllerOrRoute() {
		try (AnnotationConfigWebApplicationContext context =
				new AnnotationConfigWebApplicationContext()) {
			context.setServletContext(new MockServletContext());
			context.getEnvironment().setActiveProfiles("demo");
			context.register(WebConfiguration.class, CardBalanceScenarioController.class);
			context.refresh();

			assertThat(context.getBeansOfType(CardBalanceScenarioController.class)).isEmpty();
			RequestMappingHandlerMapping mappings = context.getBean(RequestMappingHandlerMapping.class);
			assertThat(mappings.getHandlerMethods().values())
					.extracting(HandlerMethod::getBeanType)
					.doesNotContain(CardBalanceScenarioController.class);
		}
	}

	@Test
	void theNonE2eClockBoundaryIsSystemUtcAndNotFixed() throws Exception {
		Method factory = CrabitBackendApplication.class.getDeclaredMethod("systemClock");
		Profile profile = factory.getAnnotation(Profile.class);
		assertThat(profile.value()).containsExactly("!e2e");

		factory.setAccessible(true);
		Instant before = Instant.now().minusSeconds(1);
		Clock clock = (Clock) factory.invoke(new CrabitBackendApplication());
		Instant after = Instant.now().plusSeconds(1);

		assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
		assertThat(clock.instant()).isBetween(before, after);
		assertThat(clock.getClass().getSimpleName()).isEqualTo("SystemClock");
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	static class WebConfiguration {
	}
}
