package com.crabit.backend.balance;

import static org.assertj.core.api.Assertions.assertThat;

import com.crabit.backend.wish.KrwAmount;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DeterministicCardBalanceAdapterTest {

	@Test
	void consumesEachAccountsScriptInOrderAndFailsExplicitlyWhenExhausted() {
		DeterministicCardBalanceAdapter adapter = new DeterministicCardBalanceAdapter();
		UUID firstAccount = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID secondAccount = UUID.fromString("00000000-0000-0000-0000-000000000002");

		adapter.enqueueSuccess(firstAccount, KrwAmount.nonNegative(100));
		adapter.enqueueSuccess(firstAccount, KrwAmount.nonNegative(100));
		adapter.enqueueFailure(firstAccount);
		adapter.enqueueSuccess(firstAccount, KrwAmount.nonNegative(130));
		adapter.enqueueSuccess(firstAccount, KrwAmount.nonNegative(80));
		adapter.enqueueSuccess(secondAccount, KrwAmount.zero());

		assertThat(adapter.lookup(firstAccount)).isEqualTo(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(100)));
		assertThat(adapter.lookup(secondAccount)).isEqualTo(
				new CardBalanceProviderResult.Success(KrwAmount.zero()));
		assertThat(adapter.lookup(firstAccount)).isEqualTo(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(100)));
		assertThat(adapter.lookup(firstAccount)).isEqualTo(CardBalanceProviderResult.failure());
		assertThat(adapter.lookup(firstAccount)).isEqualTo(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(130)));
		assertThat(adapter.lookup(firstAccount)).isEqualTo(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(80)));
		assertThat(adapter.lookup(firstAccount)).isEqualTo(CardBalanceProviderResult.failure());
		assertThat(adapter.lookup(secondAccount)).isEqualTo(CardBalanceProviderResult.failure());
	}

	@Test
	void clearingTheScriptRemovesEveryPendingResponse() {
		DeterministicCardBalanceAdapter adapter = new DeterministicCardBalanceAdapter();
		UUID accountId = UUID.randomUUID();
		adapter.enqueueSuccess(accountId, KrwAmount.nonNegative(10));

		adapter.clear();

		assertThat(adapter.lookup(accountId)).isEqualTo(CardBalanceProviderResult.failure());
	}

	@Test
	void exposesScriptControlOnlyInE2eAndNeverFabricatesProductionSuccess() {
		try (AnnotationConfigApplicationContext e2e = context("e2e")) {
			assertThat(e2e.getBeansOfType(CardBalanceProvider.class)).hasSize(1);
			assertThat(e2e.getBeansOfType(CardBalanceScriptControl.class)).hasSize(1);
			assertThat(e2e.getBean(CardBalanceProvider.class))
					.isInstanceOf(DeterministicCardBalanceAdapter.class);
		}

		try (AnnotationConfigApplicationContext prod = context("prod")) {
			assertThat(prod.getBeansOfType(CardBalanceScriptControl.class)).isEmpty();
			CardBalanceProvider provider = prod.getBean(CardBalanceProvider.class);
			assertThat(provider).isInstanceOf(UnavailableCardBalanceProvider.class);
			assertThat(provider.lookup(UUID.randomUUID()))
					.isEqualTo(CardBalanceProviderResult.failure());
		}
	}

	private static AnnotationConfigApplicationContext context(String profile) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().setActiveProfiles(profile);
		context.register(DeterministicCardBalanceAdapter.class, UnavailableCardBalanceProvider.class);
		context.refresh();
		return context;
	}
}
