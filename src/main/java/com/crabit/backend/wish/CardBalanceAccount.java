package com.crabit.backend.wish;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "card_balance_account",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_card_account_id_academy", columnNames = {"id", "academy_id"}),
		indexes = @Index(name = "idx_card_account_student_academy", columnList = "student_id,academy_id"))
public class CardBalanceAccount {

	@Id
	private UUID id;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_card_account_student"))
	private Student student;

	@Column(name = "academy_id", nullable = false, updatable = false)
	private UUID academyId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "academy_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_card_account_academy"))
	private Academy academy;

	@Column(name = "opened_at", nullable = false, updatable = false)
	private Instant openedAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Version
	private long version;

	protected CardBalanceAccount() {
	}

	private CardBalanceAccount(UUID id, UUID studentId, UUID academyId, Instant openedAt) {
		this.id = Objects.requireNonNull(id, "id");
		this.studentId = Objects.requireNonNull(studentId, "studentId");
		this.academyId = Objects.requireNonNull(academyId, "academyId");
		this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
	}

	public static CardBalanceAccount open(UUID studentId, UUID academyId, Instant openedAt) {
		return new CardBalanceAccount(UUID.randomUUID(), studentId, academyId, openedAt);
	}

	public void close(Instant when) {
		if (closedAt != null) {
			throw new IllegalStateException("Card Balance Account is already closed");
		}
		closedAt = Objects.requireNonNull(when, "when");
	}

	public UUID id() { return id; }
	public UUID studentId() { return studentId; }
	public UUID academyId() { return academyId; }
	public Instant openedAt() { return openedAt; }
	public Instant closedAt() { return closedAt; }
	public boolean isActive() { return closedAt == null; }
}
