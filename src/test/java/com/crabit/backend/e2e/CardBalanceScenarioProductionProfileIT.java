package com.crabit.backend.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class CardBalanceScenarioProductionProfileIT {

	@Test
	void productionContextContainsNeitherTheControllerBeanNorItsRouteMappings() {
		try (AnnotationConfigWebApplicationContext context =
				new AnnotationConfigWebApplicationContext()) {
			context.setServletContext(new MockServletContext());
			context.getEnvironment().setActiveProfiles("prod");
			context.register(WebConfiguration.class, CardBalanceScenarioController.class);
			context.refresh();

			assertThat(context.getBeansOfType(CardBalanceScenarioController.class)).isEmpty();
			RequestMappingHandlerMapping mappings =
					context.getBean(RequestMappingHandlerMapping.class);
			assertThat(mappings.getHandlerMethods().values())
					.extracting(HandlerMethod::getBeanType)
					.doesNotContain(CardBalanceScenarioController.class);
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	static class WebConfiguration {
	}
}
