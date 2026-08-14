package com.crabit.backend.wish;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "balance_adjustment_case_event", uniqueConstraints = {
		@UniqueConstraint(
				name = "uk_adjustment_case_event",
				columnNames = {"adjustment_case_id", "event_id"})
})
public class BalanceAdjustmentCaseEvent {

	@Id
	private UUID id;

	@Column(name = "adjustment_case_id", nullable = false, updatable = false)
	private UUID adjustmentCaseId;

	@Column(name = "event_id", nullable = false, updatable = false)
	private UUID eventId;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(value = {
			@JoinColumn(name = "adjustment_case_id", referencedColumnName = "id",
					insertable = false, updatable = false, nullable = false),
			@JoinColumn(name = "account_id", referencedColumnName = "account_id",
					insertable = false, updatable = false, nullable = false)
	}, foreignKey = @ForeignKey(name = "fk_adjustment_case_event_case_account"))
	private BalanceAdjustmentCase adjustmentCase;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(value = {
			@JoinColumn(name = "event_id", referencedColumnName = "id",
					insertable = false, updatable = false, nullable = false),
			@JoinColumn(name = "account_id", referencedColumnName = "account_id",
					insertable = false, updatable = false, nullable = false)
	}, foreignKey = @ForeignKey(name = "fk_adjustment_case_event_ledger_account"))
	private LedgerEvent ledgerEvent;

	protected BalanceAdjustmentCaseEvent() {
	}

	BalanceAdjustmentCaseEvent(
			UUID id, BalanceAdjustmentCase adjustmentCase, LedgerEvent ledgerEvent) {
		this.id = Objects.requireNonNull(id, "id");
		this.adjustmentCase = Objects.requireNonNull(adjustmentCase, "adjustmentCase");
		this.adjustmentCaseId = adjustmentCase.id();
		this.ledgerEvent = Objects.requireNonNull(ledgerEvent, "ledgerEvent");
		this.eventId = ledgerEvent.id();
		this.accountId = adjustmentCase.accountId();
		if (!accountId.equals(ledgerEvent.accountId())) {
			throw new IllegalArgumentException("Ledger event must belong to the adjustment account");
		}
	}

	public UUID eventId() { return eventId; }
	public UUID accountId() { return accountId; }
	public LedgerEvent ledgerEvent() { return ledgerEvent; }
}
