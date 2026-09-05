package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.crabit.backend.api.BehaviorMetricsController;
import com.crabit.backend.api.HistoricalBalanceController;
import com.crabit.backend.api.WishApiExceptionHandler;
import com.crabit.backend.behavior.BehaviorMetricsService;
import com.crabit.backend.config.BehaviorCacheControlFilter;
import com.crabit.backend.history.HistoricalBalanceQueryService;
import com.crabit.backend.history.HistoricalPeriods;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

class BehaviorMachineAuthenticationTest {
    private final BehaviorMetricsService metrics = mock(BehaviorMetricsService.class);
    private final HistoricalBalanceQueryService history = mock(HistoricalBalanceQueryService.class);
    private final RecommendationTriggerAuthenticationFilter auth =
            new RecommendationTriggerAuthenticationFilter(
                    new RecommendationHandoffSettings(
                            "https://receiver.example.test/handoffs",
                            "trigger-secret",
                            "receiver-secret"));
    private final org.springframework.test.web.servlet.MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new BehaviorMetricsController(metrics), new HistoricalBalanceController(history))
                    .setControllerAdvice(new WishApiExceptionHandler())
                    .addFilters(new BehaviorCacheControlFilter(), auth)
                    .build();
    private static final String BASE =
            "/internal/v1/academies/11111111-1111-4111-8111-111111111111/behavior-metrics";
    private static final String HISTORICAL_PATH =
            "/internal/v1/academies/11111111-1111-4111-8111-111111111111"
                    + "/students/22222222-2222-4222-8222-222222222222"
                    + "/card-balance-accounts/33333333-3333-4333-8333-333333333333"
                    + "/historical-balances";

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

    @Test
    void historicalEndpointRejectsCredentialsBeforeParsingOrCallingItsService() throws Exception {
        for (String contextPath : new String[] {"", "/crabit"}) {
            // Deliberately omit date parameters: authentication must fail before parsing them.
            mvc.perform(get(contextPath + HISTORICAL_PATH).contextPath(contextPath))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
                    .andExpect(jsonPath("$.error.retryable").value(false));
            for (String credential : new String[] {
                "", "Bearer seed-owner-token", "Bearer demo-owner-token", "Bearer receiver-secret",
                "Bearer wrong-secret", "Basic trigger-secret", "Bearer trigger-secret "
            }) {
                mvc.perform(get(contextPath + HISTORICAL_PATH).contextPath(contextPath)
                                .header("Authorization", credential))
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string("WWW-Authenticate", "Bearer"))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
            }
            mvc.perform(get(contextPath + HISTORICAL_PATH).contextPath(contextPath)
                            .header("Authorization", "Bearer trigger-secret", "Bearer trigger-secret"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        }
        verifyNoInteractions(history, metrics);
    }

    @Test
    void authenticatedHistoricalGetKeepsItsContextPathAndNoStoreThroughMvc() throws Exception {
        UUID academyId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID studentId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID accountId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 2);
        when(history.query(academyId, studentId, accountId, from, to,
                        HistoricalPeriods.Granularity.DAY, null))
                .thenReturn(Map.of("schemaVersion", 1, "cardBalanceAccountId", accountId.toString()));

        for (String contextPath : new String[] {"", "/crabit"}) {
            var result = mvc.perform(get(contextPath + HISTORICAL_PATH).contextPath(contextPath)
                            .header("Authorization", "Bearer trigger-secret")
                            .param("fromDate", "2026-09-01")
                            .param("toDateExclusive", "2026-09-02")
                            .param("granularity", "DAY"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.cardBalanceAccountId").value(accountId.toString()))
                    .andReturn();
            assertThat(result.getRequest().getAttribute("crabit.machine-behavior-authenticated"))
                    .isEqualTo(Boolean.TRUE);
        }
        verify(history, times(2)).query(academyId, studentId, accountId, from, to,
                HistoricalPeriods.Granularity.DAY, null);
        verifyNoMoreInteractions(history);
        verifyNoInteractions(metrics);
    }

    @Test
    void handlerEquivalentHistoricalPathsCannotAuthenticateThroughNormalization() throws Exception {
        for (String contextPath : new String[] {"", "/crabit"}) {
            for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                head(contextPath + HISTORICAL_PATH),
                get(URI.create(contextPath + HISTORICAL_PATH + ";x=y")),
                get(URI.create(contextPath + HISTORICAL_PATH.replace("historical-balances", "%68istorical-balances"))),
                get(URI.create(contextPath + HISTORICAL_PATH.replace("historical-balances", "historical%2Dbalances")))
            }) {
                var result = mvc.perform(request.contextPath(contextPath)
                                .header("Authorization", "Bearer trigger-secret")
                                .param("fromDate", "2026-09-01")
                                .param("toDateExclusive", "2026-09-02")
                                .param("granularity", "DAY"))
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string("WWW-Authenticate", "Bearer"))
                        .andReturn();
                assertThat(result.getRequest().getAttribute("crabit.machine-behavior-authenticated"))
                        .isNull();
            }
        }
        verifyNoInteractions(history, metrics);
    }

    @Test
    void unrelatedHistoricalPathsAndMethodsCannotReachTheHistoryService() throws Exception {
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
            post(HISTORICAL_PATH),
            put(HISTORICAL_PATH),
            get(HISTORICAL_PATH + "/extra"),
            get(URI.create(HISTORICAL_PATH.replace("/students/", "//students/"))),
            get(URI.create(HISTORICAL_PATH.replace("/students/", "%2Fstudents/")))
        }) {
            var result = mvc.perform(request.header("Authorization", "Bearer trigger-secret"))
                    .andExpect(status().is4xxClientError())
                    .andReturn();
            assertThat(result.getRequest().getAttribute("crabit.machine-behavior-authenticated"))
                    .isNull();
        }
        verifyNoInteractions(history, metrics);
    }
}
