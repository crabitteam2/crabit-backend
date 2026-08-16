package com.crabit.backend.wish;

import com.crabit.backend.account.Academy;
import com.crabit.backend.account.CardBalanceAccount;

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
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Entity
@Table(name = "wish",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_wish_id_account", columnNames = {"id", "account_id"}),
		indexes = {
				@Index(name = "idx_wish_account_state", columnList = "account_id,state"),
				@Index(name = "idx_wish_active_lookup", columnList = "account_id,deleted_at,state")
		},
		check = {
				@CheckConstraint(name = "ck_wish_target_positive", constraint = "target_amount > 0"),
				@CheckConstraint(name = "ck_wish_amount_bounds",
						constraint = "wish_amount >= 0 AND wish_amount <= target_amount"),
				@CheckConstraint(name = "ck_wish_state_amount",
						constraint = "deleted_at IS NOT NULL OR CASE CAST(state AS VARCHAR) WHEN 'IN_PROGRESS' THEN wish_amount < target_amount WHEN 'AMOUNT_REACHED' THEN wish_amount = target_amount WHEN 'COMPLETED' THEN wish_amount = 0 WHEN 'ABANDONED' THEN wish_amount = 0 ELSE FALSE END"),
				@CheckConstraint(name = "ck_wish_tombstone_pair",
						constraint = "(deleted_at IS NULL AND deleted_purpose_snapshot IS NULL) OR (deleted_at IS NOT NULL AND deleted_purpose_snapshot IS NOT NULL)"),
				@CheckConstraint(name = "ck_wish_deleted_amount",
						constraint = "deleted_at IS NULL OR wish_amount = 0"),
				@CheckConstraint(name = "ck_wish_completion_time",
						constraint = "(CAST(state AS VARCHAR) = 'COMPLETED' AND completed_at IS NOT NULL AND completed_at >= created_at) OR (CAST(state AS VARCHAR) <> 'COMPLETED' AND completed_at IS NULL)")
			})
public class Wish {

