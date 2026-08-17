package com.crabit.backend.wish;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Schema(
		name = "WishSnapshot",
		description = "The externally visible optimistic snapshot of a Wish.",
		example = """
				{
				  "id": "22222222-2222-2222-2222-222222222222",
				  "cardBalanceAccountId": "11111111-1111-1111-1111-111111111111",
				  "purpose": "Graduation trip",
				  "targetAmount": 500000,
				  "amount": 125000,
				  "targetDate": "2027-02-28",
				  "state": "IN_PROGRESS",
				  "visibility": "PRIVATE",
				  "createdAt": "2026-08-17T02:30:00Z",
				  "updatedAt": "2026-08-17T02:30:00Z",
				  "completedAt": null,
				  "actualDurationSeconds": null,
				  "version": 0
				}
				""")
public record WishSnapshot(
		@Schema(description = "Required Wish identifier.", format = "uuid",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "22222222-2222-2222-2222-222222222222") UUID id,
		@Schema(description = "Required owning Card Balance Account identifier.", format = "uuid",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "11111111-1111-1111-1111-111111111111") UUID cardBalanceAccountId,
		@Schema(description = "Required normalized purpose of 1..200 Unicode code points.",
				minLength = 1, maxLength = 200, requiredMode = Schema.RequiredMode.REQUIRED,
				example = "Graduation trip") String purpose,
		@Schema(description = "Required target amount in integer Korean won.",
				minimum = "1", maximum = "9007199254740991",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "500000") long targetAmount,
		@Schema(description = "Required allocated amount in integer Korean won; non-negative and no "
				+ "greater than targetAmount.", minimum = "0",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "125000") long amount,
		@Schema(description = "Nullable ISO target date.", format = "date", nullable = true,
				example = "2027-02-28") LocalDate targetDate,
		@Schema(description = "Required lifecycle state.",
				allowableValues = {"IN_PROGRESS", "AMOUNT_REACHED", "COMPLETED", "ABANDONED"},
				requiredMode = Schema.RequiredMode.REQUIRED, example = "IN_PROGRESS") WishState state,
		@Schema(description = "Required sharing visibility.",
				allowableValues = {"PRIVATE", "FRIENDS", "ACADEMY"},
				requiredMode = Schema.RequiredMode.REQUIRED, example = "PRIVATE") WishVisibility visibility,
		@Schema(description = "Required creation timestamp.", format = "date-time",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "2026-08-17T02:30:00Z") Instant createdAt,
		@Schema(description = "Required last-update timestamp.", format = "date-time",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "2026-08-17T02:30:00Z") Instant updatedAt,
		@Schema(description = "Nullable completion timestamp, populated only for COMPLETED Wishes.",
				format = "date-time", nullable = true,
				example = "2026-09-01T09:00:00Z") Instant completedAt,
		@Schema(description = "Nullable non-negative actual duration in seconds, populated only for "
				+ "COMPLETED Wishes.", minimum = "0", nullable = true,
				example = "1328400") Long actualDurationSeconds,
		@Schema(description = "Required non-negative optimistic version.", minimum = "0",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "0") long version) {

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
