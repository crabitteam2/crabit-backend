package com.crabit.backend.balance;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.wish.BalanceLookupMethod;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DailyBalanceRefreshJobTest {

	@Test
	void visitsActiveAccountsByUuidAndContinuesAfterOneAccountFails() {
		CardBalanceAccountRepository accounts = mock(CardBalanceAccountRepository.class);
		CardBalanceSyncService sync = mock(CardBalanceSyncService.class);
		DailyBalanceRefreshJob job = new DailyBalanceRefreshJob(accounts, sync);
		UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID middle = UUID.fromString("00000000-0000-0000-0000-000000000002");
		UUID high = UUID.fromString("00000000-0000-0000-0000-000000000003");
		when(accounts.findByClosedAtIsNullOrderByIdAsc()).thenReturn(List.of(
				account(high), account(low), account(middle)));
		when(sync.refresh(middle, BalanceLookupMethod.AUTO_DAILY))
				.thenThrow(new IllegalStateException("isolated account failure"));

		job.refreshAllActiveAccounts();

		InOrder order = inOrder(sync);
		order.verify(sync).refresh(low, BalanceLookupMethod.AUTO_DAILY);
		order.verify(sync).refresh(middle, BalanceLookupMethod.AUTO_DAILY);
		order.verify(sync).refresh(high, BalanceLookupMethod.AUTO_DAILY);
	}

	private static CardBalanceAccount account(UUID id) {
		return CardBalanceAccount.reconstitute(
				id, UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH, null);
	}
}
