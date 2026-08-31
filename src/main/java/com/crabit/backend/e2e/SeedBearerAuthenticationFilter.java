package com.crabit.backend.e2e;

import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.recommendation.RecommendationHandoffOperationMatcher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("e2e")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class SeedBearerAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final Set<String> SCENARIO_METHODS = Set.of("GET", "PUT", "DELETE");
	private static final Pattern SCENARIO_PATH = Pattern.compile(
			"/e2e/card-balance-accounts/[^/]+/balance-scenario");

	private final SeedTokenRegistry tokens;

	@Value("${crabit.recommendation.handoff.enabled:false}")
	private boolean recommendationHandoffEnabled;

	public SeedBearerAuthenticationFilter(SeedTokenRegistry tokens) {
		this.tokens = tokens;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return isOperationalProbe(request)
				|| recommendationHandoffEnabled && isRecommendationHandoff(request)
				|| path.equals("/swagger-ui.html")
				|| path.startsWith("/swagger-ui/")
				|| path.equals("/v3/api-docs")
				|| path.equals("/v3/api-docs.yaml")
				|| path.startsWith("/v3/api-docs/");
	}

	private static boolean isRecommendationHandoff(HttpServletRequest request) {
		return RecommendationHandoffOperationMatcher.matches(request);
	}

	private static boolean isOperationalProbe(HttpServletRequest request) {
		if (!"GET".equals(request.getMethod())
				|| request.getQueryString() != null
				|| request.getContentLengthLong() > 0
				|| request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
			return false;
		}
		String path = request.getRequestURI();
		String contextPath = request.getContextPath();
		if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
			path = path.substring(contextPath.length());
		}
		return path.equals("/actuator/health/liveness")
				|| path.equals("/actuator/health/readiness");
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (isScenarioControlRequest(request)) {
			filterChain.doFilter(request, response);
			return;
		}
		Optional<SeedPrincipal> principal = resolve(request.getHeader(HttpHeaders.AUTHORIZATION));
		if (principal.isEmpty()) {
			writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED",
					"A known Bearer token is required.", true);
			return;
		}

		SeedPrincipal authenticated = principal.orElseThrow();
		if (request.getRequestURI().startsWith("/v1/")
				&& authenticated.role() != SeedPrincipal.Role.STUDENT) {
			writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
					"The authenticated principal is not a student.", false);
			return;
		}

		request.setAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE, new CurrentPrincipal(
				authenticated.subjectId(),
				authenticated.role() == SeedPrincipal.Role.STUDENT
						? CurrentPrincipal.Role.STUDENT
						: CurrentPrincipal.Role.STAFF,
				authenticated.academyId(),
				authenticated.personaKey()));
		filterChain.doFilter(request, response);
	}

	private static boolean isScenarioControlRequest(HttpServletRequest request) {
		return SCENARIO_METHODS.contains(request.getMethod())
				&& SCENARIO_PATH.matcher(request.getRequestURI()).matches();
	}

	private Optional<SeedPrincipal> resolve(String authorization) {
		if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
			return Optional.empty();
		}
		String token = authorization.substring(BEARER_PREFIX.length());
		if (token.isBlank()) {
			return Optional.empty();
		}
		return tokens.resolve(token);
	}

	private static void writeError(
			HttpServletResponse response,
			int status,
			String code,
			String message,
			boolean authenticate) throws IOException {
		response.setStatus(status);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		if (authenticate) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
		String traceId = UUID.randomUUID().toString();
		response.getWriter().write("{\"error\":{\"code\":\"" + code
				+ "\",\"message\":\"" + message
				+ "\",\"retryable\":false,\"traceId\":\"" + traceId
				+ "\",\"fieldErrors\":[],\"details\":{}}}");
	}
}
