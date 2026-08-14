package com.crabit.backend.wish;

import java.util.Optional;

public record WishMoneyCommandResult(
		Optional<LedgerEvent> ledgerEvent,
		Optional<BalanceAdjustmentCase> adjustmentCase) {
}
