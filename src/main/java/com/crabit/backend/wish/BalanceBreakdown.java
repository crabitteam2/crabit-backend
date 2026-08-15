package com.crabit.backend.wish;

import java.util.Objects;

public record BalanceBreakdown(
		KrwAmount actualCardBalance,
		KrwAmount activeWishTotal,
		KrwAmount ledgerAvailable,
		KrwAmount displayAvailable,
		KrwAmount unresolvedShortage) {

	public BalanceBreakdown {
		Objects.requireNonNull(actualCardBalance, "actualCardBalance");
		Objects.requireNonNull(activeWishTotal, "activeWishTotal");
		Objects.requireNonNull(ledgerAvailable, "ledgerAvailable");
		Objects.requireNonNull(displayAvailable, "displayAvailable");
		Objects.requireNonNull(unresolvedShortage, "unresolvedShortage");
	}

	public static BalanceBreakdown calculate(KrwAmount actualCardBalance, KrwAmount activeWishTotal) {
		requireNonNegative(actualCardBalance, "actualCardBalance");
		requireNonNegative(activeWishTotal, "activeWishTotal");
		KrwAmount ledger = actualCardBalance.minus(activeWishTotal);
		KrwAmount display = ledger.isNegative() ? KrwAmount.zero() : ledger;
		KrwAmount shortage = ledger.isNegative() ? ledger.absolute() : KrwAmount.zero();
		return new BalanceBreakdown(actualCardBalance, activeWishTotal, ledger, display, shortage);
	}

	private static void requireNonNegative(KrwAmount amount, String name) {
		if (Objects.requireNonNull(amount, name).isNegative()) {
			throw new IllegalArgumentException(name + " must be non-negative");
		}
	}
}
