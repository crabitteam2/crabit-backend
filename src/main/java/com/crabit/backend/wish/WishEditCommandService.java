package com.crabit.backend.wish;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WishEditCommandService {

	private final CardBalanceAccountRepository accountRepository;
	private final WishRepository wishRepository;
	private final BalanceAdjustmentCaseRepository adjustmentRepository;
	private final SharedCardRepository sharedCardRepository;

	public WishEditCommandService(
			CardBalanceAccountRepository accountRepository,
			WishRepository wishRepository,
			BalanceAdjustmentCaseRepository adjustmentRepository,
			SharedCardRepository sharedCardRepository) {
		this.accountRepository = accountRepository;
		this.wishRepository = wishRepository;
		this.adjustmentRepository = adjustmentRepository;
		this.sharedCardRepository = sharedCardRepository;
	}

	@Transactional
	public void changePurpose(
			UUID accountId, UUID wishId, String purpose, Instant changedAt) {
		edit(accountId, wishId, changedAt, wish -> wish.changePurpose(purpose));
	}

	@Transactional
	public Wish patch(UUID accountId, UUID wishId, WishPatch patch, Instant changedAt) {
		Objects.requireNonNull(patch, "patch");
		return edit(accountId, wishId, changedAt, (wish, adjustmentOpen) -> {
			if (adjustmentOpen && (!patch.onlyVisibility()
					|| !isVisibilityNarrowing(wish.visibility(), patch.visibility()))) {
				throw new IllegalStateException(
						"Wish edits are blocked while balance adjustment is open");
			}
			patch.purpose().ifPresent(wish::changePurpose);
			patch.targetAmount().ifPresent(wish::changeTarget);
			if (patch.targetDatePresent()) {
				wish.changeTargetDate(patch.targetDate());
			}
			patch.visibilityValue().ifPresent(wish::changeVisibility);
		});
	}

	@Transactional
	public void changeTarget(
			UUID accountId, UUID wishId, KrwAmount targetAmount, Instant changedAt) {
		edit(accountId, wishId, changedAt, wish -> wish.changeTarget(targetAmount));
	}

	@Transactional
	public void changeTargetDate(
			UUID accountId, UUID wishId, LocalDate targetDate, Instant changedAt) {
		edit(accountId, wishId, changedAt, wish -> wish.changeTargetDate(targetDate));
	}

	@Transactional
	public void changeVisibility(
			UUID accountId, UUID wishId, WishVisibility visibility, Instant changedAt) {
		WishVisibility requested = Objects.requireNonNull(visibility, "visibility");
		edit(accountId, wishId, changedAt, (wish, adjustmentOpen) -> {
			if (adjustmentOpen && !isVisibilityNarrowing(wish.visibility(), requested)) {
				throw new IllegalStateException(
						"Wish visibility can only be narrowed while balance adjustment is open");
			}
			wish.changeVisibility(requested);
		});
	}

	private Wish edit(
			UUID accountId, UUID wishId, Instant changedAt, Consumer<Wish> mutation) {
		return edit(accountId, wishId, changedAt, (wish, adjustmentOpen) -> {
			if (adjustmentOpen) {
				throw new IllegalStateException(
						"Wish edits are blocked while balance adjustment is open");
			}
			mutation.accept(wish);
		});
	}

	private Wish edit(
			UUID accountId,
			UUID wishId,
			Instant changedAt,
			BiConsumer<Wish, Boolean> mutation) {
		Instant when = Objects.requireNonNull(changedAt, "changedAt");
		CardBalanceAccount account = accountRepository.lockById(
				Objects.requireNonNull(accountId, "accountId"))
				.orElseThrow(() -> new IllegalArgumentException("Card Balance Account not found"));
		if (!account.isActive()) {
			throw new IllegalStateException("Card Balance Account is closed");
		}
		boolean adjustmentOpen = adjustmentRepository.lockSingleOpenByAccountId(account.id()).isPresent();
		Wish wish = wishRepository.lockByAccountIdAndIds(
				account.id(), List.of(Objects.requireNonNull(wishId, "wishId")))
				.stream()
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Wish must belong to the locked account"));
		mutation.accept(wish, adjustmentOpen);
		wish.touch(when);
		synchronizeSharedCard(wish, when);
		return wish;
	}

	private static boolean isVisibilityNarrowing(
			WishVisibility current, WishVisibility requested) {
		return visibilityScope(requested) < visibilityScope(current);
	}

	private static int visibilityScope(WishVisibility visibility) {
		return switch (visibility) {
			case PRIVATE -> 0;
			case FRIENDS -> 1;
			case ACADEMY -> 2;
		};
	}

	private void synchronizeSharedCard(Wish wish, Instant changedAt) {
		if (wish.isDeleted() || wish.state() == WishState.ABANDONED
				|| wish.visibility() == WishVisibility.PRIVATE) {
			sharedCardRepository.findByWishId(wish.id()).ifPresent(sharedCardRepository::delete);
			return;
		}
		SharedCardKind kind = wish.state() == WishState.COMPLETED
				? SharedCardKind.COMPLETION : SharedCardKind.PROGRESS;
		SharedCard card = sharedCardRepository.findByWishId(wish.id())
				.orElseGet(() -> new SharedCard(wish.id(), kind, wish.visibility(), changedAt));
		card.refresh(kind, wish.visibility(), changedAt);
		sharedCardRepository.save(card);
	}
}
