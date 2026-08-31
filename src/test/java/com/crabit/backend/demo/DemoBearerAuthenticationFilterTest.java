package com.crabit.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.crabit.backend.auth.CurrentPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class DemoBearerAuthenticationFilterTest {

	private DemoBearerAuthenticationFilter filter;

	@BeforeEach
	void setUp() {
		filter = new DemoBearerAuthenticationFilter(
				DemoTokenRegistryTest.registry(DemoTokenRegistryTest.tokens()));
	}

	@Test
	void authenticatesAConfiguredStudentAndRejectsAbsentMalformedAndUnknownCredentials()
			throws Exception {
		MockHttpServletRequest owner = request(
				"GET", "/v1/probe", "Bearer " + DemoTokenRegistryTest.tokens().get(0));
		MockHttpServletResponse ownerResponse = invoke(owner);

		assertThat(ownerResponse.getStatus()).isEqualTo(200);
		assertThat((CurrentPrincipal) owner.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE))
				.extracting(CurrentPrincipal::personaKey, CurrentPrincipal::role)
				.containsExactly("owner", CurrentPrincipal.Role.STUDENT);

		for (String authorization : new String[] {null, "Bearer ", "Basic credential", "Bearer unknown"}) {
			MockHttpServletResponse rejected = invoke(request("GET", "/v1/probe", authorization));
			assertThat(rejected.getStatus()).as(String.valueOf(authorization)).isEqualTo(401);
			assertThat(rejected.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
			assertThat(rejected.getContentAsString()).contains("AUTH_REQUIRED");
		}
	}

	@Test
	void returnsForbiddenForTheAuthenticatedStaffPersonaOnStudentApis() throws Exception {
		MockHttpServletResponse response = invoke(request(
				"GET", "/v1/probe", "Bearer " + DemoTokenRegistryTest.tokens().get(5)));

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
		assertThat(response.getContentAsString()).contains("FORBIDDEN");
	}

	@Test
	void neverTreatsAnE2eControlPathAsAnAuthenticationBypass() throws Exception {
		String controlPath = "/e2e/card-balance-accounts/"
				+ "11111111-1111-4111-8111-111111111111/balance-scenario";

		assertThat(invoke(request("GET", controlPath, null)).getStatus()).isEqualTo(401);
		assertThat(invoke(request("PUT", controlPath, "Bearer unknown")).getStatus()).isEqualTo(401);
		assertThat(invoke(request("DELETE", controlPath, null)).getStatus()).isEqualTo(401);
	}

	@Test
	void defersOnlyTheExactEnabledRecommendationTriggerToItsDedicatedFilter()
			throws Exception {
		ReflectionTestUtils.setField(filter, "recommendationHandoffEnabled", true);

		assertThat(invoke(request(
				"POST", "/internal/v1/recommendation-handoffs", null)).getStatus())
				.isEqualTo(200);
		assertThat(invoke(request(
				"GET", "/internal/v1/recommendation-handoffs", null)).getStatus())
				.isEqualTo(401);
		assertThat(invoke(request(
				"POST", "/internal/v1/recommendation-handoffs/extra", null)).getStatus())
				.isEqualTo(401);
	}

	@Test
	void leavesOnlyTheApprovedDocumentationPathsPublic() throws Exception {
		for (String path : new String[] {
			"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs",
			"/v3/api-docs.yaml", "/v3/api-docs/swagger-config"
		}) {
			assertThat(invoke(request("GET", path, null)).getStatus()).as(path).isEqualTo(200);
		}

		for (String path : new String[] {
			"/openapi/openapi.yaml", "/swagger-ui-malicious", "/v3/api-docs-malicious"
		}) {
			assertThat(invoke(request("GET", path, null)).getStatus()).as(path).isEqualTo(401);
		}
	}

	@Test
	void bypassesBearerOnlyForExactQueryFreeGetHealthProbes() throws Exception {
		for (String path : new String[] {
				"/actuator/health/liveness", "/actuator/health/readiness"
		}) {
			assertThat(invoke(request("GET", path, null)).getStatus()).as(path).isEqualTo(200);
		}

		for (MockHttpServletRequest request : new MockHttpServletRequest[] {
				request("POST", "/actuator/health/liveness", null),
				request("GET", "/actuator/health", null),
				request("GET", "/actuator/health/liveness/", null),
				requestWithQuery("/actuator/health/readiness"),
				requestWithBody("/actuator/health/liveness")
		}) {
			assertThat(invoke(request).getStatus()).isEqualTo(401);
		}
	}

	private MockHttpServletResponse invoke(MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}

	private static MockHttpServletRequest request(
			String method, String path, String authorization) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		if (authorization != null) {
			request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
		}
		return request;
	}

	private static MockHttpServletRequest requestWithQuery(String path) {
		MockHttpServletRequest request = request("GET", path, null);
		request.setQueryString("details=true");
		return request;
	}

	private static MockHttpServletRequest requestWithBody(String path) {
		MockHttpServletRequest request = request("GET", path, null);
		request.setContent("not-allowed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return request;
	}
}
