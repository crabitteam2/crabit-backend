package com.crabit.backend.wish;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "balance_observation",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_observation_id_account", columnNames = {"id", "account_id"}),
				@UniqueConstraint(
						name = "uk_observation_previous_proof",
						columnNames = {"id", "account_id", "actual_card_balance"}),
				@UniqueConstraint(
						name = "uk_observation_first_success",
						columnNames = {"account_id", "first_successful"}),
				@UniqueConstraint(
						name = "uk_observation_previous_successor",
						columnNames = "previous_successful_observation_id"),
				@UniqueConstraint(
						name = "uk_observation_change_event",
						columnNames = "balance_change_event_id")
		},
		indexes = @Index(
				name = "idx_balance_observation_account_time", columnList = "account_id,observed_at"),
		check = {
				@CheckConstraint(name = "ck_observation_result",
						constraint = "(CAST(status AS VARCHAR) = 'SUCCEEDED' AND actual_card_balance IS NOT NULL AND failure_code IS NULL) OR (CAST(status AS VARCHAR) = 'FAILED' AND actual_card_balance IS NULL AND failure_code IS NOT NULL)"),
				@CheckConstraint(name = "ck_observation_balance_non_negative",
						constraint = "actual_card_balance IS NULL OR actual_card_balance >= 0"),
				@CheckConstraint(name = "ck_observation_success_chain",
						constraint = "(CAST(status AS VARCHAR) = 'FAILED' AND first_successful IS NULL AND previous_successful_observation_id IS NULL AND previous_successful_balance IS NULL) OR (CAST(status AS VARCHAR) = 'SUCCEEDED' AND ((first_successful = TRUE AND previous_successful_observation_id IS NULL AND previous_successful_balance = 0) OR (first_successful IS NULL AND previous_successful_observation_id IS NOT NULL AND previous_successful_balance IS NOT NULL)))"),
				@CheckConstraint(name = "ck_observation_change_provenance",
						constraint = "(CAST(status AS VARCHAR) = 'FAILED' AND balance_change_event_id IS NULL AND balance_change_event_type IS NULL AND balance_change_event_delta IS NULL) OR (CAST(status AS VARCHAR) = 'SUCCEEDED' AND ((balance_change_event_id IS NULL AND balance_change_event_type IS NULL AND balance_change_event_delta IS NULL AND actual_card_balance = previous_successful_balance) OR (balance_change_event_id IS NOT NULL AND CAST(balance_change_event_type AS VARCHAR) = 'CARD_BALANCE_CHANGE' AND balance_change_event_delta IS NOT NULL AND balance_change_event_delta <> 0 AND actual_card_balance - previous_successful_balance = balance_change_event_delta)))")
		})
