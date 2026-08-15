package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyValueTest {

	@Test
	void representsWholeWonIncludingSignedLedgerBalances() {
		assertThat(KrwAmount.of(-1_500).won()).isEqualTo(-1_500);
		assertThat(KrwAmount.nonNegative(0).won()).isZero();
		assertThat(KrwAmount.positive(1).won()).isOne();
	}

	@Test
	void rejectsInvalidPositiveAndNonNegativeAmounts() {
		assertThatThrownBy(() -> KrwAmount.positive(0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("positive");
		assertThatThrownBy(() -> KrwAmount.nonNegative(-1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("non-negative");
	}

	@Test
	void arithmeticFailsInsteadOfWrappingAtLongBounds() {
		assertThatThrownBy(() -> KrwAmount.of(Long.MAX_VALUE).plus(KrwAmount.of(1)))
				.isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> KrwAmount.of(Long.MIN_VALUE).minus(KrwAmount.of(1)))
				.isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> KrwAmount.of(Long.MIN_VALUE).absolute())
				.isInstanceOf(ArithmeticException.class);
	}
}
