package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.crabit.backend.history.HistoricalBalanceException;
import com.crabit.backend.history.HistoricalBalanceQueryService;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class HistoricalBalanceTransactionBoundaryTest {
    private static final String PATH = "/internal/v1/academies/00000000-0000-4000-8000-000000000001"
            + "/students/00000000-0000-4000-8000-000000000002"
            + "/card-balance-accounts/00000000-0000-4000-8000-000000000003/historical-balances";
    private static final String STORAGE_DETAIL = "private-database-host:5432 credential failure";
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void connectionAcquisitionFailureIsSanitizedBeforeTheQueryMethodRuns() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException(STORAGE_DETAIL));
        var mvc = mvc(new HistoricalBalanceQueryService(jdbc), new DataSourceTransactionManager(dataSource));

        expectError(mvc, HistoricalBalanceException.Code.HISTORICAL_BALANCE_QUERY_UNAVAILABLE);

        verify(dataSource).getConnection();
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitFailureAfterTheQueryReturnsIsSanitized(boolean translateJdbcException) throws Exception {
        connected();
        doThrow(new SQLTransientConnectionException(STORAGE_DETAIL)).when(connection).commit();
        // Only the successful query body is stubbed: the real Spring interceptor and
        // transaction manager begin, commit, translate the JDBC failure, and clean up.
        var history = spy(new HistoricalBalanceQueryService(jdbc));
        doReturn(Map.of("items", List.of())).when(history).query(any(), any(), any(), any(), any(), any(), any());
        PlatformTransactionManager transactions = translateJdbcException
                ? new JdbcTransactionManager(dataSource) : new DataSourceTransactionManager(dataSource);

        expectError(mvc(history, transactions), HistoricalBalanceException.Code.HISTORICAL_BALANCE_QUERY_UNAVAILABLE);

        verify(connection).commit();
        verify(connection).close();
    }

    @Test
    void rollbackInfrastructureFailureIsSanitizedOutsideTheQueryMethod() throws Exception {
        connected();
        queryReachesAccountLookup();
        doThrow(new SQLException(STORAGE_DETAIL)).when(connection).rollback();

        expectError(mvc(new HistoricalBalanceQueryService(jdbc), new DataSourceTransactionManager(dataSource)),
                HistoricalBalanceException.Code.HISTORICAL_BALANCE_QUERY_UNAVAILABLE);

        verify(connection).rollback();
        verify(connection).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void domainNotFoundAndIntegrityFailuresKeepTheirClassification(boolean accountExists) throws Exception {
        connected();
        queryReachesAccountLookup();
        if (accountExists) {
            when(jdbc.query(contains("select a.opened_at"), ArgumentMatchers.<RowMapper<Instant>>any(), any(), any(), any()))
                    .thenReturn(List.of(Instant.parse("2026-09-01T00:00:00Z")));
        }

        expectError(mvc(new HistoricalBalanceQueryService(jdbc), new DataSourceTransactionManager(dataSource)),
                accountExists ? HistoricalBalanceException.Code.HISTORICAL_BALANCE_INTEGRITY_ERROR
                        : HistoricalBalanceException.Code.CARD_BALANCE_ACCOUNT_NOT_FOUND);

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void malformedRequestIsRejectedWithoutOpeningATransaction() throws Exception {
        var mvc = mvc(new HistoricalBalanceQueryService(jdbc), new DataSourceTransactionManager(dataSource));

        mvc.perform(request().param("unexpected", "value"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.error.retryable").value(false));

        verifyNoInteractions(dataSource, jdbc);
    }

    private void connected() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    }

    private void queryReachesAccountLookup() {
        when(jdbc.queryForObject("select clock_timestamp()", Timestamp.class))
                .thenReturn(Timestamp.from(Instant.parse("2026-09-02T12:00:00Z")));
    }

    private MockMvc mvc(HistoricalBalanceQueryService history, PlatformTransactionManager transactions) {
        var proxy = new ProxyFactory(history);
        proxy.setProxyTargetClass(true);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        proxy.addAdvice(interceptor);
        return MockMvcBuilders.standaloneSetup(new HistoricalBalanceController(
                (HistoricalBalanceQueryService) proxy.getProxy()))
                .setControllerAdvice(new WishApiExceptionHandler()).build();
    }

    private static MockHttpServletRequestBuilder request() {
        return get(PATH).requestAttr("crabit.machine-behavior-authenticated", true)
                .param("fromDate", "2026-09-01").param("toDateExclusive", "2026-09-02").param("granularity", "DAY");
    }

    private static void expectError(MockMvc mvc, HistoricalBalanceException.Code code) throws Exception {
        String json = mvc.perform(request())
                .andExpect(status().is(code.status))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value(code.name()))
                .andExpect(jsonPath("$.error.message").value(code.message))
                .andExpect(jsonPath("$.error.retryable").value(code.retryable))
                .andExpect(jsonPath("$.error.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String traceId = JsonPath.read(json, "$.error.traceId");
        assertThat(UUID.fromString(traceId).toString()).isEqualTo(traceId);
        assertThat(json).doesNotContain(STORAGE_DETAIL, "SQLException", "TransactionException");
        assertThat(OpenApiExamplesTest.validateWireResponse("ErrorEnvelope", JsonPath.read(json, "$"))).isEmpty();
    }
}
