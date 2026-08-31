package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RecommendationTriggerAuthenticationFilterTest {

	private final RecommendationHandoffSettings settings = new RecommendationHandoffSettings(
			"https://receiver.example.test/handoffs", "trigger-secret", "receiver-secret");
	private final RecommendationTriggerAuthenticationFilter filter =
			new RecommendationTriggerAuthenticationFilter(settings);

	@Test
	void acceptsOnlyTheExactTriggerCredentialOnEveryHandlerEquivalentOperation()
			throws Exception {
		for (MockHttpServletRequest accepted : new MockHttpServletRequest[] {
			request("POST", "/internal/v1/recommendation-handoffs", "Bearer trigger-secret"),
			request("POST", "/internal/v1/recommendation-handoffs;x=y",
					"Bearer trigger-secret"),
			contextPathRequest("POST", "/crabit/internal/v1/recommendation-handoffs",
					"/crabit", "Bearer trigger-secret"),
			contextPathRequest("POST",
					"/crabit/internal/v1/recommendation-handoffs;jsessionid=abc",
					"/crabit", "Bearer trigger-secret")
		}) {
			assertThat(invoke(accepted).getStatus()).isEqualTo(200);
		}

		for (String path : new String[] {
			"/internal/v1/recommendation-handoffs",
			"/internal/v1/recommendation-handoffs;x=y"
		}) {
			for (String credential : new String[] {
				null, "Bearer ", "Basic trigger-secret", "Bearer receiver-secret",
				"Bearer seed-owner-token", "Bearer trigger-secret "
			}) {
				MockHttpServletResponse rejected = invoke(request("POST", path, credential));
				assertThat(rejected.getStatus()).as(path + " " + credential).isEqualTo(401);
				assertThat(rejected.getHeader(HttpHeaders.WWW_AUTHENTICATE))
						.isEqualTo("Bearer");
				assertThat(rejected.getContentAsString()).contains("AUTH_REQUIRED");
			}
		}

		assertThat(invoke(request(
				"GET", "/internal/v1/recommendation-handoffs", null)).getStatus())
				.isEqualTo(200);
		assertThat(invoke(request(
				"POST", "/internal/v1/recommendation-handoffs/extra", null)).getStatus())
				.isEqualTo(200);
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

	private static MockHttpServletRequest contextPathRequest(
			String method, String path, String contextPath, String authorization) {
		MockHttpServletRequest request = request(method, path, authorization);
		request.setContextPath(contextPath);
		return request;
	}
}
