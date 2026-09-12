package com.crabit.backend.balance;

import com.crabit.backend.wish.KrwAmount;
import java.util.Objects;

public sealed interface CardBalanceProviderResult {

	record Success(KrwAmount balance, String simulationDatasetId, String simulationSourceRef) implements CardBalanceProviderResult {
        public Success(KrwAmount balance) { this(balance, null, null); }
		public Success {
			Objects.requireNonNull(balance, "balance");
            if ((simulationDatasetId == null) != (simulationSourceRef == null)
                    || simulationDatasetId != null && (!simulationDatasetId.matches("sha256:[0-9a-f]{64}")
                    || simulationSourceRef.isBlank() || simulationSourceRef.length() > 240))
                throw new IllegalArgumentException("Invalid simulation provenance");
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
