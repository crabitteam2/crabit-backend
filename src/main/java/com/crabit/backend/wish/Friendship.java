package com.crabit.backend.wish;

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
@Table(name = "friendship",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_friendship_canonical_pair", columnNames = {"student_low_id", "student_high_id"}),
		check = @CheckConstraint(
				name = "ck_friendship_canonical_pair", constraint = "student_low_id < student_high_id"))
public class Friendship {

	@Id
	private UUID id;

	@Column(name = "student_low_id", nullable = false, updatable = false)
	private UUID studentLowId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_low_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_friendship_low_student"))
	private Student studentLow;

	@Column(name = "student_high_id", nullable = false, updatable = false)
	private UUID studentHighId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_high_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_friendship_high_student"))
	private Student studentHigh;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	protected Friendship() {
	}

	public Friendship(UUID firstStudentId, UUID secondStudentId, Instant startedAt) {
		Objects.requireNonNull(firstStudentId, "firstStudentId");
		Objects.requireNonNull(secondStudentId, "secondStudentId");
		if (firstStudentId.equals(secondStudentId)) {
			throw new IllegalArgumentException("A student cannot be their own friend");
		}
		this.id = UUID.randomUUID();
		if (firstStudentId.compareTo(secondStudentId) < 0) {
			this.studentLowId = firstStudentId;
			this.studentHighId = secondStudentId;
		} else {
			this.studentLowId = secondStudentId;
			this.studentHighId = firstStudentId;
		}
		this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
	}

	public void end(Instant when) {
		if (endedAt != null) {
			throw new IllegalStateException("Friendship has already ended");
		}
		endedAt = Objects.requireNonNull(when, "when");
	}

	public boolean isCurrent() { return endedAt == null; }
	public boolean includes(UUID studentId) {
		return studentLowId.equals(studentId) || studentHighId.equals(studentId);
	}
}
