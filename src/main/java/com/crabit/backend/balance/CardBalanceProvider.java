package com.crabit.backend.balance;

import java.util.UUID;

/** External boundary for obtaining the current actual balance of one card account. */
public interface CardBalanceProvider {

	CardBalanceProviderResult lookup(UUID accountId);
}
