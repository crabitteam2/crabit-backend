package com.crabit.backend.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FriendManagementDomainInvariantTest {

	private static final Instant CREATED = Instant.parse("2026-08-20T01:00:00Z");

	@Test
	void requestRequiresDistinctStudentsAndCanonicalizesThePair() {
		UUID low = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID high = UUID.fromString("22222222-2222-2222-2222-222222222222");

		FriendRequest request = new FriendRequest(UUID.randomUUID(), high, low, CREATED);

		assertThat(request.studentLowId()).isEqualTo(low);
		assertThat(request.studentHighId()).isEqualTo(high);
		assertThat(request.status()).isEqualTo(FriendRequestStatus.PENDING);
		assertThat(request.processedAt()).isNull();
		assertThatThrownBy(() -> new FriendRequest(UUID.randomUUID(), low, low, CREATED))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void processedRequestIsTerminalAndCannotBeProcessedBeforeCreation() {
		FriendRequest request = new FriendRequest(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), CREATED);

		assertThatThrownBy(() -> request.accept(CREATED.minusSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class);
		request.reject(CREATED.plusSeconds(1));

		assertThat(request.status()).isEqualTo(FriendRequestStatus.REJECTED);
		assertThat(request.processedAt()).isEqualTo(CREATED.plusSeconds(1));
		assertThatThrownBy(() -> request.cancel(CREATED.plusSeconds(2)))
				.isInstanceOf(IllegalStateException.class);
	}
}
