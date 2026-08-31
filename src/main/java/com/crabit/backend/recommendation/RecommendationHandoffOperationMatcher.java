package com.crabit.backend.recommendation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.RequestPath;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

public final class RecommendationHandoffOperationMatcher {

	private static final PathPattern PATH = PathPatternParser.defaultInstance.parse(
			"/internal/v1/recommendation-handoffs");

	private RecommendationHandoffOperationMatcher() {
	}

	public static boolean matches(HttpServletRequest request) {
		if (!"POST".equals(request.getMethod())) {
			return false;
		}
		RequestPath requestPath = ServletRequestPathUtils.parseAndCache(request);
		return PATH.matches(requestPath.pathWithinApplication());
	}
}
