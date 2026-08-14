package com.crabit.backend.wish;

import jakarta.persistence.Column;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "shared_card",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_shared_card_current_wish", columnNames = "wish_id"),
		check = @CheckConstraint(
				name = "ck_shared_card_not_private", constraint = "CAST(visibility AS VARCHAR) <> 'PRIVATE'"))
public class SharedCard {

	@Id
	private UUID id;

	@Column(name = "wish_id", nullable = false, updatable = false)
	private UUID wishId;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "wish_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_shared_card_wish"))
	private Wish wish;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, length = 16, columnDefinition = "varchar(16)")
	private SharedCardKind kind;

	@Enumerated(EnumType.STRING)
	@Column(name = "visibility", nullable = false, length = 32, columnDefinition = "varchar(32)")
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
