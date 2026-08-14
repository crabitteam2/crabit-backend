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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "balance_observation",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_observation_id_account", columnNames = {"id", "account_id"}),
		indexes = @Index(
				name = "idx_balance_observation_account_time", columnList = "account_id,observed_at"),
		check = {
				@CheckConstraint(name = "ck_observation_result",
						constraint = "(CAST(status AS VARCHAR) = 'SUCCEEDED' AND actual_card_balance IS NOT NULL AND failure_code IS NULL) OR (CAST(status AS VARCHAR) = 'FAILED' AND actual_card_balance IS NULL AND failure_code IS NOT NULL)"),
				@CheckConstraint(name = "ck_observation_balance_non_negative",
						constraint = "actual_card_balance IS NULL OR actual_card_balance >= 0"),
				@CheckConstraint(name = "ck_observation_change_provenance",
						constraint = "balance_change_event_id IS NULL OR (CAST(status AS VARCHAR) = 'SUCCEEDED' AND previous_successful_observation_id IS NOT NULL)")
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

	@Column(name = "previous_successful_observation_id", updatable = false)
	private UUID previousSuccessfulObservationId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumns(value = {
			@JoinColumn(name = "previous_successful_observation_id", referencedColumnName = "id",
					insertable = false, updatable = false),
			@JoinColumn(name = "account_id", referencedColumnName = "account_id",
					insertable = false, updatable = false)
	}, foreignKey = @ForeignKey(name = "fk_observation_previous_success_account"))
	private BalanceObservation previousSuccessfulObservation;

	@Column(name = "balance_change_event_id", updatable = false)
	private UUID balanceChangeEventId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumns(value = {
			@JoinColumn(name = "balance_change_event_id", referencedColumnName = "id",
					insertable = false, updatable = false),
			@JoinColumn(name = "account_id", referencedColumnName = "account_id",
					insertable = false, updatable = false)
	}, foreignKey = @ForeignKey(name = "fk_observation_change_event_account"))
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
			LedgerEvent balanceChangeEvent,
			Instant observedAt) {
		this.id = UUID.randomUUID();
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.status = Objects.requireNonNull(status, "status");
		this.lookupMethod = Objects.requireNonNull(lookupMethod, "lookupMethod");
		this.actualCardBalance = actualCardBalance;
		this.failureCode = failureCode;
		this.previousSuccessfulObservation = previousSuccessfulObservation;
		this.previousSuccessfulObservationId = previousSuccessfulObservation == null
				? null : previousSuccessfulObservation.id();
		this.balanceChangeEvent = balanceChangeEvent;
		this.balanceChangeEventId = balanceChangeEvent == null ? null : balanceChangeEvent.id();
		this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
	}

	public static BalanceObservation firstSucceeded(
			UUID accountId,
			BalanceLookupMethod lookupMethod,
			KrwAmount balance,
			Instant observedAt) {
		KrwAmount actualBalance = requireNonNegative(balance);
		return new BalanceObservation(accountId, BalanceObservationStatus.SUCCEEDED,
				lookupMethod, actualBalance, null, null, null, observedAt);
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
		validateChangeEvent(previousSuccessfulObservation.accountId, delta, balanceChangeEvent);
		return new BalanceObservation(previousSuccessfulObservation.accountId,
				BalanceObservationStatus.SUCCEEDED, lookupMethod, actualBalance, null,
				previousSuccessfulObservation, balanceChangeEvent, observationTime);
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
				lookupMethod, null, failureCode, null, null, observedAt);
	}

	private static void validateChangeEvent(
			UUID accountId, KrwAmount delta, LedgerEvent balanceChangeEvent) {
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
	public UUID balanceChangeEventId() { return balanceChangeEventId; }
	public Instant observedAt() { return observedAt; }
	public boolean isFirstConnection() {
		return status == BalanceObservationStatus.SUCCEEDED
				&& previousSuccessfulObservationId == null;
	}
}
