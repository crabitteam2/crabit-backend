package com.crabit.backend.wish;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_event", indexes = {
		@Index(name = "idx_ledger_event_account_occurred", columnList = "account_id,occurred_at")
})
public class LedgerEvent {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, updatable = false, length = 48)
	private LedgerEventType type;

	@Column(name = "account_delta", nullable = false, updatable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount accountDelta;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Column(name = "correction_of_event_id", updatable = false)
	private UUID correctionOfEventId;

	@OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = false)
	private final List<LedgerWishEffect> wishEffects = new ArrayList<>();

	protected LedgerEvent() {
	}

	private LedgerEvent(
			UUID id,
			UUID accountId,
			LedgerEventType type,
			KrwAmount accountDelta,
			Instant occurredAt,
			UUID correctionOfEventId) {
		this.id = Objects.requireNonNull(id, "id");
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.type = Objects.requireNonNull(type, "type");
		this.accountDelta = Objects.requireNonNull(accountDelta, "accountDelta");
		this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
		this.correctionOfEventId = correctionOfEventId;
	}

	public static LedgerEvent transfer(
			UUID accountId,
			UUID sourceWishId,
			String sourcePurposeSnapshot,
			UUID destinationWishId,
			String destinationPurposeSnapshot,
			KrwAmount amount,
			Instant occurredAt) {
		if (!Objects.requireNonNull(amount, "amount").isPositive()) {
			throw new IllegalArgumentException("Transfer amount must be positive");
		}
		if (Objects.requireNonNull(sourceWishId, "sourceWishId").equals(destinationWishId)) {
			throw new IllegalArgumentException("Transfer Wishes must be different");
		}
		LedgerEvent event = new LedgerEvent(UUID.randomUUID(), accountId,
				LedgerEventType.WISH_TRANSFER, KrwAmount.zero(), occurredAt, null);
		event.addWishEffect(sourceWishId, sourcePurposeSnapshot, amount.negate());
		event.addWishEffect(destinationWishId, destinationPurposeSnapshot, amount);
		return event;
	}

	private void addWishEffect(UUID wishId, String purposeSnapshot, KrwAmount delta) {
		wishEffects.add(new LedgerWishEffect(UUID.randomUUID(), this, wishId, purposeSnapshot, delta));
	}

	public UUID id() { return id; }
	public UUID accountId() { return accountId; }
	public LedgerEventType type() { return type; }
	public KrwAmount accountDelta() { return accountDelta; }
	public Instant occurredAt() { return occurredAt; }
	public UUID correctionOfEventId() { return correctionOfEventId; }
	public List<LedgerWishEffect> wishEffects() { return Collections.unmodifiableList(wishEffects); }
}
