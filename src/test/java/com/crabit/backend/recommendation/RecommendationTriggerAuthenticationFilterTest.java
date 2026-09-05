package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RecommendationTriggerAuthenticationFilterTest {
	private static final String HISTORICAL_PATH =
			"/internal/v1/academies/11111111-1111-4111-8111-111111111111"
					+ "/students/22222222-2222-4222-8222-222222222222"
					+ "/card-balance-accounts/33333333-3333-4333-8333-333333333333"
					+ "/historical-balances";

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

	@Test
	void historicalGetAuthenticatesBeforeDispatchAndDisablesCachingWithAContextPath()
			throws Exception {
		for (String contextPath : new String[] {"", "/crabit"}) {
			MockHttpServletRequest request = contextPathRequest("GET",
					contextPath + HISTORICAL_PATH, contextPath, "Bearer trigger-secret");
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			assertThat(RecommendationHandoffOperationMatcher.matches(request)).isTrue();
			assertThat(RecommendationHandoffOperationMatcher.historical(request)).isTrue();
			filter.doFilter(request, response, (downstreamRequest, downstreamResponse) -> {
				assertThat(downstreamRequest.getAttribute("crabit.machine-behavior-authenticated"))
						.isEqualTo(Boolean.TRUE);
				assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
				chain.doFilter(downstreamRequest, downstreamResponse);
			});

			assertThat(chain.getRequest()).isSameAs(request);
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
		}
	}

	@Test
	void historicalGetRejectsMissingWrongAndDuplicateCredentialsWithoutDispatch()
			throws Exception {
		for (String contextPath : new String[] {"", "/crabit"}) {
			for (String authorization : new String[] {
				null, "", "Bearer ", "Basic trigger-secret", "bearer trigger-secret",
				"Bearer wrong-secret", "Bearer receiver-secret", "Bearer seed-owner-token",
				"Bearer demo-owner-token", "Bearer trigger-secret ",
				"Bearer trigger-secret, Bearer trigger-secret"
			}) {
				assertHistoricalUnauthorized(contextPathRequest("GET", contextPath + HISTORICAL_PATH,
						contextPath, authorization));
			}
			for (String secondValue : new String[] {"Bearer trigger-secret", "Bearer wrong-secret"}) {
				MockHttpServletRequest duplicate = contextPathRequest("GET",
						contextPath + HISTORICAL_PATH, contextPath, "Bearer trigger-secret");
				duplicate.addHeader(HttpHeaders.AUTHORIZATION, secondValue);
				assertHistoricalUnauthorized(duplicate);
			}
		}
	}

	@Test
	void historicalLookalikesAndWrongMethodsNeverReceiveTheMachineBypass() throws Exception {
		for (String contextPath : new String[] {"", "/crabit"}) {
			for (String method : new String[] {"HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}) {
				assertNotMachineOperation(contextPathRequest(method, contextPath + HISTORICAL_PATH,
						contextPath, "Bearer trigger-secret"));
			}
			for (String path : new String[] {
				HISTORICAL_PATH + "/extra",
				HISTORICAL_PATH + "/",
				HISTORICAL_PATH + ";x=y",
				HISTORICAL_PATH.replace("/students/", ";x=y/students/"),
				HISTORICAL_PATH.replace("/students/", "//students/"),
				HISTORICAL_PATH.replace("historical-balances", "%68istorical-balances"),
				HISTORICAL_PATH.replace("historical-balances", "historical%2Dbalances"),
				HISTORICAL_PATH.replace("/students/", "%2Fstudents/"),
				HISTORICAL_PATH.replace("/card-balance-accounts/", "/card-balance-account/"),
				"/internal/v1/historical-balances"
			}) {
				assertNotMachineOperation(contextPathRequest("GET", contextPath + path,
						contextPath, "Bearer trigger-secret"));
			}
		}
	}

	@Test
	void historyClassificationPreservesTheLegacyHandoffAndThreeMetricsRoutes() throws Exception {
		String metricsBase = "/internal/v1/academies/11111111-1111-4111-8111-111111111111/behavior-metrics";
		for (String contextPath : new String[] {"", "/crabit"}) {
			for (String path : new String[] {
				metricsBase + "/feed",
				metricsBase + "/students/22222222-2222-4222-8222-222222222222/profile-visits",
				metricsBase + "/students/22222222-2222-4222-8222-222222222222"
						+ "/author-interest/33333333-3333-4333-8333-333333333333"
			}) {
				assertLegacyMachineOperation(contextPathRequest("GET", contextPath + path,
						contextPath, "Bearer trigger-secret"));
			}
			for (String suffix : new String[] {"", ";jsessionid=abc"}) {
				assertLegacyMachineOperation(contextPathRequest("POST",
						contextPath + "/internal/v1/recommendation-handoffs" + suffix,
						contextPath, "Bearer trigger-secret"));
			}
		}
	}

	private void assertHistoricalUnauthorized(MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		assertThat(response.getStatus()).as(request.getRequestURI()).isEqualTo(401);
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
		assertThat(response.getContentAsString()).contains("\"code\":\"AUTH_REQUIRED\"", "\"retryable\":false");
		assertThat(request.getAttribute("crabit.machine-behavior-authenticated")).isNull();
		assertThat(chain.getRequest()).isNull();
	}

	private void assertNotMachineOperation(MockHttpServletRequest request) throws Exception {
		String operation = request.getMethod() + " " + request.getRequestURI();
		assertThat(RecommendationHandoffOperationMatcher.matches(request)).as(operation).isFalse();
		assertThat(RecommendationHandoffOperationMatcher.historical(request)).as(operation).isFalse();
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(request.getAttribute("crabit.machine-behavior-authenticated")).as(operation).isNull();
		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
	}

	private void assertLegacyMachineOperation(MockHttpServletRequest request) throws Exception {
		assertThat(RecommendationHandoffOperationMatcher.matches(request)).isTrue();
		assertThat(RecommendationHandoffOperationMatcher.historical(request)).isFalse();
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(request.getAttribute("crabit.machine-behavior-authenticated")).isEqualTo(Boolean.TRUE);
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
