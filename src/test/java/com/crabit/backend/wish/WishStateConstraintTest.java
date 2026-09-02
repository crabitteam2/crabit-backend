package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
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
	void terminalTimestampExistsOnlyForItsMatchingStateAndCannotPrecedeCreation() {
		Instant createdAt = Instant.parse("2026-08-14T00:00:00Z");
		Instant completedAt = createdAt.plusSeconds(60);
		Instant abandonedAt = createdAt.plusSeconds(90);

		assertThat(reconstitute(WishState.COMPLETED, 0, 100, completedAt, null).closedAt())
				.isEqualTo(completedAt);
		assertThat(reconstitute(WishState.ABANDONED, 0, 100, null, abandonedAt).closedAt())
				.isEqualTo(abandonedAt);
		assertThatThrownBy(() -> reconstitute(WishState.COMPLETED, 0, 100, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reconstitute(WishState.ABANDONED, 0, 100, completedAt, abandonedAt))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reconstitute(
				WishState.COMPLETED, 0, 100, createdAt.minusSeconds(1), null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> reconstitute(
				WishState.ABANDONED, 0, 100, null, createdAt.minusSeconds(1)))
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

	@Test
	void reconstitutionRejectsAnInvertedPersistedPlanDatePair() {
		assertThatThrownBy(() -> Wish.reconstitute(
				UUID.randomUUID(),
				accountId,
				academyId,
				"노트북",
				KrwAmount.of(100),
				KrwAmount.zero(),
				WishState.IN_PROGRESS,
				WishVisibility.PRIVATE,
				LocalDate.of(2027, 1, 2),
				LocalDate.of(2027, 1, 1),
				Instant.parse("2026-08-14T00:00:00Z"),
				Instant.parse("2026-08-14T00:00:00Z"),
				null,
				null,
				null,
				null))
				.isInstanceOf(WishDateRangeException.class);
	}

	private Wish reconstitute(WishState state, long amount, long target) {
		Instant completedAt = state == WishState.COMPLETED
				? Instant.parse("2026-08-14T00:01:00Z")
				: null;
		Instant abandonedAt = state == WishState.ABANDONED
				? Instant.parse("2026-08-14T00:01:00Z")
				: null;
		return reconstitute(state, amount, target, completedAt, abandonedAt);
	}

	private Wish reconstitute(
			WishState state, long amount, long target, Instant completedAt, Instant abandonedAt) {
		return Wish.reconstitute(
				UUID.randomUUID(),
				accountId,
				academyId,
				"노트북",
				KrwAmount.of(target),
				KrwAmount.of(amount),
				state,
				WishVisibility.PRIVATE,
					LocalDate.of(2026, 12, 31),
					Instant.parse("2026-08-14T00:00:00Z"),
					Instant.parse("2026-08-14T00:00:00Z"),
					completedAt,
					abandonedAt,
					null,
				null);
	}
}
