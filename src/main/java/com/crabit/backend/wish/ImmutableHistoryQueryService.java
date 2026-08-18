package com.crabit.backend.wish;

import static com.crabit.backend.wish.ImmutableHistoryModels.AccountCardBalanceChange;
import static com.crabit.backend.wish.ImmutableHistoryModels.AccountFundMovement;
import static com.crabit.backend.wish.ImmutableHistoryModels.AccountFundMovementPage;
import static com.crabit.backend.wish.ImmutableHistoryModels.AccountWishAbandonmentReturn;
import static com.crabit.backend.wish.ImmutableHistoryModels.AccountWishCompletionReturn;
import static com.crabit.backend.wish.ImmutableHistoryModels.AccountWishDeletionReturn;
import static com.crabit.backend.wish.ImmutableHistoryModels.AccountWishDeposit;
import static com.crabit.backend.wish.ImmutableHistoryModels.AccountWishTransfer;
import static com.crabit.backend.wish.ImmutableHistoryModels.AccountWishWithdrawal;
import static com.crabit.backend.wish.ImmutableHistoryModels.BalanceAdjustmentEventReference;
import static com.crabit.backend.wish.ImmutableHistoryModels.CardBalanceChange;
import static com.crabit.backend.wish.ImmutableHistoryModels.CardBalanceChangePage;
import static com.crabit.backend.wish.ImmutableHistoryModels.TransferDirection;
import static com.crabit.backend.wish.ImmutableHistoryModels.WishAbandonmentReturnMovement;
import static com.crabit.backend.wish.ImmutableHistoryModels.WishCompletionReturnMovement;
import static com.crabit.backend.wish.ImmutableHistoryModels.WishDeletionReturnMovement;
import static com.crabit.backend.wish.ImmutableHistoryModels.WishDepositMovement;
import static com.crabit.backend.wish.ImmutableHistoryModels.WishFundMovement;
import static com.crabit.backend.wish.ImmutableHistoryModels.WishFundMovementPage;
import static com.crabit.backend.wish.ImmutableHistoryModels.WishHistoryReference;
import static com.crabit.backend.wish.ImmutableHistoryModels.WishHistorySubject;
import static com.crabit.backend.wish.ImmutableHistoryModels.WishTransferMovement;
import static com.crabit.backend.wish.ImmutableHistoryModels.WishWithdrawalMovement;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.wish.ImmutableHistoryQueryRepository.EventFact;
import com.crabit.backend.wish.ImmutableHistoryQueryRepository.EventKey;
import com.crabit.backend.wish.ImmutableHistoryQueryRepository.WishEffectFact;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImmutableHistoryQueryService {

	private static final String CARD_CHANGES = "listCardBalanceChanges";
	private static final String ACCOUNT_MOVEMENTS = "listAccountFundMovements";
	private static final String WISH_MOVEMENTS = "listWishFundMovements";

	private final CardBalanceAccountRepository accountRepository;
	private final WishRepository wishRepository;
	private final ImmutableHistoryQueryRepository queryRepository;
	private final ImmutableHistoryCursor cursors;

	public ImmutableHistoryQueryService(
			CardBalanceAccountRepository accountRepository,
			WishRepository wishRepository,
			ImmutableHistoryQueryRepository queryRepository,
			ImmutableHistoryCursor cursors) {
		this.accountRepository = accountRepository;
		this.wishRepository = wishRepository;
		this.queryRepository = queryRepository;
		this.cursors = cursors;
	}

	@Transactional(readOnly = true)
	public CardBalanceChangePage cardBalanceChanges(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			String rawCursor,
			int limit) {
		requireOwnedAccount(studentId, academyId, accountId);
		PageData page = loadPage(
				CARD_CHANGES, accountId, null, LedgerEventType.CARD_BALANCE_CHANGE,
				rawCursor, limit);
		List<CardBalanceChange> items = page.keys().stream()
				.map(key -> cardChange(requireEvent(page.events(), key.eventId())))
				.toList();
		return new CardBalanceChangePage(items, page.nextCursor());
	}

	@Transactional(readOnly = true)
	public AccountFundMovementPage accountFundMovements(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			String rawCursor,
			int limit) {
		requireOwnedAccount(studentId, academyId, accountId);
		PageData page = loadPage(ACCOUNT_MOVEMENTS, accountId, null, null, rawCursor, limit);
		List<AccountFundMovement> items = page.keys().stream()
				.map(key -> accountMovement(
						requireEvent(page.events(), key.eventId()),
						page.effects().getOrDefault(key.eventId(), List.of())))
				.toList();
		return new AccountFundMovementPage(items, page.nextCursor());
	}

	@Transactional(readOnly = true)
	public WishFundMovementPage wishFundMovements(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			UUID wishId,
			String rawCursor,
			int limit) {
		requireOwnedAccount(studentId, academyId, accountId);
		Wish wish = wishRepository.findByAccountIdAndId(accountId, wishId)
				.orElseThrow(ImmutableHistoryQueryService::wishNotFound);
		PageData page = loadPage(WISH_MOVEMENTS, accountId, wishId, null, rawCursor, limit);
		List<WishFundMovement> items = page.keys().stream()
				.map(key -> wishMovement(
						wishId,
						requireEvent(page.events(), key.eventId()),
						page.effects().getOrDefault(key.eventId(), List.of())))
				.toList();
		String displayPurpose = wish.isDeleted() ? wish.purposeSnapshot() : wish.purpose();
		WishHistorySubject subject = new WishHistorySubject(
				wish.id(), displayPurpose, wish.isDeleted(), !wish.isDeleted());
		return new WishFundMovementPage(subject, items, page.nextCursor());
	}

	private PageData loadPage(
			String operation,
			UUID accountId,
			UUID wishId,
			LedgerEventType eventType,
			String rawCursor,
			int limit) {
		int pageSize = requireLimit(limit);
		ImmutableHistoryCursor.Boundary boundary = cursors.decode(
				rawCursor, operation, accountId, wishId);
		List<EventKey> fetched = queryRepository.findPageKeys(
				accountId, wishId, eventType, boundary, pageSize + 1);
		boolean hasNext = fetched.size() > pageSize;
		List<EventKey> keys = List.copyOf(
				fetched.subList(0, Math.min(pageSize, fetched.size())));
		List<UUID> eventIds = keys.stream().map(EventKey::eventId).toList();
		Map<UUID, EventFact> events = queryRepository.findEventFacts(accountId, eventIds);
		Map<UUID, List<WishEffectFact>> effects =
				queryRepository.findEffectFacts(accountId, eventIds);
		String nextCursor = hasNext && !keys.isEmpty()
				? cursors.encode(operation, accountId, wishId,
						new ImmutableHistoryCursor.Boundary(
								keys.getLast().occurredAt(), keys.getLast().eventId()))
				: null;
		return new PageData(keys, events, effects, nextCursor);
	}

	private void requireOwnedAccount(UUID studentId, UUID academyId, UUID accountId) {
		CardBalanceAccount account = accountRepository.findByIdAndStudentId(
				Objects.requireNonNull(accountId, "accountId"),
				Objects.requireNonNull(studentId, "studentId"))
				.orElseThrow(ImmutableHistoryQueryService::accountNotFound);
		if (!account.isActive()
				|| !account.academyId().equals(Objects.requireNonNull(academyId, "academyId"))) {
			throw accountNotFound();
		}
	}

	private static CardBalanceChange cardChange(EventFact event) {
		requireCardObservation(event);
		return new CardBalanceChange(
				event.eventId(), event.eventType().name(), event.observationId(),
				event.lookupMethod(), event.occurredAt(), event.availableDelta(),
				event.actualCardBalanceAfter(), event.correctionOfEventId(),
				adjustment(event));
	}

	private static AccountFundMovement accountMovement(
			EventFact event, List<WishEffectFact> effects) {
		BalanceAdjustmentEventReference adjustment = adjustment(event);
		return switch (event.eventType()) {
			case CARD_BALANCE_CHANGE -> {
				requireCardObservation(event);
				yield new AccountCardBalanceChange(
						event.eventId(), event.eventType().name(), event.observationId(),
						event.lookupMethod(), event.occurredAt(), event.availableDelta(),
						event.actualCardBalanceAfter(), event.availableDelta(),
						event.availableAfter(), event.correctionOfEventId(), adjustment);
			}
			case WISH_TRANSFER -> {
				if (effects.size() != 2) {
					throw new IllegalStateException("A Wish transfer must have exactly two effects");
				}
				WishEffectFact source = effects.stream().filter(effect -> effect.delta() < 0)
						.findFirst().orElseThrow(() -> new IllegalStateException(
								"A Wish transfer must have one source effect"));
				WishEffectFact destination = effects.stream().filter(effect -> effect.delta() > 0)
						.findFirst().orElseThrow(() -> new IllegalStateException(
								"A Wish transfer must have one destination effect"));
				yield new AccountWishTransfer(
						event.eventId(), event.eventType().name(), reference(source),
						reference(destination), destination.delta(), event.occurredAt(),
						event.availableDelta(), event.availableAfter(),
						event.correctionOfEventId(), adjustment);
			}
			case WISH_DEPOSIT -> {
				WishEffectFact effect = singleEffect(event, effects);
				yield new AccountWishDeposit(
						event.eventId(), event.eventType().name(), reference(effect),
						event.occurredAt(), event.availableDelta(), event.availableAfter(),
						event.correctionOfEventId(), adjustment);
			}
			case WISH_WITHDRAWAL -> {
				WishEffectFact effect = singleEffect(event, effects);
				yield new AccountWishWithdrawal(
						event.eventId(), event.eventType().name(), reference(effect),
						event.occurredAt(), event.availableDelta(), event.availableAfter(),
						event.correctionOfEventId(), adjustment);
			}
			case WISH_COMPLETION_RETURN -> {
				WishEffectFact effect = singleEffect(event, effects);
				yield new AccountWishCompletionReturn(
						event.eventId(), event.eventType().name(), reference(effect),
						event.occurredAt(), event.availableDelta(), event.availableAfter(),
						event.correctionOfEventId(), adjustment);
			}
			case WISH_ABANDONMENT_RETURN -> {
				WishEffectFact effect = singleEffect(event, effects);
				yield new AccountWishAbandonmentReturn(
						event.eventId(), event.eventType().name(), reference(effect),
						event.occurredAt(), event.availableDelta(), event.availableAfter(),
						event.correctionOfEventId(), adjustment);
			}
			case WISH_DELETION_RETURN -> {
				WishEffectFact effect = singleEffect(event, effects);
				yield new AccountWishDeletionReturn(
						event.eventId(), event.eventType().name(), reference(effect),
						event.occurredAt(), event.availableDelta(), event.availableAfter(),
						event.correctionOfEventId(), adjustment);
			}
		};
	}

	private static WishFundMovement wishMovement(
			UUID wishId, EventFact event, List<WishEffectFact> effects) {
		WishEffectFact target = effects.stream()
				.filter(effect -> effect.wishId().equals(wishId))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"The Wish history event has no effect for its subject"));
		BalanceAdjustmentEventReference adjustment = adjustment(event);
		return switch (event.eventType()) {
			case CARD_BALANCE_CHANGE -> throw new IllegalStateException(
					"Card balance changes cannot appear in Wish history");
			case WISH_DEPOSIT -> new WishDepositMovement(
					event.eventId(), event.eventType().name(), event.occurredAt(),
					target.purposeSnapshot(), target.delta(), target.amountAfter(),
					event.correctionOfEventId(), adjustment);
			case WISH_WITHDRAWAL -> new WishWithdrawalMovement(
					event.eventId(), event.eventType().name(), event.occurredAt(),
					target.purposeSnapshot(), target.delta(), target.amountAfter(),
					event.correctionOfEventId(), adjustment);
			case WISH_TRANSFER -> {
				WishEffectFact counterparty = effects.stream()
						.filter(effect -> !effect.wishId().equals(wishId))
						.findFirst()
						.orElseThrow(() -> new IllegalStateException(
								"The Wish transfer has no counterparty effect"));
				yield new WishTransferMovement(
						event.eventId(), event.eventType().name(), event.occurredAt(),
						target.purposeSnapshot(),
						target.delta() < 0 ? TransferDirection.SOURCE : TransferDirection.DESTINATION,
						reference(counterparty), target.delta(), target.amountAfter(),
						event.correctionOfEventId(), adjustment);
			}
			case WISH_COMPLETION_RETURN -> new WishCompletionReturnMovement(
					event.eventId(), event.eventType().name(), event.occurredAt(),
					target.purposeSnapshot(), target.delta(), target.amountAfter(),
					event.correctionOfEventId(), adjustment);
			case WISH_ABANDONMENT_RETURN -> new WishAbandonmentReturnMovement(
					event.eventId(), event.eventType().name(), event.occurredAt(),
					target.purposeSnapshot(), target.delta(), target.amountAfter(),
					event.correctionOfEventId(), adjustment);
			case WISH_DELETION_RETURN -> new WishDeletionReturnMovement(
					event.eventId(), event.eventType().name(), event.occurredAt(),
					target.purposeSnapshot(), target.delta(), target.amountAfter(),
					event.correctionOfEventId(), adjustment);
		};
	}

	private static WishEffectFact singleEffect(
			EventFact event, List<WishEffectFact> effects) {
		if (effects.size() != 1) {
			throw new IllegalStateException(
					"%s must have exactly one Wish effect".formatted(event.eventType()));
		}
		return effects.getFirst();
	}

	private static WishHistoryReference reference(WishEffectFact effect) {
		return new WishHistoryReference(
				effect.wishId(), effect.purposeSnapshot(), effect.deleted(), !effect.deleted());
	}

	private static BalanceAdjustmentEventReference adjustment(EventFact event) {
		if (event.adjustmentCaseId() == null) return null;
		return new BalanceAdjustmentEventReference(
				event.adjustmentCaseId(), event.adjustmentRole(), event.adjustmentSequence());
	}

	private static void requireCardObservation(EventFact event) {
		if (event.eventType() != LedgerEventType.CARD_BALANCE_CHANGE
				|| event.observationId() == null
				|| event.lookupMethod() == null
				|| event.actualCardBalanceAfter() == null
				|| event.availableDelta() == 0) {
			throw new IllegalStateException(
					"Card balance history requires one nonzero successful observation event");
		}
	}

	private static EventFact requireEvent(Map<UUID, EventFact> events, UUID eventId) {
		EventFact event = events.get(eventId);
		if (event == null) {
			throw new IllegalStateException("History event disappeared during a read transaction");
		}
		return event;
	}

	private static int requireLimit(int limit) {
		if (limit < 1 || limit > 100) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.MALFORMED_REQUEST,
					"limit must be between 1 and 100.", "limit");
		}
		return limit;
	}

	private static WishLifecycleException accountNotFound() {
		return new WishLifecycleException(
				WishLifecycleException.Code.CARD_BALANCE_ACCOUNT_NOT_FOUND,
				"Card Balance Account not found.");
	}

	private static WishLifecycleException wishNotFound() {
		return new WishLifecycleException(
				WishLifecycleException.Code.WISH_NOT_FOUND, "Wish not found.");
	}

	private record PageData(
			List<EventKey> keys,
			Map<UUID, EventFact> events,
			Map<UUID, List<WishEffectFact>> effects,
			String nextCursor) {
	}
}
