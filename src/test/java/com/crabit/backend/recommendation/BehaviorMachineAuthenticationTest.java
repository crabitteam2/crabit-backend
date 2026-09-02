package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.crabit.backend.api.BehaviorMetricsController;
import com.crabit.backend.api.WishApiExceptionHandler;
import com.crabit.backend.behavior.BehaviorMetricsService;
import com.crabit.backend.config.BehaviorCacheControlFilter;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

class BehaviorMachineAuthenticationTest {
    private final BehaviorMetricsService metrics = mock(BehaviorMetricsService.class);
    private final RecommendationTriggerAuthenticationFilter auth =
            new RecommendationTriggerAuthenticationFilter(
                    new RecommendationHandoffSettings(
                            "https://receiver.example.test/handoffs",
                            "trigger-secret",
                            "receiver-secret"));
    private final org.springframework.test.web.servlet.MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new BehaviorMetricsController(metrics))
                    .setControllerAdvice(new WishApiExceptionHandler())
                    .addFilters(new BehaviorCacheControlFilter(), auth)
                    .build();
    private static final String BASE =
            "/internal/v1/academies/11111111-1111-4111-8111-111111111111/behavior-metrics";

    @Test
    void exactRoutesRequireOneMachineCredentialWithoutAnyStudentFilter() throws Exception {
        for (String path :
                new String[] {
                    BASE + "/feed",
                    BASE + "/students/22222222-2222-4222-8222-222222222222/profile-visits",
                    BASE
                            + "/students/22222222-2222-4222-8222-222222222222/author-interest/33333333-3333-4333-8333-333333333333"
                }) {
            for (String token :
                    new String[] {
                        "",
                        "Bearer seed-owner-token",
                        "Bearer receiver-secret",
                        "Bearer trigger-secret "
                    }) {
                mvc.perform(get(path).header("Authorization", token))
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string("WWW-Authenticate", "Bearer"))
                        .andExpect(header().string("Cache-Control", "no-store"));
            }
            mvc.perform(
                            get(path)
                                    .header(
                                            "Authorization",
                                            "Bearer trigger-secret",
                                            "Bearer trigger-secret"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(head(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path + ";x=y").header("Authorization", "Bearer trigger-secret"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(metrics);
        when(metrics.feed(any(), any(), any())).thenReturn(Map.of("schemaVersion", 1));
        mvc.perform(
                        get(BASE + "/feed")
                                .header("Authorization", "Bearer trigger-secret")
                                .param("fromDate", "2026-08-18")
                                .param("toDate", "2026-08-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1));
        verify(metrics).feed(any(), any(), any());
    }

    @Test
    void unknownRepeatedAndMissingParamsAreRejectedAfterAuthentication() throws Exception {
        for (var request :
                new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                    get(BASE + "/feed"),
                    get(BASE + "/feed")
                            .param("fromDate", "2026-08-18", "2026-08-19")
                            .param("toDate", "2026-08-20"),
                    get(BASE + "/feed")
                            .param("fromDate", "2026-08-18")
                            .param("toDate", "2026-08-19")
                            .param("unknown", "x")
                }) {
            mvc.perform(request.header("Authorization", "Bearer trigger-secret"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(metrics);
    }
}
