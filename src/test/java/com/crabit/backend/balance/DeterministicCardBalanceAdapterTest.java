package com.crabit.backend.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.wish.KrwAmount;
import java.util.List;
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
	void replacesAndInspectsOneAccountsRemainingScriptWithoutConsumingOrLeaking() {
		DeterministicCardBalanceAdapter adapter = new DeterministicCardBalanceAdapter();
		UUID firstAccount = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID secondAccount = UUID.fromString("00000000-0000-0000-0000-000000000002");
		CardBalanceProviderResult.Success first =
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(100));
		CardBalanceProviderResult.Failure failure = CardBalanceProviderResult.failure();
		CardBalanceProviderResult.Success recovery =
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(130));

		adapter.enqueueSuccess(firstAccount, KrwAmount.nonNegative(1));
		adapter.enqueueSuccess(secondAccount, KrwAmount.nonNegative(200));
		adapter.replace(firstAccount, List.of(first, failure, recovery));

		assertThat(adapter.remaining(firstAccount)).containsExactly(first, failure, recovery);
		assertThat(adapter.remaining(firstAccount)).containsExactly(first, failure, recovery);
		assertThat(adapter.lookup(firstAccount)).isEqualTo(first);
		assertThat(adapter.remaining(firstAccount)).containsExactly(failure, recovery);
		assertThat(adapter.remaining(secondAccount)).containsExactly(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(200)));

		adapter.clear(firstAccount);
		adapter.clear(firstAccount);

		assertThat(adapter.remaining(firstAccount)).isEmpty();
		assertThat(adapter.remaining(secondAccount)).hasSize(1);
	}

	@Test
	void rejectsAnEmptyReplacementWithoutChangingTheExistingScript() {
		DeterministicCardBalanceAdapter adapter = new DeterministicCardBalanceAdapter();
		UUID accountId = UUID.randomUUID();
		CardBalanceProviderResult.Success existing =
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(10));
		adapter.replace(accountId, List.of(existing));

		assertThatThrownBy(() -> adapter.replace(accountId, List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(adapter.remaining(accountId)).containsExactly(existing);
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
