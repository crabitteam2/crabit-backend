package com.crabit.backend.wish;

import java.time.Instant;
import java.util.Objects;

public class SharedCardSynchronizationService {

	private final SharedCardRepository sharedCards;

	public SharedCardSynchronizationService(SharedCardRepository sharedCards) {
		this.sharedCards = sharedCards;
	}

	public void synchronize(Wish wish, Instant updatedAt) {
		Wish currentWish = Objects.requireNonNull(wish, "wish");
		Instant when = Objects.requireNonNull(updatedAt, "updatedAt");
		if (currentWish.isDeleted()
				|| currentWish.visibility() == WishVisibility.PRIVATE) {
			sharedCards.findByWishId(currentWish.id()).ifPresent(sharedCards::delete);
			return;
		}

		SharedCardKind kind = switch (currentWish.state()) {
			case COMPLETED -> SharedCardKind.COMPLETION;
			case ABANDONED -> SharedCardKind.ABANDONMENT;
			case IN_PROGRESS, AMOUNT_REACHED -> SharedCardKind.PROGRESS;
		};
		SharedCard card = sharedCards.findByWishId(currentWish.id())
				.orElseGet(() -> new SharedCard(
						currentWish.id(), kind, currentWish.visibility(), when));
		card.refresh(kind, currentWish.visibility(), when);
		sharedCards.save(card);
	}
}
