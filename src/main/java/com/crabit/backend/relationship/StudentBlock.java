package com.crabit.backend.relationship;

import com.crabit.backend.account.Student;

import jakarta.persistence.Column;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "student_block",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_block_direction", columnNames = {"blocker_id", "blocked_id"}),
		check = @CheckConstraint(
				name = "ck_block_distinct_students", constraint = "blocker_id <> blocked_id"))
public class StudentBlock {

	@Id
	private UUID id;

	@Column(name = "blocker_id", nullable = false, updatable = false)
	private UUID blockerId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "blocker_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_block_blocker"))
	private Student blocker;

	@Column(name = "blocked_id", nullable = false, updatable = false)
	private UUID blockedId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "blocked_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_block_blocked"))
	private Student blocked;

	@Column(name = "blocked_at", nullable = false)
	private Instant blockedAt;

	@Column(name = "released_at")
	private Instant releasedAt;

	protected StudentBlock() {
	}

	public StudentBlock(UUID blockerId, UUID blockedId, Instant blockedAt) {
		this.blockerId = Objects.requireNonNull(blockerId, "blockerId");
		this.blockedId = Objects.requireNonNull(blockedId, "blockedId");
		if (blockerId.equals(blockedId)) {
			throw new IllegalArgumentException("A student cannot block themselves");
		}
		this.id = UUID.randomUUID();
		this.blockedAt = Objects.requireNonNull(blockedAt, "blockedAt");
	}

	public void release(Instant when) {
		if (releasedAt != null) {
			throw new IllegalStateException("Block has already been released");
		}
		Instant releaseTime = Objects.requireNonNull(when, "when");
		if (releaseTime.isBefore(blockedAt)) {
			throw new IllegalArgumentException("A block release cannot precede its start");
		}
		releasedAt = releaseTime;
	}

	void blockAgain(Instant when) {
		if (releasedAt == null) {
			throw new IllegalStateException("Block is already current");
		}
		Instant nextBlockedAt = Objects.requireNonNull(when, "when");
		if (nextBlockedAt.isBefore(releasedAt)) {
			throw new IllegalArgumentException("A repeated block cannot precede its release");
		}
		blockedAt = nextBlockedAt;
		releasedAt = null;
	}

	public boolean isCurrent() { return releasedAt == null; }
	public UUID blockerId() { return blockerId; }
	public UUID blockedId() { return blockedId; }
	public Instant blockedAt() { return blockedAt; }
	public Instant releasedAt() { return releasedAt; }
}
