package com.crabit.backend.wish;

import jakarta.persistence.Column;
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
@Table(name = "academy_membership", uniqueConstraints = {
		@UniqueConstraint(name = "uk_membership_student_academy", columnNames = {"student_id", "academy_id"})
})
public class AcademyMembership {

	@Id
	private UUID id;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_membership_student"))
	private Student student;

	@Column(name = "academy_id", nullable = false, updatable = false)
	private UUID academyId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "academy_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_membership_academy"))
	private Academy academy;

	@Column(name = "joined_at", nullable = false, updatable = false)
	private Instant joinedAt;

	@Column(name = "left_at")
	private Instant leftAt;

	protected AcademyMembership() {
	}

	public AcademyMembership(UUID studentId, UUID academyId, Instant joinedAt) {
		this.id = UUID.randomUUID();
		this.studentId = Objects.requireNonNull(studentId, "studentId");
		this.academyId = Objects.requireNonNull(academyId, "academyId");
		this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
	}

	public void leave(Instant when) {
		if (leftAt != null) {
			throw new IllegalStateException("Academy membership has already ended");
		}
		leftAt = Objects.requireNonNull(when, "when");
	}

	public UUID studentId() { return studentId; }
	public UUID academyId() { return academyId; }
	public Instant joinedAt() { return joinedAt; }
	public Instant leftAt() { return leftAt; }
	public boolean isCurrent() { return leftAt == null; }
}
