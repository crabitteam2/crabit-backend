package com.crabit.backend.wish;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WishEditCommandService {

	private final CardBalanceAccountRepository accountRepository;
	private final WishRepository wishRepository;
	private final BalanceAdjustmentPolicy adjustmentPolicy;
	private final SharedCardSynchronizationService sharedCardSynchronization;

	public WishEditCommandService(
			CardBalanceAccountRepository accountRepository,
			WishRepository wishRepository,
			BalanceAdjustmentPolicy adjustmentPolicy,
			SharedCardRepository sharedCardRepository) {
		this.accountRepository = accountRepository;
		this.wishRepository = wishRepository;
		this.adjustmentPolicy = adjustmentPolicy;
		this.sharedCardSynchronization =
				new SharedCardSynchronizationService(sharedCardRepository);
	}

	@Transactional
	public void changePurpose(
			UUID accountId, UUID wishId, String purpose, Instant changedAt) {
		edit(accountId, wishId, changedAt, wish -> wish.changePurpose(purpose));
	}

	@Transactional
	public Wish patch(UUID accountId, UUID wishId, WishPatch patch, Instant changedAt) {
		Objects.requireNonNull(patch, "patch");
		return edit(accountId, wishId, changedAt, wish -> {
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
		edit(accountId, wishId, changedAt, wish -> wish.changeVisibility(requested));
	}

	private Wish edit(
			UUID accountId, UUID wishId, Instant changedAt, Consumer<Wish> mutation) {
		Instant when = Objects.requireNonNull(changedAt, "changedAt");
		CardBalanceAccount account = accountRepository.lockById(
				Objects.requireNonNull(accountId, "accountId"))
				.orElseThrow(() -> new IllegalArgumentException("Card Balance Account not found"));
		if (!account.isActive()) {
			throw new IllegalStateException("Card Balance Account is closed");
		}
		Optional<BalanceAdjustmentCase> openCase = adjustmentPolicy.lockOpenCase(account.id());
		adjustmentPolicy.requireAllowed(openCase, BalanceAdjustmentPolicy.Operation.PATCH_WISH);
		Wish wish = wishRepository.lockByAccountIdAndIds(
				account.id(), List.of(Objects.requireNonNull(wishId, "wishId")))
				.stream()
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Wish must belong to the locked account"));
		mutation.accept(wish);
		wish.touch(when);
		sharedCardSynchronization.synchronize(wish, when);
		return wish;
	}
}
