package com.crabit.backend.wish;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "balance_adjustment_case", indexes = {
		@Index(name = "idx_adjustment_account_status", columnList = "account_id,status")
})
public class BalanceAdjustmentCase {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@Column(name = "opening_event_id", nullable = false, updatable = false)
	private UUID openingEventId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private BalanceAdjustmentStatus status;

	@Column(name = "opened_shortage", nullable = false, updatable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount openedShortage;

	@Column(name = "opened_at", nullable = false, updatable = false)
	private Instant openedAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	@Column(name = "resolution_event_id")
	private UUID resolutionEventId;

	protected BalanceAdjustmentCase() {
	}

	public static BalanceAdjustmentCase open(
			UUID accountId, UUID openingEventId, KrwAmount shortage, Instant openedAt) {
		if (!Objects.requireNonNull(shortage, "shortage").isPositive()) {
			throw new IllegalArgumentException("Opening shortage must be positive");
		}
		BalanceAdjustmentCase adjustmentCase = new BalanceAdjustmentCase();
		adjustmentCase.id = UUID.randomUUID();
		adjustmentCase.accountId = Objects.requireNonNull(accountId, "accountId");
		adjustmentCase.openingEventId = Objects.requireNonNull(openingEventId, "openingEventId");
		adjustmentCase.status = BalanceAdjustmentStatus.OPEN;
		adjustmentCase.openedShortage = shortage;
		adjustmentCase.openedAt = Objects.requireNonNull(openedAt, "openedAt");
		return adjustmentCase;
	}

	public void resolve(UUID resolutionEventId, Instant resolvedAt) {
		if (status != BalanceAdjustmentStatus.OPEN) {
			throw new IllegalStateException("Balance Adjustment Case is already resolved");
		}
		this.resolutionEventId = Objects.requireNonNull(resolutionEventId, "resolutionEventId");
		this.resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
		this.status = BalanceAdjustmentStatus.RESOLVED;
	}

	public UUID id() { return id; }
	public UUID accountId() { return accountId; }
	public boolean isOpen() { return status == BalanceAdjustmentStatus.OPEN; }
}
