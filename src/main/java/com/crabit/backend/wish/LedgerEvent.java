package com.crabit.backend.wish;

import jakarta.persistence.CascadeType;
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
						columnNames = {"id", "account_id", "event_type", "account_delta"})
		},
		indexes = @Index(
				name = "idx_ledger_event_account_occurred", columnList = "account_id,occurred_at"))
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
			UUID correctionOfEventId) {
		this.id = Objects.requireNonNull(id, "id");
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.type = Objects.requireNonNull(type, "type");
		this.accountDelta = Objects.requireNonNull(accountDelta, "accountDelta");
		this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
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
				LedgerEventType.WISH_TRANSFER, KrwAmount.zero(), eventTime, null);
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
				accountDelta, Objects.requireNonNull(occurredAt, "occurredAt"), null);
	}

	static LedgerEvent wishDeposit(
			CardBalanceAccount account, Wish wish, KrwAmount amount, Instant occurredAt) {
		return wishChange(account, wish, amount, LedgerEventType.WISH_DEPOSIT, occurredAt);
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
		return wishChange(account, wish, amount.negate(), type, occurredAt);
	}

	private static LedgerEvent wishChange(
			CardBalanceAccount account,
			Wish wish,
			KrwAmount wishDelta,
			LedgerEventType type,
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
				KrwAmount.zero(), Objects.requireNonNull(occurredAt, "occurredAt"), null);
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
	public UUID correctionOfEventId() { return correctionOfEventId; }
	public List<LedgerWishEffect> wishEffects() { return Collections.unmodifiableList(wishEffects); }
}
