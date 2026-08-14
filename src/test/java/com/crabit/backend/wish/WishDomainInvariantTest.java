package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WishDomainInvariantTest {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private final UUID accountId = UUID.randomUUID();
	private final UUID academyId = UUID.randomUUID();

	@Test
	void newWishStartsPrivateEmptyAndPermanentlyBoundToItsAccountAndAcademy() {
		Wish wish = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100_000), NOW);

		assertThat(wish.accountId()).isEqualTo(accountId);
		assertThat(wish.academyId()).isEqualTo(academyId);
		assertThat(wish.amount()).isEqualTo(KrwAmount.zero());
		assertThat(wish.state()).isEqualTo(WishState.IN_PROGRESS);
		assertThat(wish.visibility()).isEqualTo(WishVisibility.PRIVATE);
		assertThat(wish.isActive()).isTrue();
	}

	@Test
	void allocationAndWithdrawalRecalculateTheOnlyValidActiveState() {
		Wish wish = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);

		wish.allocate(KrwAmount.positive(100));
		assertThat(wish.state()).isEqualTo(WishState.AMOUNT_REACHED);

		wish.withdraw(KrwAmount.positive(1));
		assertThat(wish.amount()).isEqualTo(KrwAmount.of(99));
		assertThat(wish.state()).isEqualTo(WishState.IN_PROGRESS);

		assertThatThrownBy(() -> wish.allocate(KrwAmount.positive(2)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("target");
	}

	@Test
	void completionRequiresAmountReachedReturnsAllMoneyAndIsIrreversible() {
		Wish wish = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);
		assertThatThrownBy(wish::complete).isInstanceOf(IllegalStateException.class);

		wish.allocate(KrwAmount.positive(100));
		KrwAmount returned = wish.complete();

		assertThat(returned).isEqualTo(KrwAmount.of(100));
		assertThat(wish.amount()).isEqualTo(KrwAmount.zero());
		assertThat(wish.state()).isEqualTo(WishState.COMPLETED);
		assertThat(wish.isActive()).isFalse();
		assertThatThrownBy(() -> wish.withdraw(KrwAmount.positive(1)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void deletingAnInProgressWishReturnsFundsWithoutRecordingAbandonment() {
		Wish wish = Wish.create(accountId, academyId, "여름 캠프", KrwAmount.positive(200), NOW);
		wish.allocate(KrwAmount.positive(80));

		KrwAmount returned = wish.tombstone(NOW.plusSeconds(60));

		assertThat(returned).isEqualTo(KrwAmount.of(80));
		assertThat(wish.amount()).isEqualTo(KrwAmount.zero());
		assertThat(wish.state()).isEqualTo(WishState.IN_PROGRESS);
		assertThat(wish.isDeleted()).isTrue();
		assertThat(wish.purposeSnapshot()).isEqualTo("여름 캠프");
		assertThat(wish.displayPurpose()).isEqualTo("삭제된 위시");
		assertThat(wish.isActive()).isFalse();
	}

	@Test
	void deletingAnAmountReachedWishReturnsFundsWithoutChangingItsLifecycleState() {
		Wish wish = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);
		wish.allocate(KrwAmount.positive(100));

		KrwAmount returned = wish.tombstone(NOW.plusSeconds(60));

		assertThat(returned).isEqualTo(KrwAmount.of(100));
		assertThat(wish.amount()).isEqualTo(KrwAmount.zero());
		assertThat(wish.state()).isEqualTo(WishState.AMOUNT_REACHED);
		assertThat(wish.isDeleted()).isTrue();
		assertThat(wish.isActive()).isFalse();
	}

	@Test
	void deletingACompletedWishPreservesCompletionAndItsPurposeSnapshot() {
		Wish wish = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);
		wish.allocate(KrwAmount.positive(100));
		wish.complete();

		KrwAmount returned = wish.tombstone(NOW.plusSeconds(60));

		assertThat(returned).isEqualTo(KrwAmount.zero());
		assertThat(wish.state()).isEqualTo(WishState.COMPLETED);
		assertThat(wish.isDeleted()).isTrue();
		assertThat(wish.purposeSnapshot()).isEqualTo("노트북");
	}

	@Test
	void deletingAnAbandonedWishPreservesAbandonmentAndItsPurposeSnapshot() {
		Wish wish = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);
		wish.allocate(KrwAmount.positive(40));
		wish.abandon();

		KrwAmount returned = wish.tombstone(NOW.plusSeconds(60));

		assertThat(returned).isEqualTo(KrwAmount.zero());
		assertThat(wish.state()).isEqualTo(WishState.ABANDONED);
		assertThat(wish.isDeleted()).isTrue();
		assertThat(wish.purposeSnapshot()).isEqualTo("노트북");
	}

	@Test
	void oneTransferEventCarriesBothWishProjectionsWithoutDuplicatingTheFact() {
		UUID sourceWishId = UUID.randomUUID();
		UUID destinationWishId = UUID.randomUUID();

		LedgerEvent transfer = LedgerEvent.transfer(
				accountId,
				sourceWishId,
				"노트북",
				destinationWishId,
				"여행",
				KrwAmount.positive(30),
				NOW);

		assertThat(transfer.type()).isEqualTo(LedgerEventType.WISH_TRANSFER);
		assertThat(transfer.wishEffects()).hasSize(2);
		assertThat(transfer.wishEffects()).extracting(effect -> effect.delta().won())
				.containsExactlyInAnyOrder(-30L, 30L);
		assertThat(transfer.wishEffects()).extracting(LedgerWishEffect::eventId)
				.containsOnly(transfer.id());
	}

	@Test
	void accountRulesRejectASecondActiveLogicalAccountForTheSameStudentAndAcademy() {
		UUID studentId = UUID.randomUUID();
		CardBalanceAccount active = CardBalanceAccount.open(studentId, academyId, NOW);

		assertThatThrownBy(() -> CardBalanceAccountRules.assertCanOpen(
				studentId, academyId, List.of(active)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("active");

		active.close(NOW.plusSeconds(1));
		CardBalanceAccountRules.assertCanOpen(studentId, academyId, List.of(active));
	}

	@Test
	void balanceBreakdownKeepsActualLedgerDisplayAndShortageAsDifferentValues() {
		BalanceBreakdown mismatch = BalanceBreakdown.calculate(
				KrwAmount.nonNegative(70), KrwAmount.nonNegative(100));

		assertThat(mismatch.actualCardBalance()).isEqualTo(KrwAmount.of(70));
		assertThat(mismatch.activeWishTotal()).isEqualTo(KrwAmount.of(100));
		assertThat(mismatch.ledgerAvailable()).isEqualTo(KrwAmount.of(-30));
		assertThat(mismatch.displayAvailable()).isEqualTo(KrwAmount.zero());
		assertThat(mismatch.unresolvedShortage()).isEqualTo(KrwAmount.of(30));
	}
}
