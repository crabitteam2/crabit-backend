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

    private static final java.util.List<PathPattern> METRICS = java.util.List.of(
        "/internal/v1/academies/{academyId}/behavior-metrics/students/{studentId}/profile-visits",
        "/internal/v1/academies/{academyId}/behavior-metrics/students/{studentId}/author-interest/{authorStudentId}",
        "/internal/v1/academies/{academyId}/behavior-metrics/feed"
    ).stream().map(PathPatternParser.defaultInstance::parse).toList();

    public static boolean matches(HttpServletRequest request) {
        // Reject encodings and matrix parameters before Spring path normalization can widen a bypass.
        String raw=request.getRequestURI();
        RequestPath requestPath=ServletRequestPathUtils.parseAndCache(request);
        var path=requestPath.pathWithinApplication();
        if ("POST".equals(request.getMethod())&&PATH.matches(path)) return true;
        if(raw.contains(";")||raw.contains("%")||raw.contains("//"))return false;
        return "GET".equals(request.getMethod())&&METRICS.stream().anyMatch(p->p.matches(path));
    }
}
