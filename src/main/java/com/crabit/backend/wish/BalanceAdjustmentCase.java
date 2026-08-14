package com.crabit.backend.wish;

import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "balance_adjustment_case",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_adjustment_case_id_account", columnNames = {"id", "account_id"}),
		indexes = @Index(name = "idx_adjustment_account_status", columnList = "account_id,status"),
		check = {
				@CheckConstraint(name = "ck_adjustment_shortage_positive",
						constraint = "opened_shortage > 0"),
				@CheckConstraint(name = "ck_adjustment_resolution",
						constraint = "(CAST(status AS VARCHAR) = 'OPEN' AND resolved_at IS NULL AND resolution_event_id IS NULL) OR (CAST(status AS VARCHAR) = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution_event_id IS NOT NULL)")
		})
public class BalanceAdjustmentCase {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_adjustment_account"))
	private CardBalanceAccount account;

	@Column(name = "opening_event_id", nullable = false, updatable = false)
	private UUID openingEventId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(value = {
		@JoinColumn(name = "opening_event_id", referencedColumnName = "id",
				insertable = false, updatable = false, nullable = false),
		@JoinColumn(name = "account_id", referencedColumnName = "account_id",
				insertable = false, updatable = false, nullable = false)
	}, foreignKey = @ForeignKey(name = "fk_adjustment_opening_event_account"))
	private LedgerEvent openingEvent;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16, columnDefinition = "varchar(16)")
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

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumns(value = {
		@JoinColumn(name = "resolution_event_id", referencedColumnName = "id",
				insertable = false, updatable = false),
		@JoinColumn(name = "account_id", referencedColumnName = "account_id",
				insertable = false, updatable = false)
	}, foreignKey = @ForeignKey(name = "fk_adjustment_resolution_event_account"))
	private LedgerEvent resolutionEvent;

	@OneToMany(mappedBy = "adjustmentCase", cascade = CascadeType.ALL, orphanRemoval = false)
	private final List<BalanceAdjustmentCaseEvent> eventLinks = new ArrayList<>();

	protected BalanceAdjustmentCase() {
	}

	public static BalanceAdjustmentCase open(
			LedgerEvent openingEvent, KrwAmount shortage, Instant openedAt) {
		Objects.requireNonNull(openingEvent, "openingEvent");
		if (!Objects.requireNonNull(shortage, "shortage").isPositive()) {
			throw new IllegalArgumentException("Opening shortage must be positive");
		}
		BalanceAdjustmentCase adjustmentCase = new BalanceAdjustmentCase();
		adjustmentCase.id = UUID.randomUUID();
		adjustmentCase.accountId = openingEvent.accountId();
		adjustmentCase.openingEventId = openingEvent.id();
		adjustmentCase.openingEvent = openingEvent;
		adjustmentCase.status = BalanceAdjustmentStatus.OPEN;
		adjustmentCase.openedShortage = shortage;
		adjustmentCase.openedAt = Objects.requireNonNull(openedAt, "openedAt");
		adjustmentCase.record(openingEvent);
		return adjustmentCase;
	}

	public void record(LedgerEvent event) {
		Objects.requireNonNull(event, "event");
		if (!accountId.equals(event.accountId())) {
			throw new IllegalArgumentException("Ledger event must belong to the adjustment account");
		}
		if (eventLinks.stream().anyMatch(link -> link.eventId().equals(event.id()))) {
			return;
		}
		eventLinks.add(new BalanceAdjustmentCaseEvent(UUID.randomUUID(), this, event));
	}

	public void resolve(LedgerEvent resolutionEvent, Instant resolvedAt) {
		if (status != BalanceAdjustmentStatus.OPEN) {
			throw new IllegalStateException("Balance Adjustment Case is already resolved");
		}
		Objects.requireNonNull(resolutionEvent, "resolutionEvent");
		if (!accountId.equals(resolutionEvent.accountId())) {
			throw new IllegalArgumentException("Resolution event must belong to the adjustment account");
		}
		Instant resolutionTime = Objects.requireNonNull(resolvedAt, "resolvedAt");
		record(resolutionEvent);
		this.resolutionEvent = resolutionEvent;
		this.resolutionEventId = resolutionEvent.id();
		this.resolvedAt = resolutionTime;
		this.status = BalanceAdjustmentStatus.RESOLVED;
	}

	public UUID id() { return id; }
	public UUID accountId() { return accountId; }
	public boolean isOpen() { return status == BalanceAdjustmentStatus.OPEN; }
	public List<LedgerEvent> ledgerEvents() {
		return Collections.unmodifiableList(eventLinks.stream()
				.map(BalanceAdjustmentCaseEvent::ledgerEvent)
				.toList());
	}
}
