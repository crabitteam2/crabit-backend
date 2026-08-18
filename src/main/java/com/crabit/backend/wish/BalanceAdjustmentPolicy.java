package com.crabit.backend.wish;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BalanceAdjustmentPolicy {

	private final BalanceAdjustmentCaseRepository adjustments;

	public BalanceAdjustmentPolicy(BalanceAdjustmentCaseRepository adjustments) {
		this.adjustments = adjustments;
	}

	public Optional<BalanceAdjustmentCase> lockOpenCase(UUID accountId) {
		return adjustments.lockSingleOpenByAccountId(
				Objects.requireNonNull(accountId, "accountId"));
	}

	public boolean isOpen(UUID accountId) {
		return adjustments.existsByAccountIdAndStatus(
				Objects.requireNonNull(accountId, "accountId"), BalanceAdjustmentStatus.OPEN);
	}

	public void requireAllowed(UUID accountId, Operation operation) {
		requireAllowed(lockOpenCase(accountId), operation);
	}

	public void requireAllowed(
			Optional<BalanceAdjustmentCase> openCase, Operation operation) {
		Objects.requireNonNull(openCase, "openCase");
		Operation requested = Objects.requireNonNull(operation, "operation");
		if (openCase.isPresent() && requested.blockedWhileOpen()) {
			throw new IllegalStateException(
					requested.description() + " is blocked while balance adjustment is open");
		}
	}

	public enum Operation {
		CREATE_WISH(true, "Wish creation"),
		DEPOSIT(true, "Wish deposit"),
		TRANSFER(true, "Wish transfer"),
		PATCH_WISH(true, "Wish edit"),
		REFRESH_BALANCE(false, "Card balance refresh"),
		READ(false, "Read"),
		WITHDRAW(false, "Wish withdrawal"),
		COMPLETE(false, "Wish completion"),
		ABANDON(false, "Wish abandonment"),
		DELETE(false, "Wish deletion");

		private final boolean blockedWhileOpen;
		private final String description;

		Operation(boolean blockedWhileOpen, String description) {
			this.blockedWhileOpen = blockedWhileOpen;
			this.description = description;
		}

		public boolean blockedWhileOpen() {
			return blockedWhileOpen;
		}

		String description() {
			return description;
		}
	}
}
