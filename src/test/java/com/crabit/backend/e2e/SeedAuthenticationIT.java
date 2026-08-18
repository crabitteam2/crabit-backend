package com.crabit.backend.e2e;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SeedAuthenticationIT {

	private SeedBearerAuthenticationFilter filter;

	@BeforeEach
	void setUp() {
		filter = new SeedBearerAuthenticationFilter(
				new SeedTokenRegistry(new SeedFixtureCatalog()));
	}

	@Test
	void mapsKnownTokensAndRejectsMissingUnknownAndStaffPrincipals() throws Exception {
		MockHttpServletRequest owner = request(
				"GET", "/v1/probe", "Bearer " + SeedFixtureCatalog.OWNER_TOKEN);
		MockHttpServletResponse ownerResponse = new MockHttpServletResponse();
		filter.doFilter(owner, ownerResponse, new MockFilterChain());
		assertThat(ownerResponse.getStatus()).isEqualTo(200);
		assertThat((SeedPrincipal) owner.getAttribute(SeedPrincipal.REQUEST_ATTRIBUTE))
				.extracting(SeedPrincipal::subjectId, SeedPrincipal::role)
				.containsExactly(OWNER_ID, SeedPrincipal.Role.STUDENT);

		MockHttpServletResponse missing = invoke(request("GET", "/v1/probe", null));
		assertThat(missing.getStatus()).isEqualTo(401);
		assertThat(missing.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		assertThat(missing.getContentAsString()).contains("AUTH_REQUIRED");

		MockHttpServletResponse unknown = invoke(
				request("GET", "/v1/probe", "Bearer unknown"));
		assertThat(unknown.getStatus()).isEqualTo(401);

		MockHttpServletResponse staff = invoke(
				request("GET", "/v1/probe", "Bearer " + SeedFixtureCatalog.STAFF_TOKEN));
		assertThat(staff.getStatus()).isEqualTo(403);
		assertThat(staff.getContentAsString()).contains("FORBIDDEN");
	}

	@Test
<<<<<<< HEAD
	void bypassesOnlyTheExactScenarioRouteAndItsThreeContractMethods() throws Exception {
		String route = "/e2e/card-balance-accounts/"
				+ "11111111-1111-4111-8111-111111111111/balance-scenario";

		assertThat(invoke(request("GET", route, null)).getStatus()).isEqualTo(200);
		assertThat(invoke(request("PUT", route, "Bearer unknown")).getStatus()).isEqualTo(200);
		assertThat(invoke(request(
				"DELETE", route, "Bearer " + SeedFixtureCatalog.STAFF_TOKEN)).getStatus())
				.isEqualTo(200);

		assertThat(invoke(request("POST", route, null)).getStatus()).isEqualTo(401);
		assertThat(invoke(request("GET", route + "/extra", null)).getStatus()).isEqualTo(401);
		assertThat(invoke(request(
				"GET", "/v1/probe", "Bearer " + SeedFixtureCatalog.STAFF_TOKEN)).getStatus())
				.isEqualTo(403);
=======
	void letsEveryApprovedDocumentationPathBypassBearerAuthentication() throws Exception {
		for (String path : new String[] {
				"/swagger-ui.html",
				"/swagger-ui/index.html",
				"/swagger-ui/swagger-ui.css",
				"/v3/api-docs",
				"/v3/api-docs.yaml",
				"/v3/api-docs/swagger-config",
				"/v3/api-docs/wishes"
		}) {
			MockHttpServletResponse response = invoke(request(path, null));
			assertThat(response.getStatus()).as(path).isEqualTo(200);
		}

		for (String path : new String[] {
				"/openapi/openapi.yaml",
				"/swagger-ui-malicious",
				"/v3/api-docs-malicious",
				"/v1/probe"
		}) {
			MockHttpServletResponse response = invoke(request(path, null));
			assertThat(response.getStatus()).as(path).isEqualTo(401);
			assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).as(path).isEqualTo("Bearer");
		}
>>>>>>> origin/main
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
}
