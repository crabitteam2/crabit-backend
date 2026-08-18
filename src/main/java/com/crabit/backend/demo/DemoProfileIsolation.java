package com.crabit.backend.demo;

import java.util.Arrays;
import java.util.Objects;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public final class DemoProfileIsolation implements BeanFactoryPostProcessor, EnvironmentAware {

	private Environment environment;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
			throws BeansException {
		if (Arrays.asList(environment.getActiveProfiles()).contains("e2e")) {
			throw new IllegalStateException(
					"The demo and e2e profiles must not be active at the same time");
		}
	}
}
