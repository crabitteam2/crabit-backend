package com.crabit.backend.wish;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardBalanceObservationService {

	private static final List<WishState> ACTIVE_STATES =
			List.of(WishState.IN_PROGRESS, WishState.AMOUNT_REACHED);

	private final CardBalanceAccountRepository accountRepository;
	private final BalanceObservationRepository observationRepository;
	private final LedgerEventRepository eventRepository;
	private final WishRepository wishRepository;
	private final BalanceAdjustmentCaseRepository adjustmentRepository;
	private final MismatchNotificationOutboxRepository outboxRepository;

	public CardBalanceObservationService(
			CardBalanceAccountRepository accountRepository,
			BalanceObservationRepository observationRepository,
			LedgerEventRepository eventRepository,
			WishRepository wishRepository,
			BalanceAdjustmentCaseRepository adjustmentRepository,
			MismatchNotificationOutboxRepository outboxRepository) {
		this.accountRepository = accountRepository;
		this.observationRepository = observationRepository;
		this.eventRepository = eventRepository;
		this.wishRepository = wishRepository;
		this.adjustmentRepository = adjustmentRepository;
		this.outboxRepository = outboxRepository;
	}

	@Transactional
	public BalanceObservation recordSuccess(
			UUID accountId,
			BalanceLookupMethod lookupMethod,
			KrwAmount actualBalance,
			Instant observedAt) {
		CardBalanceAccount account = lockAccount(accountId);
		long accountLookupVersion = account.beginBalanceLookup();
		Optional<BalanceObservation> previous = latestSuccess(accountId);
		KrwAmount balance = Objects.requireNonNull(actualBalance, "actualBalance");
		KrwAmount delta = previous.map(BalanceObservation::actualCardBalance)
				.map(balance::minus)
				.orElse(balance);
		LedgerEvent transientChangeEvent = delta.isZero() ? null
				: LedgerEvent.cardBalanceChange(account, delta, observedAt);
		LedgerEvent changeEvent = transientChangeEvent == null
				? null : eventRepository.save(transientChangeEvent);
		BalanceObservation observation = previous
				.map(value -> BalanceObservation.succeeded(
						value, lookupMethod, balance, changeEvent, observedAt,
						accountLookupVersion))
				.orElseGet(() -> BalanceObservation.firstSucceeded(
						accountId, lookupMethod, balance, changeEvent, observedAt,
						accountLookupVersion));
		observationRepository.save(observation);
		reconcileMismatch(accountId, changeEvent, balance, observedAt);
		return observation;
	}

	@Transactional
	public BalanceObservation recordFailure(
			UUID accountId,
			BalanceLookupMethod lookupMethod,
			String failureCode,
			Instant observedAt) {
		CardBalanceAccount account = lockAccount(accountId);
		long accountLookupVersion = account.beginBalanceLookup();
		return observationRepository.save(BalanceObservation.failed(
				accountId, lookupMethod, failureCode, observedAt, accountLookupVersion));
	}

	private void reconcileMismatch(
			UUID accountId,
			LedgerEvent changeEvent,
			KrwAmount actualBalance,
			Instant observedAt) {
		KrwAmount activeTotal = wishRepository
				.findByAccountIdAndDeletedAtIsNullAndStateIn(accountId, ACTIVE_STATES)
				.stream()
				.map(Wish::amount)
				.reduce(KrwAmount.zero(), KrwAmount::plus);
		KrwAmount shortage = BalanceBreakdown.calculate(actualBalance, activeTotal)
				.unresolvedShortage();
		Optional<BalanceAdjustmentCase> current =
				adjustmentRepository.lockSingleOpenByAccountId(accountId);
		if (shortage.isPositive()) {
			if (current.isPresent()) {
				if (changeEvent != null) {
					current.orElseThrow().record(changeEvent);
				}
				return;
			}
			if (changeEvent == null || !changeEvent.accountDelta().isNegative()) {
				throw new IllegalStateException(
						"A new mismatch must be opened by its observed card-balance decrease");
			}
			BalanceAdjustmentCase opened = BalanceAdjustmentCase.open(
					changeEvent, shortage, observedAt);
			adjustmentRepository.save(opened);
			outboxRepository.save(new MismatchNotificationOutbox(opened.id(), observedAt));
			return;
		}
		if (current.isPresent() && changeEvent != null) {
			current.orElseThrow().resolve(changeEvent, observedAt);
		}
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

	private Optional<BalanceObservation> latestSuccess(UUID accountId) {
		return observationRepository.findFirstByAccountIdAndStatusOrderByObservedAtDescIdDesc(
				accountId, BalanceObservationStatus.SUCCEEDED);
	}
}
