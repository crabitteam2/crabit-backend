package com.crabit.backend.demo;

import com.crabit.backend.auth.CurrentPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("demo & !e2e")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class DemoBearerAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final DemoTokenRegistry tokens;

	public DemoBearerAuthenticationFilter(DemoTokenRegistry tokens) {
		this.tokens = tokens;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return path.equals("/swagger-ui.html")
				|| path.startsWith("/swagger-ui/")
				|| path.equals("/v3/api-docs")
				|| path.equals("/v3/api-docs.yaml")
				|| path.startsWith("/v3/api-docs/");
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		Optional<CurrentPrincipal> principal = resolve(
				request.getHeader(HttpHeaders.AUTHORIZATION));
		if (principal.isEmpty()) {
			writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED",
					"A known Bearer token is required.", true);
			return;
		}

		CurrentPrincipal authenticated = principal.orElseThrow();
		if (request.getRequestURI().startsWith("/v1/")
				&& authenticated.role() != CurrentPrincipal.Role.STUDENT) {
			writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
					"The authenticated principal is not a student.", false);
			return;
		}

		request.setAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE, authenticated);
		filterChain.doFilter(request, response);
	}

	private Optional<CurrentPrincipal> resolve(String authorization) {
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
