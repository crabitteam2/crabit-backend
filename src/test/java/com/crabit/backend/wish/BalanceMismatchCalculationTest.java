package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BalanceMismatchCalculationTest {

	@Test
	void preservesSignedLedgerWhileClampingDisplayAndExposingTheExactShortage() {
		BalanceBreakdown shortage = BalanceBreakdown.calculate(
				KrwAmount.nonNegative(70_000), KrwAmount.nonNegative(90_000));

		assertThat(shortage.ledgerAvailable()).isEqualTo(KrwAmount.of(-20_000));
		assertThat(shortage.displayAvailable()).isEqualTo(KrwAmount.zero());
		assertThat(shortage.unresolvedShortage()).isEqualTo(KrwAmount.positive(20_000));

		BalanceBreakdown surplus = BalanceBreakdown.calculate(
				KrwAmount.nonNegative(100_000), KrwAmount.nonNegative(90_000));
		assertThat(surplus.ledgerAvailable()).isEqualTo(KrwAmount.positive(10_000));
		assertThat(surplus.displayAvailable()).isEqualTo(KrwAmount.positive(10_000));
		assertThat(surplus.unresolvedShortage()).isEqualTo(KrwAmount.zero());
	}

	@Test
	void zeroLedgerIsResolvedAndInvalidNegativeInputsAreRejected() {
		BalanceBreakdown exact = BalanceBreakdown.calculate(
				KrwAmount.nonNegative(90_000), KrwAmount.nonNegative(90_000));

		assertThat(exact.ledgerAvailable()).isEqualTo(KrwAmount.zero());
		assertThat(exact.displayAvailable()).isEqualTo(KrwAmount.zero());
		assertThat(exact.unresolvedShortage()).isEqualTo(KrwAmount.zero());
		assertThatThrownBy(() -> BalanceBreakdown.calculate(
				KrwAmount.of(-1), KrwAmount.zero()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("actualCardBalance");
		assertThatThrownBy(() -> BalanceBreakdown.calculate(
				KrwAmount.zero(), KrwAmount.of(-1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("activeWishTotal");
	}
}
