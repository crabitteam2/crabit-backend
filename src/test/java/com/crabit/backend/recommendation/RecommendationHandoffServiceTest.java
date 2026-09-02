package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.UUID;

class RecommendationHandoffServiceTest {
	@Test
	void databaseFailureIsRetryableQueryUnavailableWithoutOutboundDelivery() {
		var snapshots = mock(RecommendationSnapshotService.class);
		var receiver = mock(RecommendationReceiverClient.class);
		@SuppressWarnings("unchecked")
		ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
		var service = new RecommendationHandoffService(snapshots, receiver, meters);
		when(snapshots.assemble(any(RecommendationRequest.class)))
				.thenThrow(new DataAccessResourceFailureException("private database detail"));
		assertThatThrownBy(() -> service.deliver(UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOfSatisfying(
						RecommendationHandoffException.class,
						exception -> {
							org.assertj.core.api.Assertions.assertThat(exception.code())
									.isEqualTo(
											RecommendationHandoffException.Code
													.RECOMMENDATION_QUERY_UNAVAILABLE);
							org.assertj.core.api.Assertions.assertThat(
											exception.code().status().value())
									.isEqualTo(503);
							org.assertj.core.api.Assertions.assertThat(exception.code().retryable())
									.isTrue();
							org.assertj.core.api.Assertions.assertThat(exception.getMessage())
									.doesNotContain("private");
						});
		verifyNoInteractions(receiver);
	}

	@Test
	void incompleteSnapshotRemainsUnprocessableWithoutOutboundDelivery() {
		var snapshots = mock(RecommendationSnapshotService.class);
		var receiver = mock(RecommendationReceiverClient.class);
		@SuppressWarnings("unchecked")
		ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
		var service = new RecommendationHandoffService(snapshots, receiver, meters);
		when(snapshots.assemble(any(RecommendationRequest.class)))
				.thenThrow(RecommendationHandoffException.incomplete());
		assertThatThrownBy(() -> service.deliver(UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOfSatisfying(
						RecommendationHandoffException.class,
						exception -> {
							org.assertj.core.api.Assertions.assertThat(
											exception.code().status().value())
									.isEqualTo(422);
							org.assertj.core.api.Assertions.assertThat(exception.code().retryable())
									.isFalse();
						});
		verifyNoInteractions(receiver);
	}
}
