package com.crabit.backend.wish;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "representative_wish_selection")
public class RepresentativeWishSelection {

	@Id
	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@Column(name = "wish_id", nullable = false)
	private UUID wishId;

	protected RepresentativeWishSelection() {
	}

	private RepresentativeWishSelection(UUID accountId, UUID wishId) {
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.wishId = Objects.requireNonNull(wishId, "wishId");
	}

	static RepresentativeWishSelection select(UUID accountId, UUID wishId) {
		return new RepresentativeWishSelection(accountId, wishId);
	}

	void replaceWith(UUID selectedWishId) {
		wishId = Objects.requireNonNull(selectedWishId, "selectedWishId");
	}

	public UUID accountId() {
		return accountId;
	}

	public UUID wishId() {
		return wishId;
	}
}
