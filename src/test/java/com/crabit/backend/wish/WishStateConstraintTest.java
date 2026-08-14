package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WishStateConstraintTest {

	private final UUID accountId = UUID.randomUUID();
	private final UUID academyId = UUID.randomUUID();

	@Test
	void acceptsOnlyTheAmountCombinationDefinedForEachState() {
		assertThat(reconstitute(WishState.IN_PROGRESS, 99, 100).isActive()).isTrue();
		assertThat(reconstitute(WishState.AMOUNT_REACHED, 100, 100).isActive()).isTrue();
		assertThat(reconstitute(WishState.COMPLETED, 0, 100).isTerminal()).isTrue();
		assertThat(reconstitute(WishState.ABANDONED, 0, 100).isTerminal()).isTrue();

		assertThatThrownBy(() -> reconstitute(WishState.IN_PROGRESS, 100, 100))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reconstitute(WishState.AMOUNT_REACHED, 99, 100))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reconstitute(WishState.COMPLETED, 1, 100))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reconstitute(WishState.ABANDONED, 1, 100))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNonPositiveTargetsAndAmountsOutsideTheTarget() {
		assertThatThrownBy(() -> reconstitute(WishState.IN_PROGRESS, 0, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reconstitute(WishState.IN_PROGRESS, -1, 100))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reconstitute(WishState.IN_PROGRESS, 101, 100))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private Wish reconstitute(WishState state, long amount, long target) {
		return Wish.reconstitute(
				UUID.randomUUID(),
				accountId,
				academyId,
				"노트북",
				KrwAmount.of(target),
				KrwAmount.of(amount),
				state,
				WishVisibility.PRIVATE,
				Instant.parse("2026-08-14T00:00:00Z"),
				null,
				null);
	}
}
