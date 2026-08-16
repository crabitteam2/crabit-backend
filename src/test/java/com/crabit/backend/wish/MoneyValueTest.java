package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyValueTest {

	private static final long MAX_SAFE_KRW = 9_007_199_254_740_991L;

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
	void acceptsOnlyTheSignedJavaScriptSafeIntegerRange() {
		assertThat(KrwAmount.of(MAX_SAFE_KRW).won()).isEqualTo(MAX_SAFE_KRW);
		assertThat(KrwAmount.of(-MAX_SAFE_KRW).won()).isEqualTo(-MAX_SAFE_KRW);

		assertThatThrownBy(() -> KrwAmount.of(MAX_SAFE_KRW + 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("JavaScript-safe");
		assertThatThrownBy(() -> KrwAmount.of(-MAX_SAFE_KRW - 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("JavaScript-safe");
	}

	@Test
	void arithmeticRejectsResultsOutsideThePublicKrwRange() {
		assertThatThrownBy(() -> KrwAmount.of(MAX_SAFE_KRW).plus(KrwAmount.of(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> KrwAmount.of(-MAX_SAFE_KRW).minus(KrwAmount.of(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(KrwAmount.of(-MAX_SAFE_KRW).absolute())
				.isEqualTo(KrwAmount.of(MAX_SAFE_KRW));
	}
}
