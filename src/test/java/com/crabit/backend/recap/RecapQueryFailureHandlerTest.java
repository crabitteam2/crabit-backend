package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.crabit.backend.api.RecapController;
import com.crabit.backend.api.WishApiExceptionHandler;
import com.crabit.backend.auth.CurrentPrincipal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

class RecapQueryFailureHandlerTest {
	@Test void realTransactionAcquisitionFailureReturnsTheSanitizedRetryableContract503() throws Exception {
		var unavailable = new AbstractDataSource() {
			public Connection getConnection() throws SQLException { throw new SQLException("database-password-sensitive-detail"); }
			public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
		};
		var factory = new ProxyFactory(new RecapQueryService(null, null, null, JsonMapper.builder().build()));
		factory.setProxyTargetClass(true);
		factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(unavailable), new AnnotationTransactionAttributeSource()));
		var mvc = MockMvcBuilders.standaloneSetup(new RecapController((RecapQueryService)factory.getProxy()))
				.setControllerAdvice(new WishApiExceptionHandler(), new RecapQueryFailureHandler()).build();
		var principal = new CurrentPrincipal(UUID.randomUUID(), CurrentPrincipal.Role.STUDENT, UUID.randomUUID(), "test");
		for (String kind : new String[]{"weekly", "monthly"}) {
			var response = mvc.perform(get("/v1/card-balance-accounts/" + UUID.randomUUID() + "/recaps/" + kind)
					.requestAttr(CurrentPrincipal.REQUEST_ATTRIBUTE, principal)).andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(503); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			var body = JsonMapper.builder().build().readTree(response.getContentAsString()).get("error");
			assertThat(body.get("code").asString()).isEqualTo("RECAP_QUERY_UNAVAILABLE"); assertThat(body.get("retryable").asBoolean()).isTrue();
			assertThat(response.getContentAsString()).doesNotContain("database-password", "SQLException");
		}
	}
}
