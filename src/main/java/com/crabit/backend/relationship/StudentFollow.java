package com.crabit.backend.relationship;

import com.crabit.backend.account.Academy;
import com.crabit.backend.account.AcademyMembership;
import com.crabit.backend.account.Student;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
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
@Table(
        name = "student_follow",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_student_follow_academy_pair",
                        columnNames = {"academy_id", "source_id", "target_id"}),
        check =
                @CheckConstraint(
                        name = "ck_student_follow_distinct_students",
                        constraint = "source_id <> target_id"))
public class StudentFollow {

    @Id private UUID id;

    @Column(name = "academy_id", nullable = false, updatable = false)
    private UUID academyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "academy_id",
            nullable = false,
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_student_follow_academy"))
    private Academy academy;

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "source_id",
            nullable = false,
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_student_follow_source_student"))
    private Student sourceStudent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(
            value = {
                @JoinColumn(
                        name = "source_id",
                        referencedColumnName = "student_id",
                        insertable = false,
                        updatable = false,
                        nullable = false),
                @JoinColumn(
                        name = "academy_id",
                        referencedColumnName = "academy_id",
                        insertable = false,
                        updatable = false,
                        nullable = false)
            },
            foreignKey = @ForeignKey(name = "fk_student_follow_source_membership"))
    private AcademyMembership sourceMembership;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "target_id",
            nullable = false,
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_student_follow_target_student"))
    private Student targetStudent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(
            value = {
                @JoinColumn(
                        name = "target_id",
                        referencedColumnName = "student_id",
                        insertable = false,
                        updatable = false,
                        nullable = false),
                @JoinColumn(
                        name = "academy_id",
                        referencedColumnName = "academy_id",
                        insertable = false,
                        updatable = false,
                        nullable = false)
            },
            foreignKey = @ForeignKey(name = "fk_student_follow_target_membership"))
    private AcademyMembership targetMembership;

    @Column(name = "activation", nullable = false, insertable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private long activation;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected StudentFollow() {}

    public StudentFollow(
            AcademyMembership firstMembership,
            AcademyMembership secondMembership,
            Instant startedAt) {
        Objects.requireNonNull(firstMembership, "firstMembership");
        Objects.requireNonNull(secondMembership, "secondMembership");
        if (!firstMembership.isCurrent() || !secondMembership.isCurrent()) {
            throw new IllegalStateException(
                    "StudentFollow requires two current academy memberships");
        }
        if (!firstMembership.academyId().equals(secondMembership.academyId())) {
            throw new IllegalArgumentException(
                    "StudentFollow memberships must belong to the same academy");
        }
        UUID firstStudentId = firstMembership.studentId();
        UUID secondStudentId = secondMembership.studentId();
        if (firstStudentId.equals(secondStudentId)) {
            throw new IllegalArgumentException("A student cannot be their own follow target");
        }
        this.id = UUID.randomUUID();
        this.sourceId = firstStudentId;
        this.targetId = secondStudentId;
        this.academyId = firstMembership.academyId();
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    public void end(Instant when) {
        if (endedAt != null) {
            throw new IllegalStateException("StudentFollow has already ended");
        }
        endedAt = Objects.requireNonNull(when, "when");
    }

    void restart(Instant when) {
        if (endedAt == null) {
            throw new IllegalStateException("StudentFollow is already current");
        }
        Instant restartedAt = Objects.requireNonNull(when, "when");
        if (restartedAt.isBefore(endedAt)) {
            throw new IllegalArgumentException("Restarted student_follow cannot precede its end");
        }
        startedAt = restartedAt;
        endedAt = null;
    }

    public boolean isCurrent() {
        return endedAt == null;
    }

    public boolean includes(UUID studentId) {
        return sourceId.equals(studentId) || targetId.equals(studentId);
    }

    boolean matches(UUID ownerId, UUID viewerId, UUID requestedAcademyId) {
        return isCurrent()
                && academyId.equals(
                        Objects.requireNonNull(requestedAcademyId, "requestedAcademyId"))
                && !Objects.equals(ownerId, viewerId)
                && targetId.equals(Objects.requireNonNull(ownerId, "ownerId"))
                && sourceId.equals(Objects.requireNonNull(viewerId, "viewerId"));
    }

    public UUID academyId() {
        return academyId;
    }

    public UUID sourceId() {
        return sourceId;
    }

    public UUID targetId() {
        return targetId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }
}
