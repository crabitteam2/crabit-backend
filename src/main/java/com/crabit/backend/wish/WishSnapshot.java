package com.crabit.backend.wish;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record WishSnapshot(
		UUID id,
		UUID cardBalanceAccountId,
		String purpose,
		long targetAmount,
		long amount,
		LocalDate targetDate,
		WishState state,
		WishVisibility visibility,
		Instant createdAt,
		Instant updatedAt,
		Instant completedAt,
		Long actualDurationSeconds,
		long version) {

	static WishSnapshot from(Wish wish) {
		return new WishSnapshot(
				wish.id(),
				wish.accountId(),
				wish.purpose(),
				wish.targetAmount().won(),
				wish.amount().won(),
				wish.targetDate(),
				wish.state(),
				wish.visibility(),
				wish.createdAt(),
				wish.updatedAt(),
				wish.completedAt(),
				wish.actualDuration().map(java.time.Duration::toSeconds).orElse(null),
				wish.version());
	}
}
