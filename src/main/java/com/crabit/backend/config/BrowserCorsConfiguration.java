package com.crabit.backend.config;

import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods = false)
@Profile({"e2e", "demo"})
public class BrowserCorsConfiguration {

	@Bean
	FilterRegistrationBean<CorsFilter> browserCorsFilter() {
		CorsConfiguration policy = new CorsConfiguration();
		policy.setAllowedOrigins(List.of(CorsConfiguration.ALL));
		policy.setAllowCredentials(false);
		policy.setAllowedMethods(List.of(
				"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		policy.setAllowedHeaders(List.of(
				"Authorization", "Content-Type", "Idempotency-Key", "If-Match"));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/v1/**", policy);

		FilterRegistrationBean<CorsFilter> registration =
				new FilterRegistrationBean<>(new CorsFilter(source));
		registration.setName("browserCorsFilter");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		registration.addUrlPatterns("/v1/*");
		return registration;
	}
}
