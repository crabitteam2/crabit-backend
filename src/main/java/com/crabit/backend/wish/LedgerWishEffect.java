package com.crabit.backend.wish;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "ledger_wish_effect", uniqueConstraints = {
		@UniqueConstraint(name = "uk_ledger_effect_event_wish", columnNames = {"event_id", "wish_id"})
})
public class LedgerWishEffect {

	@Id
	private UUID id;

	@Column(name = "event_id", nullable = false, updatable = false)
	private UUID eventId;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(value = {
		@JoinColumn(name = "event_id", referencedColumnName = "id",
				insertable = false, updatable = false, nullable = false),
		@JoinColumn(name = "account_id", referencedColumnName = "account_id",
				insertable = false, updatable = false, nullable = false)
	}, foreignKey = @ForeignKey(name = "fk_ledger_effect_event_account"))
	private LedgerEvent event;

	@Column(name = "wish_id", nullable = false, updatable = false)
	private UUID wishId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(value = {
		@JoinColumn(name = "wish_id", referencedColumnName = "id",
				insertable = false, updatable = false, nullable = false),
		@JoinColumn(name = "account_id", referencedColumnName = "account_id",
				insertable = false, updatable = false, nullable = false)
	}, foreignKey = @ForeignKey(name = "fk_ledger_effect_wish_account"))
	private Wish wish;

	@Column(name = "wish_purpose_snapshot", nullable = false, updatable = false, length = 200)
	private String wishPurposeSnapshot;

	@Column(name = "wish_delta", nullable = false, updatable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount delta;

	protected LedgerWishEffect() {
	}

	LedgerWishEffect(
			UUID id, LedgerEvent event, UUID wishId, String wishPurposeSnapshot, KrwAmount delta) {
		this.id = Objects.requireNonNull(id, "id");
		this.event = Objects.requireNonNull(event, "event");
		this.eventId = event.id();
		this.accountId = event.accountId();
		this.wishId = Objects.requireNonNull(wishId, "wishId");
		if (wishPurposeSnapshot == null || wishPurposeSnapshot.isBlank()) {
			throw new IllegalArgumentException("Wish purpose snapshot must not be blank");
		}
		this.wishPurposeSnapshot = wishPurposeSnapshot;
		this.delta = Objects.requireNonNull(delta, "delta");
	}

	@PreUpdate
	@PreRemove
	private void rejectMutation() {
		throw new UnsupportedOperationException("Ledger Wish Effects are append-only");
	}

	public UUID id() { return id; }
	public UUID eventId() { return eventId; }
	public UUID accountId() { return accountId; }
	public UUID wishId() { return wishId; }
	public String wishPurposeSnapshot() { return wishPurposeSnapshot; }
	public KrwAmount delta() { return delta; }
}
