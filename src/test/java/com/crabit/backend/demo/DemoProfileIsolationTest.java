package com.crabit.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

class DemoProfileIsolationTest {

	@Test
	void productionRegistersNoDemoAuthenticationBeans() {
		try (AnnotationConfigApplicationContext context = context("prod")) {
			assertThat(context.getBeansOfType(DemoProfileIsolation.class)).isEmpty();
			assertThat(context.getBeansOfType(DemoTokenRegistry.class)).isEmpty();
			assertThat(context.getBeansOfType(DemoBearerAuthenticationFilter.class)).isEmpty();
		}
	}

	@Test
	void demoRegistersTheServerOnlyAuthenticationBoundary() {
		try (AnnotationConfigApplicationContext context = context("demo")) {
			assertThat(context.getBeansOfType(DemoProfileIsolation.class)).hasSize(1);
			assertThat(context.getBeansOfType(DemoTokenRegistry.class)).hasSize(1);
			assertThat(context.getBeansOfType(DemoBearerAuthenticationFilter.class)).hasSize(1);
		}
	}

	@Test
	void jointDemoAndE2eActivationFailsClosedBeforeServingRequests() {
		AnnotationConfigApplicationContext context = unrefreshedContext("demo", "e2e");
		try {
			assertThatThrownBy(context::refresh)
					.isInstanceOf(IllegalStateException.class)
					.hasMessage("The demo and e2e profiles must not be active at the same time");
		} finally {
			context.close();
		}
	}

	private static AnnotationConfigApplicationContext context(String... profiles) {
		AnnotationConfigApplicationContext context = unrefreshedContext(profiles);
		context.refresh();
		return context;
	}

	private static AnnotationConfigApplicationContext unrefreshedContext(String... profiles) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().setActiveProfiles(profiles);
		TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
				"crabit.demo.token.owner=demo-owner-secret",
				"crabit.demo.token.friend=demo-friend-secret",
				"crabit.demo.token.nonfriend=demo-nonfriend-secret",
				"crabit.demo.token.blocked=demo-blocked-secret",
				"crabit.demo.token.other-academy=demo-other-academy-secret",
				"crabit.demo.token.staff=demo-staff-secret");
		context.register(DemoProfileIsolation.class, DemoTokenRegistry.class,
				DemoBearerAuthenticationFilter.class);
		return context;
	}
}
