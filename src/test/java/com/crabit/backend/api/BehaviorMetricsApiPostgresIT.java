package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

@SpringBootTest(
        properties = {
            "spring.main.banner-mode=off",
            "logging.level.root=warn",
            "crabit.recommendation.handoff.enabled=true",
            "crabit.recommendation.handoff.trigger-credential=behavior-trigger",
            "crabit.recommendation.handoff.receiver-credential=behavior-receiver",
            "crabit.recommendation.handoff.receiver-url=http://127.0.0.1:1/unused",
            "crabit.wish-photo.cleanup-delay-ms=3600000"
        })
class BehaviorMetricsApiPostgresIT extends WishApiIntegrationSupport {
    static final String BASE = "/internal/v1/academies/" + PRIMARY_ACADEMY_ID + "/behavior-metrics";

    @Test
    void enabledMachineRoutesExposeCanonicalDocumentationAndLiveNullableCoverage()
            throws Exception {
        String body =
                mockMvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        Map<String, Object> paths = JsonPath.read(body, "$.paths");
        assertThat(paths)
                .containsKeys(
                        "/internal/v1/academies/{academyId}/behavior-metrics/feed",
                        "/internal/v1/academies/{academyId}/behavior-metrics/students/{studentId}/profile-visits",
                        "/internal/v1/academies/{academyId}/behavior-metrics/students/{studentId}/author-interest/{authorStudentId}");
        mockMvc.perform(
                        get(BASE + "/students/" + OWNER_ID + "/profile-visits")
                                .header("Authorization", "Bearer behavior-trigger")
                                .param("fromDate", "2025-01-01")
                                .param("toDate", "2025-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.status").value("NONE"))
                .andExpect(jsonPath("$.visitCount").value(org.hamcrest.Matchers.nullValue()));
        asOwner(get(BASE + "/feed").param("fromDate", "2026-08-18").param("toDate", "2026-08-19"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(
                        get(BASE + "/feed")
                                .header("Authorization", "Bearer behavior-trigger")
                                .param("fromDate", "2026-08-18")
                                .param("toDate", "2026-08-20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
    }
}
