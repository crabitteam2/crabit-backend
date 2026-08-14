package com.crabit.backend.wish;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "student_block", uniqueConstraints = {
		@UniqueConstraint(name = "uk_block_direction", columnNames = {"blocker_id", "blocked_id"})
})
public class StudentBlock {

	@Id
	private UUID id;

	@Column(name = "blocker_id", nullable = false, updatable = false)
	private UUID blockerId;

	@Column(name = "blocked_id", nullable = false, updatable = false)
	private UUID blockedId;

	@Column(name = "blocked_at", nullable = false, updatable = false)
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
		releasedAt = Objects.requireNonNull(when, "when");
	}

	public boolean isCurrent() { return releasedAt == null; }
}
