package com.crabit.backend.balance;

import java.util.UUID;

public final class CardBalanceSyncFailedException extends RuntimeException {

	public CardBalanceSyncFailedException(UUID accountId) {
		super("Card balance sync failed for account " + accountId);
	}
}
