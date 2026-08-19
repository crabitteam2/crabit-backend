package com.crabit.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.crabit.backend.CrabitBackendApplication;
import com.crabit.backend.balance.CardBalanceProvider;
import com.crabit.backend.balance.CardBalanceProviderResult;
import com.crabit.backend.balance.CardBalanceScriptControl;
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
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class DemoRuntimeIsolationTest {

	@Test
	void demoUsesTheOrdinaryUnavailableProviderWithoutScriptControl() throws Exception {
		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("demo");
			Class<?> unavailableProvider = Class.forName(
					"com.crabit.backend.balance.UnavailableCardBalanceProvider");
			context.register(DeterministicCardBalanceAdapter.class, unavailableProvider);
			context.refresh();

			assertThat(context.getBeansOfType(CardBalanceScriptControl.class)).isEmpty();
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
