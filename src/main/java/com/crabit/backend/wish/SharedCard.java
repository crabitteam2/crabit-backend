package com.crabit.backend.wish;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "shared_card", uniqueConstraints = {
		@UniqueConstraint(name = "uk_shared_card_current_wish", columnNames = "wish_id")
})
public class SharedCard {

	@Id
	private UUID id;

	@Column(name = "wish_id", nullable = false, updatable = false)
	private UUID wishId;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, length = 16)
	private SharedCardKind kind;

	@Enumerated(EnumType.STRING)
	@Column(name = "visibility", nullable = false, length = 32)
	private WishVisibility visibility;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected SharedCard() {
	}

	public SharedCard(UUID wishId, SharedCardKind kind, WishVisibility visibility, Instant updatedAt) {
		this.id = UUID.randomUUID();
		this.wishId = Objects.requireNonNull(wishId, "wishId");
		this.kind = Objects.requireNonNull(kind, "kind");
		if (visibility == WishVisibility.PRIVATE) {
			throw new IllegalArgumentException("A private Wish has no Shared Card");
		}
		this.visibility = Objects.requireNonNull(visibility, "visibility");
		this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
	}

	public UUID wishId() { return wishId; }
}
