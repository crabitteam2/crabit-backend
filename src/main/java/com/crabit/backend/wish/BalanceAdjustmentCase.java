package com.crabit.backend.wish;

import com.crabit.backend.account.CardBalanceAccount;

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
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "balance_adjustment_case",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_adjustment_case_id_account", columnNames = {"id", "account_id"}),
				@UniqueConstraint(
						name = "uk_adjustment_opening_event", columnNames = "opening_event_id"),
				@UniqueConstraint(
						name = "uk_adjustment_resolution_event", columnNames = "resolution_event_id")
		},
		indexes = @Index(name = "idx_adjustment_account_status", columnList = "account_id,status"),
		check = {
				@CheckConstraint(name = "ck_adjustment_shortage_positive",
						constraint = "opened_shortage > 0"),
				@CheckConstraint(name = "ck_adjustment_opening_provenance",
						constraint = "CAST(opening_event_type AS VARCHAR) = 'CARD_BALANCE_CHANGE' AND opening_event_delta < 0"),
				@CheckConstraint(name = "ck_adjustment_resolution",
						constraint = "(CAST(status AS VARCHAR) = 'OPEN' AND resolved_at IS NULL AND resolution_event_id IS NULL) OR (CAST(status AS VARCHAR) = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution_event_id IS NOT NULL AND resolved_at >= opened_at)")
		})
public class BalanceAdjustmentCase implements Persistable<UUID> {

	@Id
	private UUID id;

	@Transient
	private boolean newEntity = true;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_adjustment_account"))
	private CardBalanceAccount account;

	@Column(name = "opening_event_id", nullable = false, updatable = false)
	private UUID openingEventId;

	@Enumerated(EnumType.STRING)
	@Column(name = "opening_event_type", nullable = false, updatable = false, length = 48,
			columnDefinition = "varchar(48)")
	private LedgerEventType openingEventType;