public class BalanceObservation {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_observation_account"))
	private CardBalanceAccount account;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, updatable = false, length = 16,
			columnDefinition = "varchar(16)")
	private BalanceObservationStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "lookup_method", nullable = false, updatable = false, length = 24,
			columnDefinition = "varchar(24)")
	private BalanceLookupMethod lookupMethod;

	@Column(name = "actual_card_balance", updatable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount actualCardBalance;

	@Column(name = "failure_code", updatable = false, length = 80)
	private String failureCode;

	@Column(name = "first_successful", updatable = false)
	private Boolean firstSuccessful;

	@Column(name = "previous_successful_observation_id", updatable = false)
	private UUID previousSuccessfulObservationId;

	@Column(name = "previous_successful_balance", updatable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount previousSuccessfulBalance;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumns(value = {
			@JoinColumn(name = "previous_successful_observation_id", referencedColumnName = "id",
					insertable = false, updatable = false),
			@JoinColumn(name = "account_id", referencedColumnName = "account_id",
					insertable = false, updatable = false),
			@JoinColumn(name = "previous_successful_balance", referencedColumnName = "actual_card_balance",
					insertable = false, updatable = false)
	}, foreignKey = @ForeignKey(name = "fk_observation_previous_success_proof"))
	private BalanceObservation previousSuccessfulObservation;

	@Column(name = "balance_change_event_id", updatable = false)
	private UUID balanceChangeEventId;

	@Enumerated(EnumType.STRING)
	@Column(name = "balance_change_event_type", updatable = false, length = 48,
			columnDefinition = "varchar(48)")
	private LedgerEventType balanceChangeEventType;

	@Column(name = "balance_change_event_delta", updatable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount balanceChangeEventDelta;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumns(value = {
			@JoinColumn(name = "balance_change_event_id", referencedColumnName = "id",
					insertable = false, updatable = false),
			@JoinColumn(name = "account_id", referencedColumnName = "account_id",
					insertable = false, updatable = false),
			@JoinColumn(name = "balance_change_event_type", referencedColumnName = "event_type",
					insertable = false, updatable = false),
			@JoinColumn(name = "balance_change_event_delta", referencedColumnName = "account_delta",
					insertable = false, updatable = false),
			@JoinColumn(name = "observed_at", referencedColumnName = "occurred_at",
					insertable = false, updatable = false)
	}, foreignKey = @ForeignKey(name = "fk_observation_change_event_proof"))
	private LedgerEvent balanceChangeEvent;

	@Column(name = "observed_at", nullable = false, updatable = false)
	private Instant observedAt;

	protected BalanceObservation() {
	}

	private BalanceObservation(
			UUID accountId,
			BalanceObservationStatus status,
			BalanceLookupMethod lookupMethod,
			KrwAmount actualCardBalance,
			String failureCode,
			BalanceObservation previousSuccessfulObservation,
			KrwAmount previousSuccessfulBalance,
			LedgerEvent balanceChangeEvent,
			Instant observedAt) {
		this.id = UUID.randomUUID();
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.status = Objects.requireNonNull(status, "status");
		this.lookupMethod = Objects.requireNonNull(lookupMethod, "lookupMethod");
		this.actualCardBalance = actualCardBalance;
		this.failureCode = failureCode;
		this.firstSuccessful = status == BalanceObservationStatus.SUCCEEDED
				&& previousSuccessfulObservation == null ? Boolean.TRUE : null;
		this.previousSuccessfulObservation = previousSuccessfulObservation;
		this.previousSuccessfulObservationId = previousSuccessfulObservation == null
				? null : previousSuccessfulObservation.id();
		this.previousSuccessfulBalance = previousSuccessfulBalance;
		this.balanceChangeEvent = balanceChangeEvent;
		this.balanceChangeEventId = balanceChangeEvent == null ? null : balanceChangeEvent.id();
		this.balanceChangeEventType = balanceChangeEvent == null ? null : balanceChangeEvent.type();
		this.balanceChangeEventDelta = balanceChangeEvent == null
				? null : balanceChangeEvent.accountDelta();
		this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
	}

	public static BalanceObservation firstSucceeded(
			UUID accountId,
			BalanceLookupMethod lookupMethod,
			KrwAmount balance,
			Instant observedAt) {
		return firstSucceeded(accountId, lookupMethod, balance, null, observedAt);
	}

	public static BalanceObservation firstSucceeded(
			UUID accountId,
			BalanceLookupMethod lookupMethod,
			KrwAmount balance,
			LedgerEvent balanceChangeEvent,
			Instant observedAt) {
		KrwAmount actualBalance = requireNonNegative(balance);
		validateChangeEvent(accountId, actualBalance, balanceChangeEvent,
				Objects.requireNonNull(observedAt, "observedAt"));
		return new BalanceObservation(accountId, BalanceObservationStatus.SUCCEEDED,
				lookupMethod, actualBalance, null, null, KrwAmount.zero(),
				balanceChangeEvent, observedAt);
	}

	public static BalanceObservation succeeded(
			BalanceObservation previousSuccessfulObservation,
			BalanceLookupMethod lookupMethod,
			KrwAmount balance,
			LedgerEvent balanceChangeEvent,
			Instant observedAt) {
		Objects.requireNonNull(previousSuccessfulObservation, "previousSuccessfulObservation");
		if (previousSuccessfulObservation.status != BalanceObservationStatus.SUCCEEDED) {
			throw new IllegalArgumentException("Previous observation must be successful");
		}
		Instant observationTime = Objects.requireNonNull(observedAt, "observedAt");
		if (observationTime.isBefore(previousSuccessfulObservation.observedAt)) {
			throw new IllegalArgumentException("Observation cannot precede its previous success");
		}
		KrwAmount actualBalance = requireNonNegative(balance);
		KrwAmount delta = actualBalance.minus(previousSuccessfulObservation.actualCardBalance);
		validateChangeEvent(previousSuccessfulObservation.accountId, delta,
				balanceChangeEvent, observationTime);
		return new BalanceObservation(previousSuccessfulObservation.accountId,
				BalanceObservationStatus.SUCCEEDED, lookupMethod, actualBalance, null,
				previousSuccessfulObservation, previousSuccessfulObservation.actualCardBalance,
				balanceChangeEvent, observationTime);
	}

	public static BalanceObservation failed(
			UUID accountId,
			BalanceLookupMethod lookupMethod,
			String failureCode,
			Instant observedAt) {
		if (failureCode == null || failureCode.isBlank()) {
			throw new IllegalArgumentException("Failed observation requires a failure code");
		}
		return new BalanceObservation(accountId, BalanceObservationStatus.FAILED,
				lookupMethod, null, failureCode, null, null, null, observedAt);
	}

	private static void validateChangeEvent(
			UUID accountId, KrwAmount delta, LedgerEvent balanceChangeEvent, Instant observedAt) {
		if (delta.isZero()) {
			if (balanceChangeEvent != null) {
				throw new IllegalArgumentException("A zero balance change must not have a change event");
			}
			return;
		}
		if (balanceChangeEvent == null) {
			throw new IllegalArgumentException("A nonzero balance change requires its exact change event");
		}
		if (!accountId.equals(balanceChangeEvent.accountId())) {
			throw new IllegalArgumentException("Balance change event must belong to the observation account");
		}
		if (balanceChangeEvent.type() != LedgerEventType.CARD_BALANCE_CHANGE
				|| !delta.equals(balanceChangeEvent.accountDelta())) {
			throw new IllegalArgumentException("Balance change event must exactly match the observed delta");
		}
		if (!observedAt.equals(balanceChangeEvent.occurredAt())) {
			throw new IllegalArgumentException("Balance change event time must equal the observation time");
		}
	}

	@PrePersist
	private void validatePersistenceProof() {
		if (status == BalanceObservationStatus.FAILED) {
			if (balanceChangeEventId != null || balanceChangeEventType != null
					|| balanceChangeEventDelta != null) {
				throw new IllegalStateException("Failed observation cannot carry a money fact");
			}
			return;
		}
		KrwAmount delta = actualCardBalance.minus(previousSuccessfulBalance);
		if (delta.isZero()) {
			if (balanceChangeEventId != null || balanceChangeEventType != null
					|| balanceChangeEventDelta != null) {
				throw new IllegalStateException("Zero change cannot carry a money fact");
			}
		} else if (balanceChangeEventId == null
				|| balanceChangeEventType != LedgerEventType.CARD_BALANCE_CHANGE
				|| !delta.equals(balanceChangeEventDelta)) {
			throw new IllegalStateException("Persisted balance-change proof is not exact");
		}
		if (Boolean.TRUE.equals(firstSuccessful) != (previousSuccessfulObservationId == null)) {
			throw new IllegalStateException("Successful observation chain root is inconsistent");
		}
	}

	private static KrwAmount requireNonNegative(KrwAmount balance) {
		KrwAmount actualBalance = Objects.requireNonNull(balance, "balance");
		if (actualBalance.isNegative()) {
			throw new IllegalArgumentException("Actual Card Balance must be non-negative");
		}
		return actualBalance;
	}

	public UUID id() { return id; }
	public UUID accountId() { return accountId; }
	public BalanceObservationStatus status() { return status; }
	public BalanceLookupMethod lookupMethod() { return lookupMethod; }
	public KrwAmount actualCardBalance() { return actualCardBalance; }
	public String failureCode() { return failureCode; }
	public UUID previousSuccessfulObservationId() { return previousSuccessfulObservationId; }
	public KrwAmount previousSuccessfulBalance() { return previousSuccessfulBalance; }
	public UUID balanceChangeEventId() { return balanceChangeEventId; }
	public LedgerEventType balanceChangeEventType() { return balanceChangeEventType; }
	public KrwAmount balanceChangeEventDelta() { return balanceChangeEventDelta; }
	public Instant observedAt() { return observedAt; }
	public boolean isFirstConnection() { return Boolean.TRUE.equals(firstSuccessful); }
}
