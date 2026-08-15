package com.crabit.backend.wish;

import com.crabit.backend.account.CardBalanceAccount;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
	@Table(name = "ledger_event",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_ledger_event_id_account", columnNames = {"id", "account_id"}),
				@UniqueConstraint(
						name = "uk_ledger_event_observation_proof",
						columnNames = {
								"id", "account_id", "event_type", "account_delta", "occurred_at"}),
				@UniqueConstraint(
						name = "uk_ledger_event_deposit_observation",
						columnNames = "deposit_balance_observation_id")
		},
		indexes = @Index(
				name = "idx_ledger_event_account_occurred", columnList = "account_id,occurred_at"),
		check = @CheckConstraint(
				name = "ck_ledger_event_deposit_observation",
				constraint = "(CAST(event_type AS VARCHAR) = 'WISH_DEPOSIT' AND deposit_balance_observation_id IS NOT NULL AND CAST(deposit_observation_status AS VARCHAR) = 'SUCCEEDED' AND CAST(deposit_observation_lookup_method AS VARCHAR) = 'PRE_DEPOSIT') OR (CAST(event_type AS VARCHAR) <> 'WISH_DEPOSIT' AND deposit_balance_observation_id IS NULL AND deposit_observation_status IS NULL AND deposit_observation_lookup_method IS NULL)"))
