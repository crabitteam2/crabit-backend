package com.crabit.backend.balance;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")
final class UnavailableCardBalanceProvider implements CardBalanceProvider {

	@Override
	public CardBalanceProviderResult lookup(UUID accountId) {
		return CardBalanceProviderResult.failure();
	}
}
