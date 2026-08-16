package com.crabit.backend.balance;

import com.crabit.backend.wish.BalanceLookupMethod;
import com.crabit.backend.wish.DepositBalanceProof;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PreDepositBalanceService {

	private final CardBalanceSyncService sync;

	public PreDepositBalanceService(CardBalanceSyncService sync) {
		this.sync = sync;
	}

	/** Performs and commits PRE_DEPOSIT lookup before the caller starts a deposit transaction. */
	public DepositBalanceProof prepare(UUID accountId) {
		UUID targetAccountId = Objects.requireNonNull(accountId, "accountId");
		CardBalanceSyncResult result = sync.refresh(
				targetAccountId, BalanceLookupMethod.PRE_DEPOSIT);
		if (result instanceof CardBalanceSyncResult.Success success) {
			return DepositBalanceProof.from(success.observation());
		}
		throw new CardBalanceSyncFailedException(targetAccountId);
	}
}
