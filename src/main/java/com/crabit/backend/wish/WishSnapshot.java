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
		@Schema(description = "Stable UUID of this Wish.", format = "uuid",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "22222222-2222-2222-2222-222222222222") UUID id,
		@Schema(description = "UUID of the owner Card Balance Account to which this Wish is permanently "
				+ "attached.", format = "uuid",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "11111111-1111-1111-1111-111111111111") UUID cardBalanceAccountId,
		@Schema(description = "NFC-normalized, boundary-space-free purpose text persisted for this Wish.",
				minLength = 1, maxLength = 200, requiredMode = Schema.RequiredMode.REQUIRED,
				example = "Graduation trip") String purpose,
		@Schema(description = "Positive integer KRW goal for this Wish.",
				minimum = "1", maximum = "9007199254740991",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "500000") long targetAmount,
		@Schema(description = "Non-negative integer KRW currently allocated to this Wish; it is distinct "
				+ "from actual card balance and never exceeds targetAmount.", minimum = "0",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "125000") long amount,
		@Schema(description = "Optional calendar date that may be in the past, present, or future.",
				format = "date", nullable = true,
				example = "2027-02-28") LocalDate targetDate,
		@Schema(description = "Lifecycle state: IN_PROGRESS below target, AMOUNT_REACHED at target before "
				+ "explicit completion, COMPLETED after completion, or ABANDONED after abandonment.",
				allowableValues = {"IN_PROGRESS", "AMOUNT_REACHED", "COMPLETED", "ABANDONED"},
				requiredMode = Schema.RequiredMode.REQUIRED, example = "IN_PROGRESS") WishState state,
		@Schema(description = "Requested publication scope PRIVATE, FRIENDS, or ACADEMY; current "
				+ "relationship and blocking checks may further hide any Shared Card.",
				allowableValues = {"PRIVATE", "FRIENDS", "ACADEMY"},
				requiredMode = Schema.RequiredMode.REQUIRED, example = "PRIVATE") WishVisibility visibility,
		@Schema(description = "RFC 3339 UTC Z instant at which the Wish was created.", format = "date-time",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "2026-08-17T02:30:00Z") Instant createdAt,
		@Schema(description = "RFC 3339 UTC Z instant of the most recent successful Wish content or "
				+ "lifecycle mutation.", format = "date-time",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "2026-08-17T02:30:00Z") Instant updatedAt,
		@Schema(description = "RFC 3339 UTC Z instant of explicit completion for a COMPLETED Wish; null "
				+ "for every other state.",
				format = "date-time", nullable = true,
				example = "2026-09-01T09:00:00Z") Instant completedAt,
		@Schema(description = "For completed Wishes, the elapsed whole seconds from createdAt through "
				+ "completedAt; null otherwise.", minimum = "0", nullable = true,
				example = "1328400") Long actualDurationSeconds,
		@Schema(description = "Non-negative optimistic concurrency version of this snapshot; successful "
				+ "state-changing mutations advance it and idempotent replay returns the original value.",
				minimum = "0",
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
