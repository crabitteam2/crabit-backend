package com.crabit.backend.wish;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.crabit.backend.wishphoto.WishPhotoView;

@Schema(
		name = "Wish",
		description = "The externally visible optimistic snapshot of a Wish.",
		additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
		example = """
				{
				  "id": "22222222-2222-2222-2222-222222222222",
				  "cardBalanceAccountId": "11111111-1111-1111-1111-111111111111",
				  "purpose": "Graduation trip",
				  "targetAmount": 500000,
				  "amount": 125000,
				  "abandonmentAmount": null,
				  "targetDate": "2027-02-28",
				  "state": "IN_PROGRESS",
				  "visibility": "PRIVATE",
				  "balanceAdjustmentInProgress": false,
				  "createdAt": "2026-08-17T02:30:00Z",
				  "updatedAt": "2026-08-17T02:30:00Z",
				  "completedAt": null,
				  "closedAt": null,
				  "actualDurationSeconds": null,
				  "version": 0
				}
				""")
public record WishSnapshot(
		@Schema(ref = "#/components/schemas/Uuid", description = "Stable UUID of this Wish.",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "22222222-2222-2222-2222-222222222222") UUID id,
		@Schema(description = "UUID of the owner Card Balance Account to which this Wish is permanently "
				+ "attached.", ref = "#/components/schemas/Uuid",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "11111111-1111-1111-1111-111111111111") UUID cardBalanceAccountId,
		@Schema(ref = "#/components/schemas/Purpose",
				description = "NFC-normalized, boundary-space-free purpose text persisted for this Wish.",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "Graduation trip") String purpose,
		@Schema(ref = "#/components/schemas/KrwPositive",
				description = "Positive integer KRW goal for this Wish.",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "500000") long targetAmount,
		@Schema(ref = "#/components/schemas/KrwNonNegative",
				description = "Non-negative integer KRW currently allocated to this Wish; it is distinct "
						+ "from actual card balance and never exceeds targetAmount.",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "125000") long amount,
		@Schema(description = "Immutable owner-visible amount allocated to this Wish immediately before "
				+ "successful abandonment. It is a non-negative integer KRW no greater than targetAmount "
				+ "for ABANDONED, including numeric zero, and explicit null for every other state. It is "
				+ "preserved through tombstoning and idempotent replay and is distinct from current amount.",
				minimum = "0", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED,
				example = "125000") Long abandonmentAmount,
		@Schema(description = "Optional calendar date that may be in the past, present, or future.",
				format = "date", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED,
				example = "2027-02-28") LocalDate targetDate,
		@Schema(ref = "#/components/schemas/WishState",
				description = "Lifecycle state: IN_PROGRESS below target, AMOUNT_REACHED at target before "
				+ "explicit completion, COMPLETED after completion, or ABANDONED after abandonment.",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "IN_PROGRESS") WishState state,
		@Schema(ref = "#/components/schemas/WishVisibility",
				description = "Requested publication scope PRIVATE, FRIENDS, or ACADEMY; current "
						+ "relationship and blocking checks may further hide any Shared Card.",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "PRIVATE") WishVisibility visibility,
		@Schema(description = "True iff this Wish's Card Balance Account has an OPEN Balance "
				+ "Adjustment Case for this response snapshot; derived and not persisted on the Wish "
				+ "or Shared Card. List and detail responses reflect read time, mutation responses "
				+ "reflect committed post-mutation state, and opening or resolving a case does not "
				+ "advance Wish version or updatedAt. This projection exposes only the boolean, never "
				+ "shortage amount, adjustmentCaseId, observationId, event links, or account history.",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "false") boolean balanceAdjustmentInProgress,
		@Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		WishPhotoView photo,
		@Schema(ref = "#/components/schemas/UtcInstant",
				description = "RFC 3339 UTC Z instant at which the Wish was created.",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "2026-08-17T02:30:00Z") Instant createdAt,
		@Schema(ref = "#/components/schemas/UtcInstant",
				description = "RFC 3339 UTC Z instant of the most recent successful Wish content or "
				+ "lifecycle mutation.",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "2026-08-17T02:30:00Z") Instant updatedAt,
		@Schema(description = "RFC 3339 UTC Z instant of explicit completion for a COMPLETED Wish; null "
				+ "for every other state.",
				format = "date-time", pattern = "Z$", nullable = true,
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "2026-09-01T09:00:00Z") Instant completedAt,
			@Schema(description = "RFC 3339 UTC Z lifecycle closure instant. Equal to completedAt for "
					+ "COMPLETED, the internal persisted abandonment instant for ABANDONED, and null "
					+ "for active states. Independent of targetDate, updatedAt, and deletion time.",
					format = "date-time", pattern = "Z$", nullable = true,
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "2026-09-01T09:00:00Z") Instant closedAt,
			@Schema(description = "For completed Wishes, the elapsed whole seconds from createdAt through "
				+ "completedAt; null otherwise.", minimum = "0", nullable = true,
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "1328400") Long actualDurationSeconds,
		@Schema(ref = "#/components/schemas/WishVersion",
				description = "Non-negative optimistic concurrency version of this snapshot; successful "
				+ "state-changing mutations advance it and idempotent replay returns the original value.",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "0") long version) {

	static WishSnapshot from(Wish wish, boolean balanceAdjustmentInProgress) {
		return from(wish, balanceAdjustmentInProgress, null);
	}

	static WishSnapshot from(Wish wish, boolean balanceAdjustmentInProgress, WishPhotoView photo) {
		return new WishSnapshot(
				wish.id(),
				wish.accountId(),
				wish.purpose(),
				wish.targetAmount().won(),
				wish.amount().won(),
				wish.abandonmentAmount() == null ? null : wish.abandonmentAmount().won(),
				wish.targetDate(),
				wish.state(),
				wish.visibility(),
				balanceAdjustmentInProgress,
				photo,
				wish.createdAt(),
					wish.updatedAt(),
					wish.completedAt(),
					wish.closedAt(),
					wish.actualDuration().map(java.time.Duration::toSeconds).orElse(null),
				wish.version());
	}

	WishSnapshot withPhoto(WishPhotoView refreshedPhoto) {
		return new WishSnapshot(id, cardBalanceAccountId, purpose, targetAmount, amount,
				abandonmentAmount, targetDate, state, visibility, balanceAdjustmentInProgress,
				refreshedPhoto, createdAt, updatedAt,
				completedAt, closedAt, actualDurationSeconds, version);
	}
}
