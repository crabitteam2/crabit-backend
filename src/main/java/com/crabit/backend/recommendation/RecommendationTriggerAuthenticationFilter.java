package com.crabit.backend.recommendation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnProperty(
		name = "crabit.recommendation.handoff.enabled", havingValue = "true")
final class RecommendationTriggerAuthenticationFilter extends OncePerRequestFilter {

	private final byte[] expectedAuthorization;

	RecommendationTriggerAuthenticationFilter(RecommendationHandoffSettings settings) {
		this.expectedAuthorization = ("Bearer " + settings.triggerCredential())
				.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !RecommendationHandoffOperationMatcher.matches(request);
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		List<String> values = Collections.list(
				request.getHeaders(HttpHeaders.AUTHORIZATION));
		if (values.size() != 1 || !matches(values.getFirst())) {
			unauthorized(response);
			return;
		}
		request.setAttribute("crabit.machine-behavior-authenticated", Boolean.TRUE);
		filterChain.doFilter(request, response);
	}

	private boolean matches(String value) {
		return value != null && MessageDigest.isEqual(
				expectedAuthorization, value.getBytes(StandardCharsets.UTF_8));
	}

	private static void unauthorized(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		response.getWriter().write("{\"error\":{\"code\":\"AUTH_REQUIRED\","
				+ "\"message\":\"A valid recommendation trigger Bearer credential is required.\","
				+ "\"retryable\":false,\"traceId\":\"" + UUID.randomUUID()
				+ "\",\"fieldErrors\":[],\"details\":{}}}");
	}
}