	@Column(name = "opening_event_delta", nullable = false, updatable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount openingEventDelta;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(value = {
		@JoinColumn(name = "opening_event_id", referencedColumnName = "id",
				insertable = false, updatable = false, nullable = false),
		@JoinColumn(name = "account_id", referencedColumnName = "account_id",
				insertable = false, updatable = false, nullable = false),
		@JoinColumn(name = "opening_event_type", referencedColumnName = "event_type",
				insertable = false, updatable = false, nullable = false),
		@JoinColumn(name = "opening_event_delta", referencedColumnName = "account_delta",
				insertable = false, updatable = false, nullable = false),
		@JoinColumn(name = "opened_at", referencedColumnName = "occurred_at",
				insertable = false, updatable = false, nullable = false)
	}, foreignKey = @ForeignKey(name = "fk_adjustment_opening_event_proof"))
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
	@OrderBy("sequenceNumber ASC")
	private final List<BalanceAdjustmentCaseEvent> eventLinks = new ArrayList<>();

	protected BalanceAdjustmentCase() {
	}

	public static BalanceAdjustmentCase open(
			LedgerEvent openingEvent, KrwAmount shortage, Instant openedAt) {
		Objects.requireNonNull(openingEvent, "openingEvent");
		if (openingEvent.type() != LedgerEventType.CARD_BALANCE_CHANGE) {
			throw new IllegalArgumentException("Mismatch must open from a Card Balance Change event");
		}
		if (!openingEvent.accountDelta().isNegative()) {
			throw new IllegalArgumentException(
					"Mismatch must open from a negative Card Balance Change event");
		}
		if (!Objects.requireNonNull(shortage, "shortage").isPositive()) {
			throw new IllegalArgumentException("Opening shortage must be positive");
		}
		BalanceAdjustmentCase adjustmentCase = new BalanceAdjustmentCase();
		adjustmentCase.id = UUID.randomUUID();
		adjustmentCase.accountId = openingEvent.accountId();
		adjustmentCase.openingEventId = openingEvent.id();
		adjustmentCase.openingEventType = openingEvent.type();
		adjustmentCase.openingEventDelta = openingEvent.accountDelta();
		adjustmentCase.openingEvent = openingEvent;
		adjustmentCase.status = BalanceAdjustmentStatus.OPEN;
		adjustmentCase.openedShortage = shortage;
		adjustmentCase.openedAt = Objects.requireNonNull(openedAt, "openedAt");
		if (!openingEvent.occurredAt().equals(adjustmentCase.openedAt)) {
			throw new IllegalArgumentException("Opening event time must equal mismatch opening time");
		}
		adjustmentCase.recordInternal(openingEvent, BalanceAdjustmentEventRole.OPENING, true);
		return adjustmentCase;
	}

	public void record(LedgerEvent event) {
		if (status != BalanceAdjustmentStatus.OPEN) {
			throw new IllegalStateException("Resolved Balance Adjustment Case is immutable");
		}
		recordInternal(event, BalanceAdjustmentEventRole.INTERMEDIATE, true);
	}

	void recordCardBalanceChangeInPersistenceOrder(LedgerEvent event) {
		if (status != BalanceAdjustmentStatus.OPEN) {
			throw new IllegalStateException("Resolved Balance Adjustment Case is immutable");
		}
		requireCardBalanceChange(event);
		recordInternal(event, BalanceAdjustmentEventRole.INTERMEDIATE, false);
	}

	private void recordInternal(
			LedgerEvent event,
			BalanceAdjustmentEventRole role,
			boolean requireChronologicalEventTime) {
		Objects.requireNonNull(event, "event");
		if (!accountId.equals(event.accountId())) {
			throw new IllegalArgumentException("Ledger event must belong to the adjustment account");
		}
		if (eventLinks.stream().anyMatch(link -> link.eventId().equals(event.id()))) {
			throw new IllegalArgumentException("Ledger event is already linked to this adjustment case");
		}
		if (requireChronologicalEventTime && event.occurredAt().isBefore(openedAt)) {
			throw new IllegalArgumentException("Adjustment event cannot precede the opening event");
		}
		if (requireChronologicalEventTime && !eventLinks.isEmpty()
				&& event.occurredAt().isBefore(eventLinks.get(eventLinks.size() - 1).occurredAt())) {
			throw new IllegalArgumentException("Adjustment events must be recorded chronologically");
		}
		eventLinks.add(new BalanceAdjustmentCaseEvent(
				UUID.randomUUID(), this, event, eventLinks.size(), role));
	}

	public void resolve(LedgerEvent resolutionEvent, Instant resolvedAt) {
		resolve(resolutionEvent, resolvedAt, true);
	}

	void resolveCardBalanceChangeInPersistenceOrder(
			LedgerEvent resolutionEvent, Instant resolvedAt) {
		requireCardBalanceChange(resolutionEvent);
		resolve(resolutionEvent, resolvedAt, false);
	}

	private void resolve(
			LedgerEvent resolutionEvent,
			Instant resolvedAt,
			boolean requireChronologicalEventTime) {
		if (status != BalanceAdjustmentStatus.OPEN) {
			throw new IllegalStateException("Balance Adjustment Case is already resolved");
		}
		Objects.requireNonNull(resolutionEvent, "resolutionEvent");
		if (!accountId.equals(resolutionEvent.accountId())) {
			throw new IllegalArgumentException("Resolution event must belong to the adjustment account");
		}
		Instant resolutionTime = Objects.requireNonNull(resolvedAt, "resolvedAt");
		if (requireChronologicalEventTime && (resolutionTime.isBefore(openedAt)
				|| resolutionTime.isBefore(resolutionEvent.occurredAt()))) {
			throw new IllegalArgumentException("Resolution time must follow its episode event");
		}
		if (!requireChronologicalEventTime) {
			resolutionTime = latestEpisodeTime(resolutionTime, resolutionEvent.occurredAt());
		}
		recordInternal(
				resolutionEvent,
				BalanceAdjustmentEventRole.RESOLUTION,
				requireChronologicalEventTime);
		this.resolutionEvent = resolutionEvent;
		this.resolutionEventId = resolutionEvent.id();
		this.resolvedAt = resolutionTime;
		this.status = BalanceAdjustmentStatus.RESOLVED;
	}

	private static void requireCardBalanceChange(LedgerEvent event) {
		if (Objects.requireNonNull(event, "event").type()
				!= LedgerEventType.CARD_BALANCE_CHANGE) {
			throw new IllegalArgumentException(
					"Persistence-ordered adjustment events must be Card Balance Changes");
		}
	}

	private Instant latestEpisodeTime(Instant requestedResolution, Instant resolutionEventTime) {
		Instant latest = requestedResolution.isAfter(resolutionEventTime)
				? requestedResolution : resolutionEventTime;
		for (BalanceAdjustmentCaseEvent link : eventLinks) {
			if (link.occurredAt().isAfter(latest)) {
				latest = link.occurredAt();
			}
		}
		return latest;
	}

	@PrePersist
	@PreUpdate
	private void validateEpisodeBoundary() {
		if (eventLinks.isEmpty()
				|| eventLinks.get(0).role() != BalanceAdjustmentEventRole.OPENING
				|| !eventLinks.get(0).eventId().equals(openingEventId)) {
			throw new IllegalStateException("Adjustment episode must contain its opening event first");
		}
		for (int index = 0; index < eventLinks.size(); index++) {
			BalanceAdjustmentCaseEvent link = eventLinks.get(index);
			if (link.sequenceNumber() != index || !accountId.equals(link.accountId())) {
				throw new IllegalStateException("Adjustment episode order or account is inconsistent");
			}
		}
		long resolutionLinks = eventLinks.stream()
				.filter(link -> link.role() == BalanceAdjustmentEventRole.RESOLUTION)
				.count();
		if (status == BalanceAdjustmentStatus.OPEN && resolutionLinks != 0) {
			throw new IllegalStateException("Open adjustment episode cannot contain a resolution");
		}
		if (status == BalanceAdjustmentStatus.RESOLVED
				&& (resolutionLinks != 1
				|| !eventLinks.get(eventLinks.size() - 1).eventId().equals(resolutionEventId)
				|| eventLinks.get(eventLinks.size() - 1).role()
						!= BalanceAdjustmentEventRole.RESOLUTION)) {
			throw new IllegalStateException("Resolved episode must contain its resolution event last");
		}
	}

	void validatePersistedLink(BalanceAdjustmentCaseEvent link) {
		int index = eventLinks.indexOf(link);
		if (index < 0 || index != link.sequenceNumber()) {
			throw new IllegalStateException("Adjustment event link must belong to its ordered episode");
		}
		validateEpisodeBoundary();
	}

	public UUID id() { return id; }
	@Override
	public UUID getId() { return id; }
	@Override
	public boolean isNew() { return newEntity; }

	@PostLoad
	@PostPersist
	private void markNotNew() { newEntity = false; }
	public UUID accountId() { return accountId; }
	public boolean isOpen() { return status == BalanceAdjustmentStatus.OPEN; }
	public Instant openedAt() { return openedAt; }
	public Instant resolvedAt() { return resolvedAt; }
	public UUID openingEventId() { return openingEventId; }
	public UUID resolutionEventId() { return resolutionEventId; }
	public List<BalanceAdjustmentCaseEvent> eventLinks() {
		return Collections.unmodifiableList(eventLinks);
	}
	public List<LedgerEvent> ledgerEvents() {
		return Collections.unmodifiableList(eventLinks.stream()
				.map(BalanceAdjustmentCaseEvent::ledgerEvent)
				.toList());
	}
}
