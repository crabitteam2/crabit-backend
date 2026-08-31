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
	void acceptsOnlyTheExactTriggerCredentialOnTheExactOperation() throws Exception {
		MockHttpServletRequest accepted = request(
				"POST", "/internal/v1/recommendation-handoffs", "Bearer trigger-secret");
		assertThat(invoke(accepted).getStatus()).isEqualTo(200);

		for (String credential : new String[] {
			null, "Bearer ", "Basic trigger-secret", "Bearer receiver-secret",
			"Bearer seed-owner-token", "Bearer trigger-secret "
		}) {
			MockHttpServletResponse rejected = invoke(request(
					"POST", "/internal/v1/recommendation-handoffs", credential));
			assertThat(rejected.getStatus()).as(String.valueOf(credential)).isEqualTo(401);
			assertThat(rejected.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
			assertThat(rejected.getContentAsString()).contains("AUTH_REQUIRED");
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
}
