package com.crabit.backend.wish;

import com.crabit.backend.account.CardBalanceAccountRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crabit.backend.wishphoto.WishPhotoService;

@Service
public class RepresentativeWishService {
	private static final List<WishState> ACTIVE_STATES =
			List.of(WishState.IN_PROGRESS, WishState.AMOUNT_REACHED);

	private final CardBalanceAccountRepository accountRepository;
	private final WishRepository wishRepository;
	private final RepresentativeWishSelectionRepository selectionRepository;
	private final BalanceAdjustmentPolicy adjustmentPolicy;
	private final WishPhotoService photos;

	public RepresentativeWishService(
			CardBalanceAccountRepository accountRepository,
			WishRepository wishRepository,
			RepresentativeWishSelectionRepository selectionRepository,
			BalanceAdjustmentPolicy adjustmentPolicy,
			Optional<WishPhotoService> photos) {
		this.accountRepository = accountRepository;
		this.wishRepository = wishRepository;
		this.selectionRepository = selectionRepository;
		this.adjustmentPolicy = adjustmentPolicy;
		this.photos = photos.orElse(null);
	}

	@Transactional
	public Optional<WishSnapshot> get(
			UUID studentId, UUID academyId, UUID accountId) {
		accountRepository.lockOwnedActiveForProjection(accountId, studentId, academyId)
				.orElseThrow(RepresentativeWishService::accountNotFound);
		return selectionRepository.findById(accountId)
				.flatMap(selection -> wishRepository.findByAccountIdAndIdAndDeletedAtIsNull(
						accountId, selection.wishId()))
				.filter(Wish::isActive)
				.map(wish -> snapshot(wish, accountId));
	}

	@Transactional
	public WishSnapshot select(
			UUID studentId, UUID academyId, UUID accountId, UUID wishId) {
		accountRepository.lockOwnedActive(accountId, studentId, academyId)
				.orElseThrow(RepresentativeWishService::accountNotFound);
		Wish wish = wishRepository.findByAccountIdAndIdAndDeletedAtIsNull(accountId, wishId)
				.orElseThrow(RepresentativeWishService::wishNotFound);
		if (!wish.isActive()) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_STATE_TRANSITION,
					"A completed or abandoned Wish cannot be selected as representative.");
		}
		RepresentativeWishSelection selection = selectionRepository.findById(accountId)
				.orElseGet(() -> RepresentativeWishSelection.select(accountId, wish.id()));
		if (!selection.wishId().equals(wish.id())) {
			selection.replaceWith(wish.id());
		}
		selectionRepository.save(selection);
		return snapshot(wish, accountId);
	}

	private WishSnapshot snapshot(Wish wish, UUID accountId) {
		return WishSnapshot.from(wish, adjustmentPolicy.isOpen(accountId),
				photos == null ? null : photos.attachedView(wish.id()));
	}

	@Transactional
	public void reconcile(UUID accountId) {
		List<Wish> activeWishes = wishRepository.findByAccountIdAndDeletedAtIsNullAndStateIn(
				accountId, ACTIVE_STATES);
		Optional<RepresentativeWishSelection> current = selectionRepository.findById(accountId);
		if (current.isPresent() && activeWishes.stream()
				.anyMatch(wish -> wish.id().equals(current.orElseThrow().wishId()))) {
			return;
		}
		if (activeWishes.size() == 1) {
			UUID wishId = activeWishes.getFirst().id();
			RepresentativeWishSelection selection = current
					.orElseGet(() -> RepresentativeWishSelection.select(accountId, wishId));
			selection.replaceWith(wishId);
			selectionRepository.save(selection);
		} else {
			current.ifPresent(selectionRepository::delete);
		}
	}

	private static WishLifecycleException accountNotFound() {
		return new WishLifecycleException(
				WishLifecycleException.Code.CARD_BALANCE_ACCOUNT_NOT_FOUND,
				"Card Balance Account not found.");
	}

	private static WishLifecycleException wishNotFound() {
		return new WishLifecycleException(
				WishLifecycleException.Code.WISH_NOT_FOUND,
				"Wish not found.");
	}
}
