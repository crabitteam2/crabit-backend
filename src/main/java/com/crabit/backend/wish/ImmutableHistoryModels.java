package com.crabit.backend.wish;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ImmutableHistoryModels {

	private ImmutableHistoryModels() {
	}

	@Schema(name = "BalanceAdjustmentEventReference", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record BalanceAdjustmentEventReference(
			UUID adjustmentCaseId,
			BalanceAdjustmentEventRole eventRole,
			int sequenceNumber) {
	}

	@Schema(name = "WishHistoryReference", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record WishHistoryReference(
			UUID wishId,
			String wishPurposeSnapshot,
			boolean deletedWish,
			boolean detailAvailable) {
	}

	@Schema(name = "WishHistorySubject", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record WishHistorySubject(
			UUID wishId,
			String displayPurpose,
			boolean deletedWish,
			boolean detailAvailable) {
	}

	@Schema(name = "CardBalanceChange", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record CardBalanceChange(
			UUID eventId,
			String eventType,
			UUID observationId,
			BalanceLookupMethod lookupMethod,
			Instant occurredAt,
			long actualCardBalanceDelta,
			long actualCardBalanceAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment) {
	}

	@Schema(name = "CardBalanceChangePage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record CardBalanceChangePage(
			@ArraySchema(schema = @Schema(implementation = CardBalanceChange.class))
			List<CardBalanceChange> items,
			String nextCursor) {
	}

	@Schema(name = "AccountFundMovement", discriminatorProperty = "eventType", oneOf = {
			AccountCardBalanceChange.class,
			AccountWishDeposit.class,
			AccountWishWithdrawal.class,
			AccountWishTransfer.class,
			AccountWishCompletionReturn.class,
			AccountWishAbandonmentReturn.class,
			AccountWishDeletionReturn.class
	})
	public sealed interface AccountFundMovement permits
			AccountCardBalanceChange,
			AccountWishDeposit,
			AccountWishWithdrawal,
			AccountWishTransfer,
			AccountWishCompletionReturn,
			AccountWishAbandonmentReturn,
			AccountWishDeletionReturn {
	}

	@Schema(name = "AccountCardBalanceChange", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record AccountCardBalanceChange(
			UUID eventId,
			String eventType,
			UUID observationId,
			BalanceLookupMethod lookupMethod,
			Instant occurredAt,
			long actualCardBalanceDelta,
			long actualCardBalanceAfter,
			long accountAvailableBalanceDelta,
			long accountAvailableBalanceAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements AccountFundMovement {
	}

	@Schema(name = "AccountWishDeposit", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record AccountWishDeposit(
			UUID eventId,
			String eventType,
			WishHistoryReference wish,
			Instant occurredAt,
			long accountAvailableBalanceDelta,
			long accountAvailableBalanceAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements AccountFundMovement {
	}

	@Schema(name = "AccountWishWithdrawal", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record AccountWishWithdrawal(
			UUID eventId,
			String eventType,
			WishHistoryReference wish,
			Instant occurredAt,
			long accountAvailableBalanceDelta,
			long accountAvailableBalanceAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements AccountFundMovement {
	}

	@Schema(name = "AccountWishTransfer", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record AccountWishTransfer(
			UUID eventId,
			String eventType,
			WishHistoryReference sourceWish,
			WishHistoryReference destinationWish,
			long amount,
			Instant occurredAt,
			long accountAvailableBalanceDelta,
			long accountAvailableBalanceAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements AccountFundMovement {
	}

	@Schema(name = "AccountWishCompletionReturn", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record AccountWishCompletionReturn(
			UUID eventId,
			String eventType,
			WishHistoryReference wish,
			Instant occurredAt,
			long accountAvailableBalanceDelta,
			long accountAvailableBalanceAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements AccountFundMovement {
	}

	@Schema(name = "AccountWishAbandonmentReturn", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record AccountWishAbandonmentReturn(
			UUID eventId,
			String eventType,
			WishHistoryReference wish,
			Instant occurredAt,
			long accountAvailableBalanceDelta,
			long accountAvailableBalanceAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements AccountFundMovement {
	}

	@Schema(name = "AccountWishDeletionReturn", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record AccountWishDeletionReturn(
			UUID eventId,
			String eventType,
			WishHistoryReference wish,
			Instant occurredAt,
			long accountAvailableBalanceDelta,
			long accountAvailableBalanceAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements AccountFundMovement {
	}

	@Schema(name = "AccountFundMovementPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record AccountFundMovementPage(
			@ArraySchema(schema = @Schema(implementation = AccountFundMovement.class))
			List<AccountFundMovement> items,
			String nextCursor) {
	}

	@Schema(name = "WishFundMovement", discriminatorProperty = "eventType", oneOf = {
			WishDepositMovement.class,
			WishWithdrawalMovement.class,
			WishTransferMovement.class,
			WishCompletionReturnMovement.class,
			WishAbandonmentReturnMovement.class,
			WishDeletionReturnMovement.class
	})
	public sealed interface WishFundMovement permits
			WishDepositMovement,
			WishWithdrawalMovement,
			WishTransferMovement,
			WishCompletionReturnMovement,
			WishAbandonmentReturnMovement,
			WishDeletionReturnMovement {
	}

	@Schema(name = "WishDepositMovement", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record WishDepositMovement(
			UUID eventId,
			String eventType,
			Instant occurredAt,
			String wishPurposeSnapshot,
			long wishAmountDelta,
			long wishAmountAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements WishFundMovement {
	}

	@Schema(name = "WishWithdrawalMovement", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record WishWithdrawalMovement(
			UUID eventId,
			String eventType,
			Instant occurredAt,
			String wishPurposeSnapshot,
			long wishAmountDelta,
			long wishAmountAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements WishFundMovement {
	}

	@Schema(name = "WishTransferMovement", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record WishTransferMovement(
			UUID eventId,
			String eventType,
			Instant occurredAt,
			String wishPurposeSnapshot,
			TransferDirection direction,
			WishHistoryReference counterpartyWish,
			long wishAmountDelta,
			long wishAmountAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements WishFundMovement {
	}

	@Schema(name = "WishCompletionReturnMovement", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record WishCompletionReturnMovement(
			UUID eventId,
			String eventType,
			Instant occurredAt,
			String wishPurposeSnapshot,
			long wishAmountDelta,
			long wishAmountAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements WishFundMovement {
	}

	@Schema(name = "WishAbandonmentReturnMovement", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record WishAbandonmentReturnMovement(
			UUID eventId,
			String eventType,
			Instant occurredAt,
			String wishPurposeSnapshot,
			long wishAmountDelta,
			long wishAmountAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements WishFundMovement {
	}

	@Schema(name = "WishDeletionReturnMovement", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record WishDeletionReturnMovement(
			UUID eventId,
			String eventType,
			Instant occurredAt,
			String wishPurposeSnapshot,
			long wishAmountDelta,
			long wishAmountAfter,
			UUID correctionOfEventId,
			BalanceAdjustmentEventReference balanceAdjustment)
			implements WishFundMovement {
	}

	@Schema(name = "WishFundMovementPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record WishFundMovementPage(
			WishHistorySubject wish,
			@ArraySchema(schema = @Schema(implementation = WishFundMovement.class))
			List<WishFundMovement> items,
			String nextCursor) {
	}

	public enum TransferDirection {
		SOURCE,
		DESTINATION
	}
}
