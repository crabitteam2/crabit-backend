package com.crabit.backend.wish;

import jakarta.persistence.Column;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "friendship",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_friendship_academy_pair",
				columnNames = {"academy_id", "student_low_id", "student_high_id"}),
		check = @CheckConstraint(
				name = "ck_friendship_canonical_pair", constraint = "student_low_id < student_high_id"))
public class Friendship {

	@Id
	private UUID id;

	@Column(name = "academy_id", nullable = false, updatable = false)
	private UUID academyId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "academy_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_friendship_academy"))
	private Academy academy;

	@Column(name = "student_low_id", nullable = false, updatable = false)
	private UUID studentLowId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_low_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_friendship_low_student"))
	private Student studentLow;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(value = {
		@JoinColumn(name = "student_low_id", referencedColumnName = "student_id",
				insertable = false, updatable = false, nullable = false),
		@JoinColumn(name = "academy_id", referencedColumnName = "academy_id",
				insertable = false, updatable = false, nullable = false)
	}, foreignKey = @ForeignKey(name = "fk_friendship_low_membership"))
	private AcademyMembership lowMembership;

	@Column(name = "student_high_id", nullable = false, updatable = false)
	private UUID studentHighId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_high_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_friendship_high_student"))
	private Student studentHigh;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumns(value = {
		@JoinColumn(name = "student_high_id", referencedColumnName = "student_id",
				insertable = false, updatable = false, nullable = false),
		@JoinColumn(name = "academy_id", referencedColumnName = "academy_id",
				insertable = false, updatable = false, nullable = false)
	}, foreignKey = @ForeignKey(name = "fk_friendship_high_membership"))
	private AcademyMembership highMembership;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	protected Friendship() {
	}

	public Friendship(
			AcademyMembership firstMembership,
			AcademyMembership secondMembership,
			Instant startedAt) {
		Objects.requireNonNull(firstMembership, "firstMembership");
		Objects.requireNonNull(secondMembership, "secondMembership");
		if (!firstMembership.isCurrent() || !secondMembership.isCurrent()) {
			throw new IllegalStateException("Friendship requires two current academy memberships");
		}
		if (!firstMembership.academyId().equals(secondMembership.academyId())) {
			throw new IllegalArgumentException("Friendship memberships must belong to the same academy");
		}
		UUID firstStudentId = firstMembership.studentId();
		UUID secondStudentId = secondMembership.studentId();
		if (firstStudentId.equals(secondStudentId)) {
			throw new IllegalArgumentException("A student cannot be their own friend");
		}
		this.id = UUID.randomUUID();
		if (firstStudentId.toString().compareTo(secondStudentId.toString()) < 0) {
			this.studentLowId = firstStudentId;
			this.studentHighId = secondStudentId;
		} else {
			this.studentLowId = secondStudentId;
			this.studentHighId = firstStudentId;
		}
		this.academyId = firstMembership.academyId();
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
	public boolean grantsSharingAccess(UUID ownerId, UUID viewerId, UUID requestedAcademyId) {
		return isCurrent()
				&& academyId.equals(Objects.requireNonNull(requestedAcademyId, "requestedAcademyId"))
				&& !Objects.equals(ownerId, viewerId)
				&& includes(Objects.requireNonNull(ownerId, "ownerId"))
				&& includes(Objects.requireNonNull(viewerId, "viewerId"));
	}
	public UUID academyId() { return academyId; }
	public UUID studentLowId() { return studentLowId; }
	public UUID studentHighId() { return studentHighId; }
}
