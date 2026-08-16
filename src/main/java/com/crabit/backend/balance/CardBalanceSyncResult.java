package com.crabit.backend.balance;

import com.crabit.backend.wish.BalanceObservation;
import java.util.Objects;

public sealed interface CardBalanceSyncResult {

	record Success(BalanceObservation observation) implements CardBalanceSyncResult {
		public Success {
			Objects.requireNonNull(observation, "observation");
		}
	}

	record Failure(BalanceObservation observation) implements CardBalanceSyncResult {
		public Failure {
			Objects.requireNonNull(observation, "observation");
		}
	}
}
