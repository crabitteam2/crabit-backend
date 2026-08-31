package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.account.CardBalanceAccount;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WishDomainInvariantTest {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
	private final UUID accountId = UUID.randomUUID();
	private final UUID academyId = UUID.randomUUID();

	@Test
	void newWishStartsPrivateEmptyAndPermanentlyBoundToItsAccountAndAcademy() {
		LocalDate targetDate = LocalDate.of(2026, 12, 31);
		Wish wish = Wish.create(
				accountId, academyId, "노트북", KrwAmount.positive(100_000), targetDate, NOW);

		assertThat(wish.accountId()).isEqualTo(accountId);
		assertThat(wish.academyId()).isEqualTo(academyId);
		assertThat(wish.amount()).isEqualTo(KrwAmount.zero());
		assertThat(wish.state()).isEqualTo(WishState.IN_PROGRESS);
		assertThat(wish.visibility()).isEqualTo(WishVisibility.PRIVATE);
		assertThat(wish.targetDate()).isEqualTo(targetDate);
		assertThat(wish.createdAt()).isEqualTo(NOW);
		assertThat(wish.completedAt()).isNull();
		assertThat(wish.abandonedAt()).isNull();
		assertThat(wish.closedAt()).isNull();
		assertThat(wish.actualDuration()).isEmpty();
		assertThat(wish.version()).isZero();
		assertThat(wish.isActive()).isTrue();
	}

	@Test
	void normalizesPurposeToTrimmedNfcAndCountsUnicodeCharacters() {
		Wish normalized = Wish.create(
				accountId, academyId, "  Cafe\u0301  with  spaces  ",
				KrwAmount.positive(100), NOW);
		Wish twoHundredEmoji = Wish.create(
				accountId, academyId, "😀".repeat(200), KrwAmount.positive(100), NOW);

		assertThat(normalized.purpose()).isEqualTo("Café  with  spaces");
		assertThat(twoHundredEmoji.purpose().codePointCount(0, twoHundredEmoji.purpose().length()))
				.isEqualTo(200);
		assertThatThrownBy(() -> Wish.create(
				accountId, academyId, "😀".repeat(201), KrwAmount.positive(100), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("200");
	}

	@Test
	void removesUnicodeSpaceSeparatorBoundariesAndPreservesInternalSpaces() {
		Wish wish = Wish.create(
				accountId, academyId, "\u00A0  비상금\u2002계획  \u00A0",
				KrwAmount.positive(100), NOW);

		assertThat(wish.purpose()).isEqualTo("비상금\u2002계획");
	}

	@Test
	void rejectsPurposeThatIsEmptyAfterUnicodeSpaceSeparatorRemoval() {
		assertThatThrownBy(() -> Wish.create(
				accountId, academyId, "\u00A0 \u2002", KrwAmount.positive(100), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("1 to 200");
	}

	@Test
	void rejectsPurposeControlsFormatsAndUnicodeLineSeparators() {
		assertThatThrownBy(() -> Wish.create(
				accountId, academyId, "line one\nline two", KrwAmount.positive(100), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("control");
		assertThatThrownBy(() -> Wish.create(
				accountId, academyId, "hidden\u200Bformat", KrwAmount.positive(100), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("control");
		assertThatThrownBy(() -> Wish.create(
				accountId, academyId, "line\u2028separator", KrwAmount.positive(100), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("control");
		assertThatThrownBy(() -> Wish.create(
				accountId, academyId, "paragraph\u2029separator", KrwAmount.positive(100), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("control");
	}

	@Test
	void activeWishCanChangeOrClearItsOptionalTargetDateButTerminalWishCannot() {
		Wish wish = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);
		LocalDate changedTargetDate = LocalDate.of(2026, 10, 1);

		wish.changeTargetDate(changedTargetDate);
		assertThat(wish.targetDate()).isEqualTo(changedTargetDate);

		wish.changeTargetDate(null);
		assertThat(wish.targetDate()).isNull();

		wish.allocate(KrwAmount.positive(100));
		wish.complete(NOW.plus(Duration.ofDays(2)));
		assertThatThrownBy(() -> wish.changeTargetDate(LocalDate.of(2026, 11, 1)))
				.isInstanceOf(IllegalStateException.class);
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
		Instant completionTime = NOW.plus(Duration.ofDays(3));
		assertThatThrownBy(() -> wish.complete(completionTime)).isInstanceOf(IllegalStateException.class);

		wish.allocate(KrwAmount.positive(100));
		KrwAmount returned = wish.complete(completionTime);

		assertThat(returned).isEqualTo(KrwAmount.of(100));
		assertThat(wish.amount()).isEqualTo(KrwAmount.zero());
		assertThat(wish.state()).isEqualTo(WishState.COMPLETED);
		assertThat(wish.completedAt()).isEqualTo(completionTime);
		assertThat(wish.closedAt()).isEqualTo(completionTime);
		assertThat(wish.actualDuration()).contains(Duration.ofDays(3));
		assertThat(wish.isActive()).isFalse();
		assertThatThrownBy(() -> wish.withdraw(KrwAmount.positive(1)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void completionCannotBeRecordedBeforeCreation() {
		Wish wish = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);
		wish.allocate(KrwAmount.positive(100));

		assertThatThrownBy(() -> wish.complete(NOW.minusSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("creation");
	}

	@Test
	void abandonmentForcesPrivateAndAllowsLaterVisibilityMutation() {
		Wish wish = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);
		wish.changeVisibility(WishVisibility.ACADEMY);
		wish.allocate(KrwAmount.positive(40));

		Instant abandonedAt = NOW.plusSeconds(45);
		wish.abandon(abandonedAt);

		assertThat(wish.visibility()).isEqualTo(WishVisibility.PRIVATE);
		assertThat(wish.abandonedAt()).isEqualTo(abandonedAt);
		assertThat(wish.closedAt()).isEqualTo(abandonedAt);
		assertThat(wish.completedAt()).isNull();
		wish.changeVisibility(WishVisibility.FRIENDS);
		assertThat(wish.visibility()).isEqualTo(WishVisibility.FRIENDS);
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
		wish.complete(NOW.plusSeconds(30));

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
		Instant abandonedAt = NOW.plusSeconds(30);
		wish.abandon(abandonedAt);

		KrwAmount returned = wish.tombstone(NOW.plusSeconds(60));

		assertThat(returned).isEqualTo(KrwAmount.zero());
		assertThat(wish.state()).isEqualTo(WishState.ABANDONED);
		assertThat(wish.closedAt()).isEqualTo(abandonedAt);
		assertThat(wish.isDeleted()).isTrue();
		assertThat(wish.purposeSnapshot()).isEqualTo("노트북");
	}

	@Test
	void accountScopedTransferAtomicallyMovesMoneyAndCarriesTwoBalancedEffects() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		Wish source = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);
		Wish destination = Wish.create(accountId, academyId, "여행", KrwAmount.positive(100), NOW);
		source.allocate(KrwAmount.positive(70));
		destination.allocate(KrwAmount.positive(80));

		LedgerEvent transfer = LedgerEvent.transfer(
				account,
				source,
				destination,
				KrwAmount.positive(20),
				NOW);

		assertThat(source.amount()).isEqualTo(KrwAmount.of(50));
		assertThat(source.state()).isEqualTo(WishState.IN_PROGRESS);
		assertThat(destination.amount()).isEqualTo(KrwAmount.of(100));
		assertThat(destination.state()).isEqualTo(WishState.AMOUNT_REACHED);
		assertThat(transfer.type()).isEqualTo(LedgerEventType.WISH_TRANSFER);
		assertThat(transfer.accountDelta()).isEqualTo(KrwAmount.zero());
		assertThat(transfer.wishEffects()).hasSize(2);
		assertThat(transfer.wishEffects()).extracting(effect -> effect.delta().won())
				.containsExactlyInAnyOrder(-20L, 20L);
		assertThat(transfer.wishEffects()).extracting(LedgerWishEffect::wishId)
				.containsExactlyInAnyOrder(source.id(), destination.id());
		assertThat(transfer.wishEffects()).extracting(LedgerWishEffect::eventId)
				.containsOnly(transfer.id());
		assertThat(transfer.wishEffects()).extracting(LedgerWishEffect::accountId)
				.containsOnly(accountId);
	}

	@Test
	void transferRejectsWishesOutsideTheLockedAccountScope() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		Wish source = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);
		source.allocate(KrwAmount.positive(50));
		Wish anotherAccount = Wish.create(UUID.randomUUID(), academyId,
				"여행", KrwAmount.positive(100), NOW);
		Wish anotherAcademy = Wish.create(accountId, UUID.randomUUID(),
				"자전거", KrwAmount.positive(100), NOW);

		assertThatThrownBy(() -> LedgerEvent.transfer(
				account, source, anotherAccount, KrwAmount.positive(30), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("account");
		assertThatThrownBy(() -> LedgerEvent.transfer(
				account, source, anotherAcademy, KrwAmount.positive(30), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("academy");
	}

	@Test
	void transferRejectsInsufficientSourceWithoutMutatingEitherWish() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		Wish source = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);
		Wish destination = Wish.create(accountId, academyId, "여행", KrwAmount.positive(100), NOW);
		source.allocate(KrwAmount.positive(20));
		destination.allocate(KrwAmount.positive(10));

		assertThatThrownBy(() -> LedgerEvent.transfer(
				account, source, destination, KrwAmount.positive(30), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("source");
		assertThat(source.amount()).isEqualTo(KrwAmount.of(20));
		assertThat(destination.amount()).isEqualTo(KrwAmount.of(10));
	}

	@Test
	void transferRejectsDestinationOverflowWithoutMutatingEitherWish() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		Wish source = Wish.create(accountId, academyId, "노트북", KrwAmount.positive(100), NOW);
		Wish destination = Wish.create(accountId, academyId, "여행", KrwAmount.positive(100), NOW);
		source.allocate(KrwAmount.positive(60));
		destination.allocate(KrwAmount.positive(90));

		assertThatThrownBy(() -> LedgerEvent.transfer(
				account, source, destination, KrwAmount.positive(20), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("destination");
		assertThat(source.amount()).isEqualTo(KrwAmount.of(60));
		assertThat(destination.amount()).isEqualTo(KrwAmount.of(90));
	}

	@Test
	void adjustmentCaseRetainsEveryAccountScopedLedgerEventInTheMismatchEpisode() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		LedgerEvent openingEvent = LedgerEvent.cardBalanceChange(
				account, KrwAmount.of(-30), NOW);
		BalanceObservation openingObservation = observationForOpeningDecrease(
				account, openingEvent);
		BalanceAdjustmentCase adjustmentCase = BalanceAdjustmentCase.open(
				openingObservation, openingEvent, KrwAmount.positive(30), NOW);
		LedgerEvent middleEvent = LedgerEvent.cardBalanceChange(
				account, KrwAmount.of(-10), NOW.plusSeconds(20));
		LedgerEvent resolutionEvent = LedgerEvent.cardBalanceChange(
				account, KrwAmount.positive(40), NOW.plusSeconds(40));

		adjustmentCase.record(middleEvent);
		adjustmentCase.resolve(resolutionEvent, NOW.plusSeconds(60));

		assertThat(adjustmentCase.ledgerEvents()).extracting(LedgerEvent::id)
				.containsExactly(openingEvent.id(), middleEvent.id(), resolutionEvent.id());
		assertThat(adjustmentCase.isOpen()).isFalse();
	}

	@Test
	void adjustmentCaseRejectsAnyEpisodeEventFromAnotherAccount() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		LedgerEvent openingEvent = LedgerEvent.cardBalanceChange(
				account, KrwAmount.of(-30), NOW);
		BalanceObservation openingObservation = observationForOpeningDecrease(
				account, openingEvent);
		BalanceAdjustmentCase adjustmentCase = BalanceAdjustmentCase.open(
				openingObservation, openingEvent, KrwAmount.positive(30), NOW);
		LedgerEvent foreignResolution = LedgerEvent.cardBalanceChange(
				accountFor(UUID.randomUUID(), academyId), KrwAmount.positive(30), NOW.plusSeconds(30));

		assertThatThrownBy(() -> adjustmentCase.record(foreignResolution))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("account");
		assertThatThrownBy(() -> adjustmentCase.resolve(foreignResolution, NOW.plusSeconds(60)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("account");
	}

	@Test
	void adjustmentCaseIsChronologicalOpenOnlyAndContainsOpeningAndResolutionRoles() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		LedgerEvent opening = LedgerEvent.cardBalanceChange(account, KrwAmount.of(-30), NOW);
		BalanceObservation openingObservation = observationForOpeningDecrease(account, opening);
		BalanceAdjustmentCase adjustment = BalanceAdjustmentCase.open(
				openingObservation, opening, KrwAmount.positive(30), NOW);

		LedgerEvent earlier = LedgerEvent.cardBalanceChange(
				account, KrwAmount.of(-1), NOW.minusSeconds(1));
		assertThatThrownBy(() -> adjustment.record(earlier))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("precede");

		LedgerEvent resolution = LedgerEvent.cardBalanceChange(
				account, KrwAmount.positive(30), NOW.plusSeconds(10));
		assertThatThrownBy(() -> adjustment.resolve(resolution, NOW.plusSeconds(9)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("follow");

		adjustment.resolve(resolution, NOW.plusSeconds(10));
		assertThat(adjustment.eventLinks()).extracting(BalanceAdjustmentCaseEvent::role)
				.containsExactly(
						BalanceAdjustmentEventRole.OPENING_DECREASE,
						BalanceAdjustmentEventRole.RESOLUTION);
		assertThat(adjustment.eventLinks()).extracting(BalanceAdjustmentCaseEvent::sequenceNumber)
				.containsExactly(0, 1);

		LedgerEvent late = LedgerEvent.cardBalanceChange(
				account, KrwAmount.of(-1), NOW.plusSeconds(11));
		assertThatThrownBy(() -> adjustment.record(late))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("immutable");
	}

	@Test
	void adjustmentCaseCanOnlyOpenFromTheExactCardBalanceChange() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		LedgerEvent exactDecrease = LedgerEvent.cardBalanceChange(
				account, KrwAmount.of(-30), NOW);
		BalanceObservation openingObservation = observationForOpeningDecrease(
				account, exactDecrease);
		assertThatThrownBy(() -> BalanceAdjustmentCase.open(
				openingObservation, transferFor(accountId, academyId),
				KrwAmount.positive(1), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Card Balance Change");
		assertThatThrownBy(() -> BalanceAdjustmentCase.open(
				openingObservation,
				LedgerEvent.cardBalanceChange(account, KrwAmount.positive(1), NOW),
				KrwAmount.positive(1), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("negative");
	}

	@Test
	void onlyTheFirstSuccessfulObservationMayOpenWithoutADecrease() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		BalanceObservation first = BalanceObservation.firstSucceeded(
				account.id(), BalanceLookupMethod.USER_REQUESTED,
				KrwAmount.zero(), NOW);

		BalanceAdjustmentCase initialShortage = BalanceAdjustmentCase.open(
				first, null, KrwAmount.positive(30), NOW);

		assertThat(initialShortage.openingBalanceObservationId()).isEqualTo(first.id());
		assertThat(initialShortage.openingEventId()).isNull();
		assertThat(initialShortage.eventLinks()).isEmpty();

		BalanceObservation laterUnchanged = BalanceObservation.succeeded(
				first, BalanceLookupMethod.USER_REQUESTED, KrwAmount.zero(), null,
				NOW.plusSeconds(1));
		assertThatThrownBy(() -> BalanceAdjustmentCase.open(
				laterUnchanged, null, KrwAmount.positive(30), NOW.plusSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("first successful observation");
	}

	@Test
	void balanceObservationCapturesLookupAndExactChangeEventProvenance() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		LedgerEvent firstDeposit = LedgerEvent.cardBalanceChange(
				account, KrwAmount.positive(100), NOW);
		BalanceObservation first = BalanceObservation.firstSucceeded(
				accountId, BalanceLookupMethod.USER_REQUESTED, KrwAmount.nonNegative(100),
				firstDeposit, NOW);
		LedgerEvent change = LedgerEvent.cardBalanceChange(
				account, KrwAmount.of(-30), NOW.plusSeconds(1));

		BalanceObservation next = BalanceObservation.succeeded(
				first, BalanceLookupMethod.USER_REQUESTED, KrwAmount.nonNegative(70),
				change, NOW.plusSeconds(1));

		assertThat(first.isFirstConnection()).isTrue();
		assertThat(first.balanceChangeEventId()).isEqualTo(firstDeposit.id());
		assertThat(next.lookupMethod()).isEqualTo(BalanceLookupMethod.USER_REQUESTED);
		assertThat(next.previousSuccessfulObservationId()).isEqualTo(first.id());
		assertThat(next.balanceChangeEventId()).isEqualTo(change.id());
	}

	@Test
	void balanceObservationRejectsMissingWrongOrSpuriousChangeEvents() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		LedgerEvent firstDeposit = LedgerEvent.cardBalanceChange(
				account, KrwAmount.positive(100), NOW);
		BalanceObservation first = BalanceObservation.firstSucceeded(
				accountId, BalanceLookupMethod.AUTO_DAILY, KrwAmount.nonNegative(100),
				firstDeposit, NOW);

		assertThatThrownBy(() -> BalanceObservation.succeeded(
				first, BalanceLookupMethod.PRE_DEPOSIT, KrwAmount.nonNegative(90), null,
				NOW.plusSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("change event");

		LedgerEvent spurious = LedgerEvent.cardBalanceChange(
				account, KrwAmount.positive(1), NOW.plusSeconds(1));
		assertThatThrownBy(() -> BalanceObservation.succeeded(
				first, BalanceLookupMethod.PRE_DEPOSIT, KrwAmount.nonNegative(100), spurious,
				NOW.plusSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("zero");

		LedgerEvent wrongDelta = LedgerEvent.cardBalanceChange(
				account, KrwAmount.of(-9), NOW.plusSeconds(1));
		assertThatThrownBy(() -> BalanceObservation.succeeded(
				first, BalanceLookupMethod.PRE_DEPOSIT, KrwAmount.nonNegative(90), wrongDelta,
				NOW.plusSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly");

		CardBalanceAccount foreignAccount = accountFor(UUID.randomUUID(), academyId);
		LedgerEvent foreignChange = LedgerEvent.cardBalanceChange(
				foreignAccount, KrwAmount.of(-10), NOW.plusSeconds(1));
		assertThatThrownBy(() -> BalanceObservation.succeeded(
				first, BalanceLookupMethod.PRE_DEPOSIT, KrwAmount.nonNegative(90), foreignChange,
				NOW.plusSeconds(1)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("account");
	}

	@Test
	void firstNonzeroBalanceRequiresItsExactDepositFromZeroAtTheObservationTime() {
		CardBalanceAccount account = accountFor(accountId, academyId);
		assertThatThrownBy(() -> BalanceObservation.firstSucceeded(
				accountId, BalanceLookupMethod.USER_REQUESTED, KrwAmount.positive(100), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("change event");

		LedgerEvent wrongDelta = LedgerEvent.cardBalanceChange(
				account, KrwAmount.positive(99), NOW);
		assertThatThrownBy(() -> BalanceObservation.firstSucceeded(
				accountId, BalanceLookupMethod.USER_REQUESTED, KrwAmount.positive(100),
				wrongDelta, NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly");

		LedgerEvent wrongTime = LedgerEvent.cardBalanceChange(
				account, KrwAmount.positive(100), NOW.minusSeconds(1));
		assertThatThrownBy(() -> BalanceObservation.firstSucceeded(
				accountId, BalanceLookupMethod.USER_REQUESTED, KrwAmount.positive(100),
				wrongTime, NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("time");
	}

	@Test
	void balanceObservationPreservesZeroChangeAndFailureProvenance() {
		BalanceObservation first = BalanceObservation.firstSucceeded(
				accountId, BalanceLookupMethod.USER_REQUESTED, KrwAmount.zero(), NOW);
		BalanceObservation unchanged = BalanceObservation.succeeded(
				first, BalanceLookupMethod.AUTO_DAILY, KrwAmount.zero(), null,
				NOW.plusSeconds(1));
		BalanceObservation failed = BalanceObservation.failed(
				accountId, BalanceLookupMethod.USER_REQUESTED, "CARD_TIMEOUT", NOW.plusSeconds(2));

		assertThat(unchanged.previousSuccessfulObservationId()).isEqualTo(first.id());
		assertThat(unchanged.balanceChangeEventId()).isNull();
		assertThat(failed.status()).isEqualTo(BalanceObservationStatus.FAILED);
		assertThat(failed.lookupMethod()).isEqualTo(BalanceLookupMethod.USER_REQUESTED);
		assertThat(failed.failureCode()).isEqualTo("CARD_TIMEOUT");
		assertThat(failed.balanceChangeEventId()).isNull();
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

	private LedgerEvent transferFor(UUID scopedAccountId, UUID scopedAcademyId) {
		CardBalanceAccount account = accountFor(scopedAccountId, scopedAcademyId);
		Wish source = Wish.create(scopedAccountId, scopedAcademyId,
				"출발", KrwAmount.positive(100), NOW);
		Wish destination = Wish.create(scopedAccountId, scopedAcademyId,
				"도착", KrwAmount.positive(100), NOW);
		source.allocate(KrwAmount.positive(1));
		return LedgerEvent.transfer(account, source, destination, KrwAmount.positive(1), NOW);
	}

	private BalanceObservation observationForOpeningDecrease(
			CardBalanceAccount account, LedgerEvent openingDecrease) {
		Instant previousTime = openingDecrease.occurredAt().minusSeconds(1);
		LedgerEvent initialIncrease = LedgerEvent.cardBalanceChange(
				account, KrwAmount.positive(100), previousTime);
		BalanceObservation previous = BalanceObservation.firstSucceeded(
				account.id(), BalanceLookupMethod.USER_REQUESTED,
				KrwAmount.nonNegative(100), initialIncrease, previousTime);
		return BalanceObservation.succeeded(
				previous, BalanceLookupMethod.USER_REQUESTED,
				KrwAmount.nonNegative(100 + openingDecrease.accountDelta().won()),
				openingDecrease, openingDecrease.occurredAt());
	}

	private CardBalanceAccount accountFor(UUID scopedAccountId, UUID scopedAcademyId) {
		return CardBalanceAccount.reconstitute(
				scopedAccountId, UUID.randomUUID(), scopedAcademyId, NOW, null);
	}
}
