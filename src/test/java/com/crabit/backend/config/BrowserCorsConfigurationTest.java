package com.crabit.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.demo.DemoBearerAuthenticationFilter;
import com.crabit.backend.demo.DemoTokenRegistry;
import com.crabit.backend.e2e.SeedBearerAuthenticationFilter;
import com.crabit.backend.e2e.SeedFixtureCatalog;
import com.crabit.backend.e2e.SeedTokenRegistry;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

class BrowserCorsConfigurationTest {

	private static final String ORIGIN = "https://frontend.example";

	@Test
	void registersOneHighestPrecedenceApiFilterOnlyForE2eAndDemo() {
		for (String profile : List.of("e2e", "demo")) {
			try (AnnotationConfigApplicationContext context = context(profile)) {
				FilterRegistrationBean<?> registration = registration(context);

				assertThat(registration.getFilter()).isInstanceOf(CorsFilter.class);
				assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
				assertThat(registration.getUrlPatterns()).containsExactly("/v1/*");
			}
		}

		for (String profile : List.of("default", "prod")) {
			try (AnnotationConfigApplicationContext context = context(profile)) {
				assertThat(context.getBeansOfType(FilterRegistrationBean.class)).isEmpty();
			}
		}
	}

	@Test
	void acceptsEveryApprovedPreflightBeforeBearerAuthentication() throws Exception {
		for (AuthenticationScenario scenario : authenticationScenarios()) {
			for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
				MockHttpServletRequest request = preflight(method,
						"Authorization, Content-Type, Idempotency-Key, If-Match");

				MockHttpServletResponse response = invoke(request, scenario.filter());

				assertThat(response.getStatus()).as(scenario.profile() + " " + method)
						.isEqualTo(200);
				assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
						.as(scenario.profile() + " " + method).isEqualTo("*");
				assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
						.as(scenario.profile() + " " + method).contains(method);
				assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
						.as(scenario.profile() + " " + method)
						.containsIgnoringCase("Authorization")
						.containsIgnoringCase("Content-Type")
						.containsIgnoringCase("Idempotency-Key")
						.containsIgnoringCase("If-Match");
				assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
						.as(scenario.profile() + " " + method).isNull();
			}
		}
	}

	@Test
	void rejectsUnsupportedPreflightMethodsAndHeadersBeforeAuthentication() throws Exception {
		for (AuthenticationScenario scenario : authenticationScenarios()) {
			for (MockHttpServletRequest request : List.of(
					preflight("TRACE", "Authorization"),
					preflight("POST", "X-Unapproved-Header"))) {
				MockHttpServletResponse response = invoke(request, scenario.filter());

				assertThat(response.getStatus()).as(scenario.profile()).isEqualTo(403);
				assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
			}
		}
	}

	@Test
	void keepsActualBrowserRequestsBehindExistingBearerAuthentication() throws Exception {
		for (AuthenticationScenario scenario : authenticationScenarios()) {
			MockHttpServletRequest valid = actual(scenario.validAuthorization());
			MockHttpServletResponse validResponse = invoke(valid, scenario.filter());
			assertThat(validResponse.getStatus()).as(scenario.profile()).isEqualTo(200);
			assertThat(validResponse.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
					.isEqualTo("*");
			assertThat(valid.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE))
					.isInstanceOf(CurrentPrincipal.class);

			MockHttpServletResponse missing = invoke(actual(null), scenario.filter());
			assertThat(missing.getStatus()).as(scenario.profile()).isEqualTo(401);
			assertThat(missing.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
			assertThat(missing.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("*");

			MockHttpServletResponse staff = invoke(
					actual(scenario.staffAuthorization()), scenario.filter());
			assertThat(staff.getStatus()).as(scenario.profile()).isEqualTo(403);
			assertThat(staff.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("*");
		}
	}

	private static AnnotationConfigApplicationContext context(String profile) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().setActiveProfiles(profile);
		context.register(BrowserCorsConfiguration.class);
		context.refresh();
		return context;
	}

	private static FilterRegistrationBean<?> registration(
			AnnotationConfigApplicationContext context) {
		var registrations = context.getBeansOfType(FilterRegistrationBean.class);
		assertThat(registrations).hasSize(1);
		return registrations.values().iterator().next();
	}

	private static MockHttpServletRequest preflight(String method, String headers) {
		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/v1/probe");
		request.addHeader(HttpHeaders.ORIGIN, ORIGIN);
		request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
		request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers);
		return request;
	}

	private static MockHttpServletRequest actual(String authorization) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/probe");
		request.addHeader(HttpHeaders.ORIGIN, ORIGIN);
		if (authorization != null) {
			request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
		}
		return request;
	}

	private static List<AuthenticationScenario> authenticationScenarios() {
		List<String> demoTokens = List.of(
				"demo-owner-secret", "demo-friend-secret", "demo-nonfriend-secret",
				"demo-blocked-secret", "demo-other-academy-secret", "demo-staff-secret");
		DemoTokenRegistry demoTokensRegistry = new DemoTokenRegistry(
				demoTokens.get(0), demoTokens.get(1), demoTokens.get(2),
				demoTokens.get(3), demoTokens.get(4), demoTokens.get(5));
		return List.of(
				new AuthenticationScenario(
						"e2e",
						new SeedBearerAuthenticationFilter(
								new SeedTokenRegistry(new SeedFixtureCatalog())),
						"Bearer " + SeedFixtureCatalog.OWNER_TOKEN,
						"Bearer " + SeedFixtureCatalog.STAFF_TOKEN),
				new AuthenticationScenario(
						"demo",
						new DemoBearerAuthenticationFilter(demoTokensRegistry),
						"Bearer " + demoTokens.get(0),
						"Bearer " + demoTokens.get(5)));
	}

	private static MockHttpServletResponse invoke(
			MockHttpServletRequest request,
			Filter authentication) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		corsFilter().doFilter(
				request,
				response,
				(servletRequest, servletResponse) -> authentication.doFilter(
						servletRequest, servletResponse, new MockFilterChain()));
		return response;
	}

	private static CorsFilter corsFilter() {
		FilterRegistrationBean<?> registration =
				new BrowserCorsConfiguration().browserCorsFilter();
		return (CorsFilter) registration.getFilter();
	}

	private record AuthenticationScenario(
			String profile,
			Filter filter,
			String validAuthorization,
			String staffAuthorization) {
	}
}
