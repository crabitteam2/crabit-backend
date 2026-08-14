package com.crabit.backend.wish;

import java.util.Objects;
import java.util.UUID;

public record DepositBalanceProof(UUID observationId, long accountLookupVersion) {

	public DepositBalanceProof {
		Objects.requireNonNull(observationId, "observationId");
		if (accountLookupVersion <= 0) {
			throw new IllegalArgumentException("Account lookup version must be positive");
		}
	}

	public static DepositBalanceProof from(BalanceObservation observation) {
		Objects.requireNonNull(observation, "observation");
		if (observation.accountLookupVersion() == null) {
			throw new IllegalArgumentException("Observation has no account lookup version");
		}
		return new DepositBalanceProof(observation.id(), observation.accountLookupVersion());
	}
}
