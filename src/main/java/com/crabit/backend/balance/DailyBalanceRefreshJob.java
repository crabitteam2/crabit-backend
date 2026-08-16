package com.crabit.backend.balance;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.wish.BalanceLookupMethod;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyBalanceRefreshJob {

	private static final Logger log = LoggerFactory.getLogger(DailyBalanceRefreshJob.class);

	private final CardBalanceAccountRepository accounts;
	private final CardBalanceSyncService sync;

	public DailyBalanceRefreshJob(
			CardBalanceAccountRepository accounts,
			CardBalanceSyncService sync) {
		this.accounts = accounts;
		this.sync = sync;
	}

	@Scheduled(
			cron = "${crabit.balance.daily.cron}",
			zone = "${crabit.balance.daily.zone:UTC}")
	public void refreshAllActiveAccounts() {
		accounts.findByClosedAtIsNullOrderByIdAsc().stream()
				.sorted(Comparator.comparing(CardBalanceAccount::id))
				.forEach(this::refreshOne);
	}

	private void refreshOne(CardBalanceAccount account) {
		try {
			sync.refresh(account.id(), BalanceLookupMethod.AUTO_DAILY);
		} catch (RuntimeException failure) {
			log.warn("Daily card balance refresh failed for account {}", account.id(), failure);
		}
	}
}
