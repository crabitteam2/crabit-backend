package com.crabit.backend.wish;

import jakarta.persistence.Column;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "balance_observation",
		indexes = @Index(
				name = "idx_balance_observation_account_time", columnList = "account_id,observed_at"),
		check = {
				@CheckConstraint(name = "ck_observation_result",
						constraint = "(CAST(status AS VARCHAR) = 'SUCCEEDED' AND actual_card_balance IS NOT NULL AND failure_code IS NULL) OR (CAST(status AS VARCHAR) = 'FAILED' AND actual_card_balance IS NULL AND failure_code IS NOT NULL)"),
				@CheckConstraint(name = "ck_observation_balance_non_negative",
						constraint = "actual_card_balance IS NULL OR actual_card_balance >= 0")
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

	@Column(name = "actual_card_balance", updatable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount actualCardBalance;

	@Column(name = "failure_code", updatable = false, length = 80)
	private String failureCode;

	@Column(name = "observed_at", nullable = false, updatable = false)
	private Instant observedAt;

	protected BalanceObservation() {
	}

	private BalanceObservation(UUID accountId, BalanceObservationStatus status,
			KrwAmount actualCardBalance, String failureCode, Instant observedAt) {
		this.id = UUID.randomUUID();
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.status = Objects.requireNonNull(status, "status");
		this.actualCardBalance = actualCardBalance;
		this.failureCode = failureCode;
		this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
		if ((status == BalanceObservationStatus.SUCCEEDED) != (actualCardBalance != null)) {
			throw new IllegalArgumentException("Successful observation requires a balance; failed observation forbids one");
		}
		if (actualCardBalance != null && actualCardBalance.isNegative()) {
			throw new IllegalArgumentException("Actual Card Balance must be non-negative");
		}
		if (status == BalanceObservationStatus.FAILED && (failureCode == null || failureCode.isBlank())) {
			throw new IllegalArgumentException("Failed observation requires a failure code");
		}
	}

	public static BalanceObservation succeeded(UUID accountId, KrwAmount balance, Instant observedAt) {
		return new BalanceObservation(accountId, BalanceObservationStatus.SUCCEEDED, balance, null, observedAt);
	}

	public static BalanceObservation failed(UUID accountId, String failureCode, Instant observedAt) {
		return new BalanceObservation(accountId, BalanceObservationStatus.FAILED, null, failureCode, observedAt);
	}
}
