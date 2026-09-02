package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedCardSynchronizationTest {

	private static final UUID ACCOUNT_ID = UUID.randomUUID();
	private static final UUID ACADEMY_ID = UUID.randomUUID();
	private static final UUID WISH_ID = UUID.randomUUID();
	private static final Instant CREATED_AT = Instant.parse("2026-08-16T00:00:00Z");

	@Test
	void refreshesTheSameCardAcrossPublicProgressAndCompletion() {
		SharedCardRepository repository = mock(SharedCardRepository.class);
		SharedCard existing = new SharedCard(
				WISH_ID, SharedCardKind.PROGRESS, WishVisibility.FOLLOWERS, CREATED_AT);
		UUID stableId = existing.id();
		Wish completed = Wish.reconstitute(
				WISH_ID, ACCOUNT_ID, ACADEMY_ID, "수료 선물", KrwAmount.positive(100),
				KrwAmount.zero(), WishState.COMPLETED, WishVisibility.ACADEMY,
				LocalDate.of(2026, 12, 31), CREATED_AT, CREATED_AT.plusSeconds(20),
				CREATED_AT.plusSeconds(20), null, null);
		when(repository.findByWishId(WISH_ID)).thenReturn(Optional.of(existing));

		new SharedCardSynchronizationService(repository)
				.synchronize(completed, CREATED_AT.plusSeconds(20));

		assertThat(existing.id()).isEqualTo(stableId);
		assertThat(existing.kind()).isEqualTo(SharedCardKind.COMPLETION);
		assertThat(existing.visibility()).isEqualTo(WishVisibility.ACADEMY);
		assertThat(existing.updatedAt()).isEqualTo(CREATED_AT.plusSeconds(20));
		verify(repository).save(existing);
	}

	@Test
	void removesTheExistingCardWhenTheWishBecomesPrivate() {
		SharedCardRepository repository = mock(SharedCardRepository.class);
		SharedCard existing = new SharedCard(
				WISH_ID, SharedCardKind.PROGRESS, WishVisibility.FOLLOWERS, CREATED_AT);
		Wish privateWish = Wish.reconstitute(
				WISH_ID, ACCOUNT_ID, ACADEMY_ID, "비공개 위시", KrwAmount.positive(100),
				KrwAmount.zero(), WishState.IN_PROGRESS, WishVisibility.PRIVATE,
				null, CREATED_AT, CREATED_AT.plusSeconds(10), null, null, null);
		when(repository.findByWishId(WISH_ID)).thenReturn(Optional.of(existing));

		new SharedCardSynchronizationService(repository)
				.synchronize(privateWish, CREATED_AT.plusSeconds(10));

		verify(repository).delete(existing);
	}
}
