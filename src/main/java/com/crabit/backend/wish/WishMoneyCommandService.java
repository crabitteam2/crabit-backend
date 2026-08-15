package com.crabit.backend.wish;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WishMoneyCommandService {

	private static final List<WishState> ACTIVE_STATES =
			List.of(WishState.IN_PROGRESS, WishState.AMOUNT_REACHED);

	private final CardBalanceAccountRepository accountRepository;
	private final WishRepository wishRepository;
	private final LedgerEventRepository eventRepository;
	private final BalanceObservationRepository observationRepository;
	private final BalanceAdjustmentCaseRepository adjustmentRepository;
	private final SharedCardRepository sharedCardRepository;

	public WishMoneyCommandService(
			CardBalanceAccountRepository accountRepository,
			WishRepository wishRepository,
			LedgerEventRepository eventRepository,
			BalanceObservationRepository observationRepository,
			BalanceAdjustmentCaseRepository adjustmentRepository,
			SharedCardRepository sharedCardRepository) {
		this.accountRepository = accountRepository;
		this.wishRepository = wishRepository;
		this.eventRepository = eventRepository;
		this.observationRepository = observationRepository;
		this.adjustmentRepository = adjustmentRepository;
		this.sharedCardRepository = sharedCardRepository;
	}

	@Transactional
	public WishMoneyCommandResult deposit(
			UUID accountId,
			UUID wishId,
			KrwAmount amount,
			DepositBalanceProof balanceProof,
			Instant occurredAt) {
		CardBalanceAccount account = lockAccount(accountId);
		Optional<BalanceAdjustmentCase> openCase = lockOpenCase(accountId);
		if (openCase.isPresent()) {
			throw new IllegalStateException("Wish deposits are blocked while balance adjustment is open");
		}
		Wish wish = lockWishes(accountId, List.of(wishId)).get(wishId);
		KrwAmount allocation = requirePositive(amount);
		BalanceObservation depositObservation = requireCurrentDepositObservation(
				account, Objects.requireNonNull(balanceProof, "balanceProof"), occurredAt);
		KrwAmount actualBalance = depositObservation.actualCardBalance();
		KrwAmount activeTotal = activeWishTotal(accountId);
		if (allocation.compareTo(actualBalance.minus(activeTotal)) > 0) {
			throw new IllegalArgumentException("Wish deposit exceeds available balance");
		}
		wish.allocate(allocation);
		LedgerEvent event = eventRepository.save(
				LedgerEvent.wishDeposit(
						account, wish, allocation, depositObservation, occurredAt));
		synchronizeSharedCard(wish, occurredAt);
		return result(event, Optional.empty());
	}

	@Transactional
	public WishMoneyCommandResult withdraw(
			UUID accountId, UUID wishId, KrwAmount amount, Instant occurredAt) {
		CardBalanceAccount account = lockAccount(accountId);
		Optional<BalanceAdjustmentCase> openCase = lockOpenCase(accountId);
		Wish wish = lockWishes(accountId, List.of(wishId)).get(wishId);
		KrwAmount withdrawal = requirePositive(amount);
		wish.withdraw(withdrawal);
		LedgerEvent event = eventRepository.save(LedgerEvent.wishWithdrawal(
				account, wish, withdrawal, LedgerEventType.WISH_WITHDRAWAL, occurredAt));
		Optional<BalanceAdjustmentCase> adjustment = recordAndMaybeResolve(openCase, event, occurredAt);
		synchronizeSharedCard(wish, occurredAt);
		return result(event, adjustment);
	}

	@Transactional
	public WishMoneyCommandResult transfer(
			UUID accountId,
			UUID sourceWishId,
			UUID destinationWishId,
			KrwAmount amount,
			Instant occurredAt) {
		CardBalanceAccount account = lockAccount(accountId);
		if (lockOpenCase(accountId).isPresent()) {
			throw new IllegalStateException("Wish transfer is blocked while balance adjustment is open");
		}
		Map<UUID, Wish> wishes = lockWishes(accountId, List.of(sourceWishId, destinationWishId));
		Wish source = wishes.get(sourceWishId);
		Wish destination = wishes.get(destinationWishId);
		LedgerEvent event = eventRepository.save(LedgerEvent.transfer(
				account, source, destination, requirePositive(amount), occurredAt));
		synchronizeSharedCard(source, occurredAt);
		synchronizeSharedCard(destination, occurredAt);
		return result(event, Optional.empty());
	}

	@Transactional
	public WishMoneyCommandResult complete(UUID accountId, UUID wishId, Instant occurredAt) {
		CardBalanceAccount account = lockAccount(accountId);
		Optional<BalanceAdjustmentCase> openCase = lockOpenCase(accountId);
		Wish wish = lockWishes(accountId, List.of(wishId)).get(wishId);
		KrwAmount returned = wish.complete(occurredAt);
		LedgerEvent event = eventRepository.save(LedgerEvent.wishWithdrawal(
				account, wish, returned, LedgerEventType.WISH_COMPLETION_RETURN, occurredAt));
		Optional<BalanceAdjustmentCase> adjustment = recordAndMaybeResolve(openCase, event, occurredAt);
		synchronizeSharedCard(wish, occurredAt);
		return result(event, adjustment);
	}

	@Transactional
	public WishMoneyCommandResult abandon(UUID accountId, UUID wishId, Instant occurredAt) {
		CardBalanceAccount account = lockAccount(accountId);
		Optional<BalanceAdjustmentCase> openCase = lockOpenCase(accountId);
		Wish wish = lockWishes(accountId, List.of(wishId)).get(wishId);
		KrwAmount returned = wish.abandon();
		Optional<LedgerEvent> event = returned.isZero() ? Optional.empty() : Optional.of(
				eventRepository.save(LedgerEvent.wishWithdrawal(account, wish, returned,
						LedgerEventType.WISH_ABANDONMENT_RETURN, occurredAt)));
		Optional<BalanceAdjustmentCase> adjustment = event
				.flatMap(value -> recordAndMaybeResolve(openCase, value, occurredAt));
		synchronizeSharedCard(wish, occurredAt);
		return new WishMoneyCommandResult(event, adjustment);
	}

	@Transactional
	public WishMoneyCommandResult tombstone(UUID accountId, UUID wishId, Instant occurredAt) {
		CardBalanceAccount account = lockAccount(accountId);
		Optional<BalanceAdjustmentCase> openCase = lockOpenCase(accountId);
		Wish wish = lockWishes(accountId, List.of(wishId)).get(wishId);
		KrwAmount returned = wish.tombstone(occurredAt);
		Optional<LedgerEvent> event = returned.isZero() ? Optional.empty() : Optional.of(
				eventRepository.save(LedgerEvent.wishWithdrawal(account, wish, returned,
						LedgerEventType.WISH_DELETION_RETURN, occurredAt)));
		Optional<BalanceAdjustmentCase> adjustment = event
				.flatMap(value -> recordAndMaybeResolve(openCase, value, occurredAt));
		synchronizeSharedCard(wish, occurredAt);
		return new WishMoneyCommandResult(event, adjustment);
	}

	private CardBalanceAccount lockAccount(UUID accountId) {
		CardBalanceAccount account = accountRepository.lockById(
				Objects.requireNonNull(accountId, "accountId"))
				.orElseThrow(() -> new IllegalArgumentException("Card Balance Account not found"));
		if (!account.isActive()) {
			throw new IllegalStateException("Card Balance Account is closed");
		}
		return account;
	}

	private Map<UUID, Wish> lockWishes(UUID accountId, Collection<UUID> wishIds) {
		List<UUID> distinctIds = wishIds.stream().distinct().sorted(Comparator.naturalOrder()).toList();
		List<Wish> locked = wishRepository.lockByAccountIdAndIds(accountId, distinctIds);
		if (locked.size() != distinctIds.size()) {
			throw new IllegalArgumentException("Every Wish must belong to the locked account");
		}
		return locked.stream().collect(Collectors.toMap(Wish::id, Function.identity()));
	}

	private Optional<BalanceAdjustmentCase> lockOpenCase(UUID accountId) {
		return adjustmentRepository.lockSingleOpenByAccountId(accountId);
	}

	private Optional<KrwAmount> latestSuccessfulBalance(UUID accountId) {
		return observationRepository
				.findFirstByAccountIdAndStatusOrderByObservedAtDescIdDesc(
						accountId, BalanceObservationStatus.SUCCEEDED)
				.map(BalanceObservation::actualCardBalance);
	}

	private BalanceObservation requireCurrentDepositObservation(
			CardBalanceAccount account, DepositBalanceProof proof, Instant occurredAt) {
		BalanceObservation observation = observationRepository.findById(proof.observationId())
				.orElseThrow(() -> new IllegalArgumentException("Deposit balance observation not found"));
		if (!account.id().equals(observation.accountId())
				|| observation.status() != BalanceObservationStatus.SUCCEEDED
				|| observation.lookupMethod() != BalanceLookupMethod.PRE_DEPOSIT) {
			throw new IllegalArgumentException(
					"Deposit requires the current successful PRE_DEPOSIT observation");
		}
		if (observation.accountLookupVersion() == null
				|| observation.accountLookupVersion() != proof.accountLookupVersion()
				|| account.balanceLookupVersion() != proof.accountLookupVersion()) {
			throw new IllegalStateException(
					"Deposit balance proof is stale because a newer lookup attempt exists");
		}
		if (observation.observedAt().isAfter(Objects.requireNonNull(occurredAt, "occurredAt"))) {
			throw new IllegalArgumentException("Deposit cannot precede its balance observation");
		}
		if (eventRepository.existsByDepositBalanceObservationId(observation.id())) {
			throw new IllegalStateException(
					"PRE_DEPOSIT observation already authorized a Wish deposit");
		}
		return observation;
	}

	private KrwAmount activeWishTotal(UUID accountId) {
		return wishRepository.findByAccountIdAndDeletedAtIsNullAndStateIn(accountId, ACTIVE_STATES)
				.stream()
				.map(Wish::amount)
				.reduce(KrwAmount.zero(), KrwAmount::plus);
	}

	private Optional<BalanceAdjustmentCase> recordAndMaybeResolve(
			Optional<BalanceAdjustmentCase> openCase,
			LedgerEvent event,
			Instant occurredAt) {
		if (openCase.isEmpty()) {
			return Optional.empty();
		}
		BalanceAdjustmentCase adjustment = openCase.orElseThrow();
		Optional<KrwAmount> actualBalance = latestSuccessfulBalance(event.accountId());
		if (actualBalance.isPresent()
				&& !BalanceBreakdown.calculate(actualBalance.orElseThrow(),
						activeWishTotal(event.accountId())).unresolvedShortage().isPositive()) {
			adjustment.resolve(event, occurredAt);
		} else {
			adjustment.record(event);
		}
		return Optional.of(adjustment);
	}

	private void synchronizeSharedCard(Wish wish, Instant updatedAt) {
		if (wish.isDeleted() || wish.state() == WishState.ABANDONED
				|| wish.visibility() == WishVisibility.PRIVATE) {
			sharedCardRepository.findByWishId(wish.id()).ifPresent(sharedCardRepository::delete);
			return;
		}
		SharedCardKind kind = wish.state() == WishState.COMPLETED
				? SharedCardKind.COMPLETION : SharedCardKind.PROGRESS;
		SharedCard card = sharedCardRepository.findByWishId(wish.id())
				.orElseGet(() -> new SharedCard(wish.id(), kind, wish.visibility(), updatedAt));
		card.refresh(kind, wish.visibility(), updatedAt);
		sharedCardRepository.save(card);
	}

	private static KrwAmount requirePositive(KrwAmount amount) {
		if (!Objects.requireNonNull(amount, "amount").isPositive()) {
			throw new IllegalArgumentException("Money command amount must be positive");
		}
		return amount;
	}

	private static WishMoneyCommandResult result(
			LedgerEvent event, Optional<BalanceAdjustmentCase> adjustment) {
		return new WishMoneyCommandResult(Optional.of(event), adjustment);
	}
}
