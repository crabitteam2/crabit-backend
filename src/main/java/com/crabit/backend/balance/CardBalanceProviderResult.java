package com.crabit.backend.balance;

import com.crabit.backend.wish.KrwAmount;
import java.util.Objects;

public sealed interface CardBalanceProviderResult {

	record Success(KrwAmount balance) implements CardBalanceProviderResult {
		public Success {
			Objects.requireNonNull(balance, "balance");
			if (balance.isNegative()) {
				throw new IllegalArgumentException("Provider balance must be non-negative");
			}
		}
	}

	record Failure() implements CardBalanceProviderResult {
	}

	static Failure failure() {
		return new Failure();
	}
}
