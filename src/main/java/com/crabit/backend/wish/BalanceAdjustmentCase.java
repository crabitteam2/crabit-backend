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
						name = "uk_adjustment_opening_observation",
						columnNames = "opening_balance_observation_id"),
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
						constraint = "(opening_event_id IS NULL AND opening_event_type IS NULL AND opening_event_delta IS NULL AND opening_balance_observation_first_successful = TRUE) OR (opening_event_id IS NOT NULL AND CAST(opening_event_type AS VARCHAR) = 'CARD_BALANCE_CHANGE' AND opening_event_delta < 0 AND opening_balance_observation_first_successful IS NULL)"),
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

	@Column(name = "opening_balance_observation_id", nullable = false, updatable = false)
	private UUID openingBalanceObservationId;

	@Column(name = "opening_balance_observation_first_successful", updatable = false)
	private Boolean openingBalanceObservationFirstSuccessful;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(value = {
		@JoinColumn(name = "opening_balance_observation_id", referencedColumnName = "id",
				insertable = false, updatable = false, nullable = false),
		@JoinColumn(name = "account_id", referencedColumnName = "account_id",
				insertable = false, updatable = false, nullable = false),
		@JoinColumn(name = "opened_at", referencedColumnName = "observed_at",
				insertable = false, updatable = false, nullable = false)
	}, foreignKey = @ForeignKey(name = "fk_adjustment_opening_observation_origin"))
	private BalanceObservation openingBalanceObservation;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumns(value = {
		@JoinColumn(name = "opening_balance_observation_id", referencedColumnName = "id",
				insertable = false, updatable = false),
		@JoinColumn(name = "account_id", referencedColumnName = "account_id",
				insertable = false, updatable = false),
		@JoinColumn(name = "opening_balance_observation_first_successful",
				referencedColumnName = "first_successful", insertable = false, updatable = false)
	}, foreignKey = @ForeignKey(name = "fk_adjustment_eventless_first_success"))
	private BalanceObservation eventlessFirstSuccessfulObservation;

	@Column(name = "opening_event_id", updatable = false)
	private UUID openingEventId;

	@Enumerated(EnumType.STRING)
	@Column(name = "opening_event_type", updatable = false, length = 48,
			columnDefinition = "varchar(48)")
	private LedgerEventType openingEventType;

	@Column(name = "opening_event_delta", updatable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount openingEventDelta;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumns(value = {
		@JoinColumn(name = "opening_event_id", referencedColumnName = "id",
				insertable = false, updatable = false),
		@JoinColumn(name = "account_id", referencedColumnName = "account_id",
				insertable = false, updatable = false),
		@JoinColumn(name = "opening_event_type", referencedColumnName = "event_type",
				insertable = false, updatable = false),
		@JoinColumn(name = "opening_event_delta", referencedColumnName = "account_delta",
				insertable = false, updatable = false),
		@JoinColumn(name = "opened_at", referencedColumnName = "occurred_at",
				insertable = false, updatable = false)
	}, foreignKey = @ForeignKey(name = "fk_adjustment_opening_event_proof"))
	private LedgerEvent openingEvent;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumns(value = {
		@JoinColumn(name = "opening_balance_observation_id", referencedColumnName = "id",
				insertable = false, updatable = false),
		@JoinColumn(name = "account_id", referencedColumnName = "account_id",
				insertable = false, updatable = false),
		@JoinColumn(name = "opening_event_id", referencedColumnName = "balance_change_event_id",
				insertable = false, updatable = false),
		@JoinColumn(name = "opening_event_type", referencedColumnName = "balance_change_event_type",
				insertable = false, updatable = false),
		@JoinColumn(name = "opening_event_delta", referencedColumnName = "balance_change_event_delta",
				insertable = false, updatable = false),
		@JoinColumn(name = "opened_at", referencedColumnName = "observed_at",
				insertable = false, updatable = false)
	}, foreignKey = @ForeignKey(name = "fk_adjustment_opening_event_observation_proof"))
	private BalanceObservation openingEventObservation;

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
			BalanceObservation openingObservation,
			LedgerEvent openingDecrease,
			KrwAmount shortage,
			Instant openedAt) {
		Objects.requireNonNull(openingObservation, "openingObservation");
		if (openingObservation.status() != BalanceObservationStatus.SUCCEEDED) {
			throw new IllegalArgumentException("Mismatch must open from a successful observation");
		}
		if (!Objects.requireNonNull(shortage, "shortage").isPositive()) {
			throw new IllegalArgumentException("Opening shortage must be positive");
		}
		Instant openingTime = Objects.requireNonNull(openedAt, "openedAt");
		if (!openingObservation.observedAt().equals(openingTime)) {
			throw new IllegalArgumentException(
					"Opening observation time must equal mismatch opening time");
		}
		if (openingDecrease == null && !openingObservation.isFirstConnection()) {
			throw new IllegalArgumentException(
					"Only a first successful observation may open without a decrease event");
		}
		if (openingDecrease != null) {
			requireOpeningDecrease(openingObservation, openingDecrease);
		}
		BalanceAdjustmentCase adjustmentCase = new BalanceAdjustmentCase();
		adjustmentCase.id = UUID.randomUUID();
		adjustmentCase.accountId = openingObservation.accountId();
		adjustmentCase.openingBalanceObservationId = openingObservation.id();
		adjustmentCase.openingBalanceObservation = openingObservation;
		adjustmentCase.openingBalanceObservationFirstSuccessful = openingDecrease == null
				? Boolean.TRUE : null;
		adjustmentCase.eventlessFirstSuccessfulObservation = openingDecrease == null
				? openingObservation : null;
		adjustmentCase.openingEventId = openingDecrease == null ? null : openingDecrease.id();
		adjustmentCase.openingEventType = openingDecrease == null ? null : openingDecrease.type();
		adjustmentCase.openingEventDelta = openingDecrease == null
				? null : openingDecrease.accountDelta();
		adjustmentCase.openingEvent = openingDecrease;
		adjustmentCase.openingEventObservation = openingDecrease == null
				? null : openingObservation;
		adjustmentCase.status = BalanceAdjustmentStatus.OPEN;
		adjustmentCase.openedShortage = shortage;
		adjustmentCase.openedAt = openingTime;
		if (openingDecrease != null) {
			adjustmentCase.recordInternal(
					openingDecrease, BalanceAdjustmentEventRole.OPENING_DECREASE, true);
		}
		return adjustmentCase;
	}

	private static void requireOpeningDecrease(
			BalanceObservation openingObservation, LedgerEvent openingDecrease) {
		if (openingDecrease.type() != LedgerEventType.CARD_BALANCE_CHANGE) {
			throw new IllegalArgumentException("Mismatch opening decrease must be a Card Balance Change");
		}
		if (!openingDecrease.accountDelta().isNegative()) {
			throw new IllegalArgumentException("Mismatch opening decrease must be negative");
		}
		if (!openingObservation.accountId().equals(openingDecrease.accountId())) {
			throw new IllegalArgumentException(
					"Opening decrease must belong to the observation account");
		}
		if (!openingDecrease.id().equals(openingObservation.balanceChangeEventId())
				|| openingObservation.balanceChangeEventType() != openingDecrease.type()
				|| !openingDecrease.accountDelta().equals(
						openingObservation.balanceChangeEventDelta())
				|| !openingDecrease.occurredAt().equals(openingObservation.observedAt())) {
			throw new IllegalArgumentException(
					"Opening decrease must be the observation's exact balance change event");
		}
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
		if (openingBalanceObservationId == null || openingBalanceObservation == null) {
			throw new IllegalStateException(
					"Adjustment episode must retain its opening observation");
		}
		long openingDecreaseLinks = eventLinks.stream()
				.filter(link -> link.role() == BalanceAdjustmentEventRole.OPENING_DECREASE)
				.count();
		if (openingEventId == null) {
			if (!Boolean.TRUE.equals(openingBalanceObservationFirstSuccessful)
					|| openingDecreaseLinks != 0) {
				throw new IllegalStateException(
						"Eventless adjustment must originate from the first successful observation");
			}
		} else if (openingDecreaseLinks != 1
				|| eventLinks.isEmpty()
				|| eventLinks.get(0).role() != BalanceAdjustmentEventRole.OPENING_DECREASE
				|| !eventLinks.get(0).eventId().equals(openingEventId)) {
			throw new IllegalStateException(
					"Adjustment episode must contain its opening decrease first");
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
	public UUID openingBalanceObservationId() { return openingBalanceObservationId; }
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
