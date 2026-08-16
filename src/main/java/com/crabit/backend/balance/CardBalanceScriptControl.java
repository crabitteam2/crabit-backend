package com.crabit.backend.balance;

import com.crabit.backend.wish.KrwAmount;
import java.util.UUID;

/** E2E-only control surface for deterministic per-account provider scripts. */
public interface CardBalanceScriptControl {

	void enqueueSuccess(UUID accountId, KrwAmount balance);

	void enqueueFailure(UUID accountId);

	void clear();
}
