package com.crabit.backend.api;

import static com.crabit.backend.api.BehaviorRequestParser.*;

import com.crabit.backend.behavior.BehaviorMetricsService;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@ConditionalOnProperty(name = "crabit.recommendation.handoff.enabled", havingValue = "true")
@RequestMapping("/internal/v1/academies/{academyId}/behavior-metrics")
public class BehaviorMetricsController {
    private final BehaviorMetricsService metrics;

    public BehaviorMetricsController(BehaviorMetricsService metrics) {
        this.metrics = metrics;
    }

    private static void machine(HttpServletRequest request) {
        if (!Boolean.TRUE.equals(request.getAttribute("crabit.machine-behavior-authenticated")))
            throw new com.crabit.backend.wish.WishLifecycleException(
                    com.crabit.backend.wish.WishLifecycleException.Code.AUTH_REQUIRED,
                    "A valid machine credential is required.");
    }

    @GetMapping("/students/{studentId}/profile-visits")
    @Operation(operationId = "getIncomingProfileVisitMetrics")
    public Map<String, Object> profile(
            @PathVariable String academyId,
            @PathVariable String studentId,
            HttpServletRequest request) {
        machine(request);
        query(request, Set.of("fromDate", "toDate"));
        return metrics.profile(
                uuid(academyId),
                uuid(studentId),
                null,
                date(request, "fromDate"),
                date(request, "toDate"));
    }

    @GetMapping("/students/{studentId}/author-interest/{authorStudentId}")
    @Operation(operationId = "getOutgoingAuthorInterestMetrics")
    public Map<String, Object> author(
            @PathVariable String academyId,
            @PathVariable String studentId,
            @PathVariable String authorStudentId,
            HttpServletRequest request) {
        machine(request);
        query(request, Set.of("fromDate", "toDate"));
        return metrics.profile(
                uuid(academyId),
                uuid(studentId),
                uuid(authorStudentId),
                date(request, "fromDate"),
                date(request, "toDate"));
    }

    @GetMapping("/feed")
    @Operation(operationId = "getFeedBehaviorMetrics")
    public Map<String, Object> feed(@PathVariable String academyId, HttpServletRequest request) {
        machine(request);
        query(request, Set.of("fromDate", "toDate"));
        return metrics.feed(uuid(academyId), date(request, "fromDate"), date(request, "toDate"));
    }
}