public class LedgerEvent {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_ledger_event_account"))
	private CardBalanceAccount account;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, updatable = false, length = 48,
			columnDefinition = "varchar(48)")
	private LedgerEventType type;

	@Column(name = "account_delta", nullable = false, updatable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount accountDelta;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Column(name = "deposit_balance_observation_id", updatable = false)
	private UUID depositBalanceObservationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "deposit_observation_status", updatable = false, length = 16,
			columnDefinition = "varchar(16)")
	private BalanceObservationStatus depositObservationStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "deposit_observation_lookup_method", updatable = false, length = 24,
			columnDefinition = "varchar(24)")
	private BalanceLookupMethod depositObservationLookupMethod;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumns(value = {
		@JoinColumn(name = "deposit_balance_observation_id", referencedColumnName = "id",
				insertable = false, updatable = false),
		@JoinColumn(name = "account_id", referencedColumnName = "account_id",
				insertable = false, updatable = false),
		@JoinColumn(name = "deposit_observation_status", referencedColumnName = "status",
				insertable = false, updatable = false),
		@JoinColumn(name = "deposit_observation_lookup_method", referencedColumnName = "lookup_method",
				insertable = false, updatable = false)
	}, foreignKey = @ForeignKey(name = "fk_ledger_event_deposit_observation_proof"))
	private BalanceObservation depositBalanceObservation;

	@Column(name = "correction_of_event_id", updatable = false)
	private UUID correctionOfEventId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumns(value = {
		@JoinColumn(name = "correction_of_event_id", referencedColumnName = "id",
				insertable = false, updatable = false),
		@JoinColumn(name = "account_id", referencedColumnName = "account_id",
				insertable = false, updatable = false)
	}, foreignKey = @ForeignKey(name = "fk_ledger_event_correction_account"))
	private LedgerEvent correctionOfEvent;

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
			UUID depositBalanceObservationId,
			BalanceObservationStatus depositObservationStatus,
			BalanceLookupMethod depositObservationLookupMethod,
			UUID correctionOfEventId) {
		this.id = Objects.requireNonNull(id, "id");
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.type = Objects.requireNonNull(type, "type");
		this.accountDelta = Objects.requireNonNull(accountDelta, "accountDelta");
		this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
		this.depositBalanceObservationId = depositBalanceObservationId;
		this.depositObservationStatus = depositObservationStatus;
		this.depositObservationLookupMethod = depositObservationLookupMethod;
		this.correctionOfEventId = correctionOfEventId;
	}

	static LedgerEvent transfer(
			CardBalanceAccount account,
			Wish sourceWish,
			Wish destinationWish,
			KrwAmount amount,
			Instant occurredAt) {
		Objects.requireNonNull(account, "account");
		Objects.requireNonNull(sourceWish, "sourceWish");
		Objects.requireNonNull(destinationWish, "destinationWish");
		if (!Objects.requireNonNull(amount, "amount").isPositive()) {
			throw new IllegalArgumentException("Transfer amount must be positive");
		}
		if (sourceWish.id().equals(destinationWish.id())) {
			throw new IllegalArgumentException("Transfer Wishes must be different");
		}
		if (!account.isActive()) {
			throw new IllegalStateException("Transfer account must be active");
		}
		if (!sourceWish.accountId().equals(account.id())
				|| !destinationWish.accountId().equals(account.id())) {
			throw new IllegalArgumentException("Transfer Wishes must belong to the locked account");
		}
		if (!sourceWish.academyId().equals(account.academyId())
				|| !destinationWish.academyId().equals(account.academyId())) {
			throw new IllegalArgumentException("Transfer Wishes must belong to the account academy");
		}
		Instant eventTime = Objects.requireNonNull(occurredAt, "occurredAt");
		sourceWish.validateTransferOut(amount);
		destinationWish.validateTransferIn(amount);

		LedgerEvent event = new LedgerEvent(UUID.randomUUID(), account.id(),
				LedgerEventType.WISH_TRANSFER, KrwAmount.zero(), eventTime,
				null, null, null, null);
		event.addWishEffect(sourceWish.id(), sourceWish.purpose(), amount.negate());
		event.addWishEffect(destinationWish.id(), destinationWish.purpose(), amount);
		sourceWish.applyValidatedTransferOut(amount);
		destinationWish.applyValidatedTransferIn(amount);
		return event;
	}

	static LedgerEvent cardBalanceChange(
			CardBalanceAccount account, KrwAmount accountDelta, Instant occurredAt) {
		Objects.requireNonNull(account, "account");
		if (!account.isActive()) {
			throw new IllegalStateException("Balance change account must be active");
		}
		if (Objects.requireNonNull(accountDelta, "accountDelta").isZero()) {
			throw new IllegalArgumentException("Card Balance Change delta must be nonzero");
		}
		return new LedgerEvent(UUID.randomUUID(), account.id(), LedgerEventType.CARD_BALANCE_CHANGE,
				accountDelta, Objects.requireNonNull(occurredAt, "occurredAt"),
				null, null, null, null);
	}

	static LedgerEvent wishDeposit(
			CardBalanceAccount account,
			Wish wish,
			KrwAmount amount,
			BalanceObservation depositBalanceObservation,
			Instant occurredAt) {
		Objects.requireNonNull(depositBalanceObservation, "depositBalanceObservation");
		if (!account.id().equals(depositBalanceObservation.accountId())
				|| depositBalanceObservation.status() != BalanceObservationStatus.SUCCEEDED
				|| depositBalanceObservation.lookupMethod() != BalanceLookupMethod.PRE_DEPOSIT) {
			throw new IllegalArgumentException(
					"Wish deposit requires a successful PRE_DEPOSIT observation for the account");
		}
		return wishChange(account, wish, amount, LedgerEventType.WISH_DEPOSIT,
				depositBalanceObservation, occurredAt);
	}

	static LedgerEvent wishWithdrawal(
			CardBalanceAccount account,
			Wish wish,
			KrwAmount amount,
			LedgerEventType type,
			Instant occurredAt) {
		if (type != LedgerEventType.WISH_WITHDRAWAL
				&& type != LedgerEventType.WISH_COMPLETION_RETURN
				&& type != LedgerEventType.WISH_ABANDONMENT_RETURN
				&& type != LedgerEventType.WISH_DELETION_RETURN) {
			throw new IllegalArgumentException("Withdrawal event type is not a Wish return");
		}
		return wishChange(account, wish, amount.negate(), type, null, occurredAt);
	}

	private static LedgerEvent wishChange(
			CardBalanceAccount account,
			Wish wish,
			KrwAmount wishDelta,
			LedgerEventType type,
			BalanceObservation depositBalanceObservation,
			Instant occurredAt) {
		Objects.requireNonNull(account, "account");
		Objects.requireNonNull(wish, "wish");
		if (!account.isActive()) {
			throw new IllegalStateException("Wish money account must be active");
		}
		if (!account.id().equals(wish.accountId()) || !account.academyId().equals(wish.academyId())) {
			throw new IllegalArgumentException("Wish must belong to the locked account and academy");
		}
		if (Objects.requireNonNull(wishDelta, "wishDelta").isZero()) {
			throw new IllegalArgumentException("Wish money event delta must be nonzero");
		}
		LedgerEvent event = new LedgerEvent(UUID.randomUUID(), account.id(), type,
				KrwAmount.zero(), Objects.requireNonNull(occurredAt, "occurredAt"),
				depositBalanceObservation == null ? null : depositBalanceObservation.id(),
				depositBalanceObservation == null ? null : depositBalanceObservation.status(),
				depositBalanceObservation == null ? null : depositBalanceObservation.lookupMethod(),
				null);
		event.addWishEffect(wish.id(), wish.purpose(), wishDelta);
		return event;
	}

	private void addWishEffect(UUID wishId, String purposeSnapshot, KrwAmount delta) {
		wishEffects.add(new LedgerWishEffect(UUID.randomUUID(), this, wishId, purposeSnapshot, delta));
	}

	@PreUpdate
	@PreRemove
	private void rejectMutation() {
		throw new UnsupportedOperationException("Ledger Events are append-only");
	}

	public UUID id() { return id; }
	public UUID accountId() { return accountId; }
	public LedgerEventType type() { return type; }
	public KrwAmount accountDelta() { return accountDelta; }
	public Instant occurredAt() { return occurredAt; }
	public UUID depositBalanceObservationId() { return depositBalanceObservationId; }
	public UUID correctionOfEventId() { return correctionOfEventId; }
	public List<LedgerWishEffect> wishEffects() { return Collections.unmodifiableList(wishEffects); }
}
