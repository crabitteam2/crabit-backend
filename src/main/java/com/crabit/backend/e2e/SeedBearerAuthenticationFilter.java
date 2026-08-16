package com.crabit.backend.e2e;

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
@Profile("e2e")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class SeedBearerAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final SeedTokenRegistry tokens;

	public SeedBearerAuthenticationFilter(SeedTokenRegistry tokens) {
		this.tokens = tokens;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return path.startsWith("/swagger-ui/")
				|| path.equals("/v3/api-docs/swagger-config")
				|| path.equals("/openapi/openapi.yaml");
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
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

		request.setAttribute(SeedPrincipal.REQUEST_ATTRIBUTE, authenticated);
		filterChain.doFilter(request, response);
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