	@Id
	private UUID id;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@Column(name = "academy_id", nullable = false, updatable = false)
	private UUID academyId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(value = {
			@JoinColumn(name = "account_id", referencedColumnName = "id",
					insertable = false, updatable = false, nullable = false),
			@JoinColumn(name = "academy_id", referencedColumnName = "academy_id",
					insertable = false, updatable = false, nullable = false)
	}, foreignKey = @ForeignKey(name = "fk_wish_account_academy"))
	private CardBalanceAccount account;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "academy_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_wish_academy"))
	private Academy academy;

	@Column(name = "purpose", nullable = false, length = 200)
	private String purpose;

	@Column(name = "target_amount", nullable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount targetAmount;

	@Column(name = "wish_amount", nullable = false)
	@Convert(converter = KrwAmountConverter.class)
	private KrwAmount amount;

	@Enumerated(EnumType.STRING)
	@Column(name = "state", nullable = false, length = 32, columnDefinition = "varchar(32)")
	private WishState state;

	@Enumerated(EnumType.STRING)
	@Column(name = "visibility", nullable = false, length = 32, columnDefinition = "varchar(32)")
	private WishVisibility visibility;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false,
			columnDefinition = "timestamp with time zone default current_timestamp")
	private Instant updatedAt;

	@Column(name = "target_date")
	private LocalDate targetDate;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_purpose_snapshot", length = 200)
	private String deletedPurposeSnapshot;

	@Version
	private long version;

	protected Wish() {
	}

	private Wish(
			UUID id,
			UUID accountId,
			UUID academyId,
			String purpose,
			KrwAmount targetAmount,
			KrwAmount amount,
			WishState state,
			WishVisibility visibility,
			LocalDate targetDate,
			Instant createdAt,
			Instant updatedAt,
			Instant completedAt,
			Instant deletedAt,
			String deletedPurposeSnapshot) {
		this.id = Objects.requireNonNull(id, "id");
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.academyId = Objects.requireNonNull(academyId, "academyId");
		this.purpose = normalizePurpose(purpose);
		this.targetAmount = Objects.requireNonNull(targetAmount, "targetAmount");
		this.amount = Objects.requireNonNull(amount, "amount");
		this.state = Objects.requireNonNull(state, "state");
		this.visibility = Objects.requireNonNull(visibility, "visibility");
		this.targetDate = targetDate;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
		this.completedAt = completedAt;
		this.deletedAt = deletedAt;
		this.deletedPurposeSnapshot = deletedPurposeSnapshot;
		validateStateAndAmounts();
		validateCompletionTime();
		validateTombstone();
		validateTerminalVisibility();
	}

	public static Wish create(
			UUID accountId, UUID academyId, String purpose, KrwAmount targetAmount, Instant createdAt) {
		return create(accountId, academyId, purpose, targetAmount, null, createdAt);
	}

	public static Wish create(
			UUID accountId,
			UUID academyId,
			String purpose,
			KrwAmount targetAmount,
			LocalDate targetDate,
			Instant createdAt) {
		return new Wish(UUID.randomUUID(), accountId, academyId, purpose, targetAmount,
				KrwAmount.zero(), WishState.IN_PROGRESS, WishVisibility.PRIVATE,
				targetDate, createdAt, createdAt, null, null, null);
	}

	public static Wish reconstitute(
			UUID id,
			UUID accountId,
			UUID academyId,
			String purpose,
			KrwAmount targetAmount,
			KrwAmount amount,
			WishState state,
			WishVisibility visibility,
			LocalDate targetDate,
			Instant createdAt,
			Instant completedAt,
			Instant deletedAt,
			String deletedPurposeSnapshot) {
		return new Wish(id, accountId, academyId, purpose, targetAmount, amount, state,
				visibility, targetDate, createdAt, createdAt, completedAt, deletedAt,
				deletedPurposeSnapshot);
	}

	public static Wish reconstitute(
			UUID id,
			UUID accountId,
			UUID academyId,
			String purpose,
			KrwAmount targetAmount,
			KrwAmount amount,
			WishState state,
			WishVisibility visibility,
			LocalDate targetDate,
			Instant createdAt,
			Instant updatedAt,
			Instant completedAt,
			Instant deletedAt,
			String deletedPurposeSnapshot) {
		return new Wish(id, accountId, academyId, purpose, targetAmount, amount, state,
				visibility, targetDate, createdAt, updatedAt, completedAt, deletedAt,
				deletedPurposeSnapshot);
	}

	void allocate(KrwAmount allocation) {
		requireActive();
		requirePositive(allocation, "allocation");
		KrwAmount candidate = amount.plus(allocation);
		if (candidate.compareTo(targetAmount) > 0) {
			throw new IllegalArgumentException("allocation exceeds Wish target");
		}
		amount = candidate;
		recalculateActiveState();
	}

	void withdraw(KrwAmount withdrawal) {
		requireActive();
		requirePositive(withdrawal, "withdrawal");
		if (withdrawal.compareTo(amount) > 0) {
			throw new IllegalArgumentException("withdrawal exceeds Wish amount");
		}
		amount = amount.minus(withdrawal);
		recalculateActiveState();
	}

	void changePurpose(String newPurpose) {
		requireActive();
		purpose = normalizePurpose(newPurpose);
	}

	void changeTarget(KrwAmount newTarget) {
		requireActive();
		if (!Objects.requireNonNull(newTarget, "newTarget").isPositive()) {
			throw new IllegalArgumentException("Wish target must be positive");
		}
		if (newTarget.compareTo(amount) < 0) {
			throw new IllegalArgumentException("Wish target cannot be below current amount");
		}
		targetAmount = newTarget;
		recalculateActiveState();
	}

	void changeTargetDate(LocalDate newTargetDate) {
		requireActive();
		targetDate = newTargetDate;
	}

	KrwAmount complete(Instant when) {
		requireNotDeleted();
		if (state != WishState.AMOUNT_REACHED) {
			throw new IllegalStateException("Only an amount-reached Wish can be completed");
		}
		Instant completionTime = Objects.requireNonNull(when, "when");
		if (completionTime.isBefore(createdAt)) {
			throw new IllegalArgumentException("Wish completion cannot precede creation");
		}
		KrwAmount returned = amount;
		amount = KrwAmount.zero();
		state = WishState.COMPLETED;
		completedAt = completionTime;
		return returned;
	}

	KrwAmount abandon() {
		requireActive();
		KrwAmount returned = amount;
		amount = KrwAmount.zero();
		state = WishState.ABANDONED;
		visibility = WishVisibility.PRIVATE;
		return returned;
	}

	KrwAmount tombstone(Instant when) {
		requireNotDeleted();
		Instant deletionTime = Objects.requireNonNull(when, "when");
		KrwAmount returned = amount;
		amount = KrwAmount.zero();
		deletedPurposeSnapshot = purpose;
		deletedAt = deletionTime;
		return returned;
	}

	void changeVisibility(WishVisibility newVisibility) {
		requireNotDeleted();
		if (state == WishState.ABANDONED) {
			throw new IllegalStateException("Abandoned Wish visibility is immutable");
		}
		visibility = Objects.requireNonNull(newVisibility, "newVisibility");
	}

	void touch(Instant when) {
		Instant changedAt = Objects.requireNonNull(when, "when");
		if (changedAt.isBefore(createdAt)) {
			throw new IllegalArgumentException("Wish update cannot precede creation");
		}
		updatedAt = changedAt;
	}

	void validateTransferOut(KrwAmount transferAmount) {
		requireActive();
		requirePositive(transferAmount, "transfer amount");
		if (transferAmount.compareTo(amount) > 0) {
			throw new IllegalArgumentException("Transfer exceeds source Wish amount");
		}
	}

	void validateTransferIn(KrwAmount transferAmount) {
		requireActive();
		requirePositive(transferAmount, "transfer amount");
		if (amount.plus(transferAmount).compareTo(targetAmount) > 0) {
			throw new IllegalArgumentException("Transfer exceeds destination Wish target");
		}
	}

	void applyValidatedTransferOut(KrwAmount transferAmount) {
		amount = amount.minus(transferAmount);
		recalculateActiveState();
	}

	void applyValidatedTransferIn(KrwAmount transferAmount) {
		amount = amount.plus(transferAmount);
		recalculateActiveState();
	}

	private void validateStateAndAmounts() {
		if (!targetAmount.isPositive()) {
			throw new IllegalArgumentException("Wish target must be positive");
		}
		if (amount.isNegative() || amount.compareTo(targetAmount) > 0) {
			throw new IllegalArgumentException("Wish amount must be between zero and target");
		}
		if (deletedAt != null) {
			if (!amount.isZero()) {
				throw new IllegalArgumentException("Deleted Wish must have zero amount");
			}
			return;
		}
		boolean valid = switch (state) {
			case IN_PROGRESS -> amount.compareTo(targetAmount) < 0;
			case AMOUNT_REACHED -> amount.equals(targetAmount);
			case COMPLETED, ABANDONED -> amount.isZero();
		};
		if (!valid) {
			throw new IllegalArgumentException("Wish state and amount are inconsistent");
		}
	}

	private void validateTombstone() {
		if ((deletedAt == null) != (deletedPurposeSnapshot == null)) {
			throw new IllegalArgumentException("Wish tombstone requires both deletion time and purpose snapshot");
		}
	}

	private void validateCompletionTime() {
		if ((state == WishState.COMPLETED) != (completedAt != null)) {
			throw new IllegalArgumentException("Only a completed Wish has a completion time");
		}
		if (completedAt != null && completedAt.isBefore(createdAt)) {
			throw new IllegalArgumentException("Wish completion cannot precede creation");
		}
	}

	private void validateTerminalVisibility() {
		if (state == WishState.ABANDONED && visibility != WishVisibility.PRIVATE) {
			throw new IllegalArgumentException("Abandoned Wish must be private");
		}
	}

	private void recalculateActiveState() {
		state = amount.equals(targetAmount) ? WishState.AMOUNT_REACHED : WishState.IN_PROGRESS;
	}

	private void requireActive() {
		requireNotDeleted();
		if (!state.isActive()) {
			throw new IllegalStateException("Terminal Wish cannot be changed");
		}
	}

	private void requireNotDeleted() {
		if (deletedAt != null) {
			throw new IllegalStateException("Deleted Wish cannot be changed");
		}
	}

	private static void requirePositive(KrwAmount amount, String name) {
		if (!Objects.requireNonNull(amount, name).isPositive()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}

	public static String normalizePurpose(String purpose) {
		if (purpose == null) {
			throw new IllegalArgumentException("Wish purpose must not be blank");
		}
		if (purpose.codePoints().anyMatch(Wish::isForbiddenPurposeCharacter)) {
			throw new IllegalArgumentException("Wish purpose must be control-free and newline-free");
		}
		String normalized = Normalizer.normalize(
				removeBoundarySpaceSeparators(purpose), Normalizer.Form.NFC);
		int characterCount = normalized.codePointCount(0, normalized.length());
		if (characterCount < 1 || characterCount > 200) {
			throw new IllegalArgumentException("Wish purpose must contain 1 to 200 Unicode characters");
		}
		return normalized;
	}

	private static String removeBoundarySpaceSeparators(String purpose) {
		int start = 0;
		int end = purpose.length();
		while (start < end) {
			int codePoint = purpose.codePointAt(start);
			if (Character.getType(codePoint) != Character.SPACE_SEPARATOR) {
				break;
			}
			start += Character.charCount(codePoint);
		}
		while (end > start) {
			int codePoint = purpose.codePointBefore(end);
			if (Character.getType(codePoint) != Character.SPACE_SEPARATOR) {
				break;
			}
			end -= Character.charCount(codePoint);
		}
		return purpose.substring(start, end);
	}

	private static boolean isForbiddenPurposeCharacter(int codePoint) {
		int type = Character.getType(codePoint);
		return type == Character.CONTROL
				|| type == Character.FORMAT
				|| type == Character.LINE_SEPARATOR
				|| type == Character.PARAGRAPH_SEPARATOR;
	}

	public UUID id() { return id; }
	public UUID accountId() { return accountId; }
	public UUID academyId() { return academyId; }
	public String purpose() { return purpose; }
	public String purposeSnapshot() { return deletedPurposeSnapshot; }
	public String displayPurpose() { return isDeleted() ? "삭제된 위시" : purpose; }
	public KrwAmount targetAmount() { return targetAmount; }
	public KrwAmount amount() { return amount; }
	public WishState state() { return state; }
	public WishVisibility visibility() { return visibility; }
	public LocalDate targetDate() { return targetDate; }
	public Instant createdAt() { return createdAt; }
	public Instant updatedAt() { return updatedAt; }
	public Instant completedAt() { return completedAt; }
	public Optional<Duration> actualDuration() {
		return Optional.ofNullable(completedAt).map(value -> Duration.between(createdAt, value));
	}
	public Instant deletedAt() { return deletedAt; }
	public long version() { return version; }
	public boolean isDeleted() { return deletedAt != null; }
	public boolean isActive() { return !isDeleted() && state.isActive(); }
	public boolean isTerminal() { return state.isTerminal(); }
}
