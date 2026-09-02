package com.crabit.backend.relationship;

import com.crabit.backend.account.AcademyMembership;
import com.crabit.backend.account.AcademyMembershipRepository;
import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.account.StudentRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class RelationshipCommandService {

    private final CardBalanceAccountRepository accountRepository;
    private final StudentRepository studentRepository;
    private final AcademyMembershipRepository membershipRepository;
    private final StudentFollowRepository followRepository;
    private final StudentBlockRepository blockRepository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RelationshipCommandService(
            CardBalanceAccountRepository accountRepository,
            StudentRepository studentRepository,
            AcademyMembershipRepository membershipRepository,
            StudentFollowRepository followRepository,
            StudentBlockRepository blockRepository,
            JdbcTemplate jdbc,
            ObjectProvider<Clock> clock) {
        this.accountRepository = accountRepository;
        this.studentRepository = studentRepository;
        this.membershipRepository = membershipRepository;
        this.followRepository = followRepository;
        this.blockRepository = blockRepository;
        this.jdbc = jdbc;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Transactional
    public StudentFollow follow(UUID actorId, UUID academyId, UUID targetId, Instant when) {
        return followInternal(actorId, academyId, targetId, () -> when);
    }

    @Transactional
    public StudentFollow follow(UUID actorId, UUID academyId, UUID targetId) {
        return followInternal(actorId, academyId, targetId, clock::instant);
    }

    private StudentFollow followInternal(
            UUID actorId, UUID academyId, UUID targetId, Supplier<Instant> time) {
        validateTarget(actorId, academyId, targetId);
        Instant when = time.get();
        StudentFollow existing =
                followRepository.lockByAcademyAndPair(academyId, actorId, targetId).orElse(null);
        if (existing != null && existing.isCurrent()) return existing;
        if (existing == null)
            existing =
                    new StudentFollow(
                            currentMembership(actorId, academyId),
                            currentMembership(targetId, academyId),
                            when);
        else
            existing.restart(
                    when.isAfter(existing.endedAt()) ? when : existing.endedAt().plusNanos(1000));
        StudentFollow result = followRepository.saveAndFlush(existing);
        jdbc.update(
                "UPDATE student_follow SET activation = nextval('student_follow_activation_seq')"
                    + " WHERE academy_id = ? AND source_id = ? AND target_id = ?",
                academyId,
                actorId,
                targetId);
        return result;
    }

    @Transactional
    public void unfollow(UUID actorId, UUID academyId, UUID targetId, Instant when) {
        validateTarget(actorId, academyId, targetId);
        followRepository
                .lockByAcademyAndPair(academyId, actorId, targetId)
                .filter(StudentFollow::isCurrent)
                .ifPresent(
                        follow ->
                                follow.end(
                                        when.isBefore(follow.startedAt())
                                                ? follow.startedAt()
                                                : when));
    }

    private void validateTarget(UUID actorId, UUID academyId, UUID targetId) {
        if (actorId.equals(targetId))
            throw conflict(
                    RelationshipException.Code.SELF_RELATIONSHIP,
                    "A student cannot target themselves.");
        requireActorAcademy(actorId, academyId);
        if (!studentRepository.existsById(targetId))
            throw notFound(RelationshipException.Code.STUDENT_NOT_FOUND, "Student not found.");
        lockCanonicalPair(actorId, targetId);
        requireActorAcademy(actorId, academyId);
        if (membershipRepository
                        .findByStudentIdAndAcademyIdAndLeftAtIsNull(targetId, academyId)
                        .isEmpty()
                || hasCurrentBilateralBlock(actorId, targetId))
            throw notFound(RelationshipException.Code.STUDENT_NOT_FOUND, "Student not found.");
    }

    @Transactional
    public StudentBlock blockStudent(UUID actorId, UUID blockedStudentId, Instant blockedAt) {
        UUID blocked = Objects.requireNonNull(blockedStudentId, "blockedStudentId");
        if (actorId.equals(blocked)) {
            throw conflict(
                    RelationshipException.Code.SELF_RELATIONSHIP,
                    "A student cannot target themselves.");
        }
        if (!studentRepository.existsById(blocked)) {
            throw notFound(RelationshipException.Code.STUDENT_NOT_FOUND, "Student not found.");
        }
        lockCanonicalPair(actorId, blocked);
        Instant when = Objects.requireNonNull(blockedAt, "blockedAt");
        StudentBlock block =
                blockRepository
                        .lockByBlockerIdAndBlockedId(actorId, blocked)
                        .map(
                                existing -> {
                                    if (existing.isCurrent()) {
                                        throw conflict(
                                                RelationshipException.Code
                                                        .STUDENT_BLOCK_ALREADY_ACTIVE,
                                                "The student is already blocked.");
                                    }
                                    existing.blockAgain(when);
                                    return existing;
                                })
                        .orElseGet(() -> new StudentBlock(actorId, blocked, when));
        endPairRelationships(actorId, blocked, when);
        return blockRepository.save(block);
    }

    @Transactional
    public void unblockStudent(UUID actorId, UUID blockedStudentId, Instant releasedAt) {
        UUID blocked = Objects.requireNonNull(blockedStudentId, "blockedStudentId");
        if (actorId.equals(blocked) || !studentRepository.existsById(blocked)) {
            throw notFound(
                    RelationshipException.Code.STUDENT_BLOCK_NOT_FOUND, "Student block not found.");
        }
        lockCanonicalPair(actorId, blocked);
        StudentBlock block =
                blockRepository
                        .lockByBlockerIdAndBlockedId(actorId, blocked)
                        .filter(StudentBlock::isCurrent)
                        .orElseThrow(
                                () ->
                                        notFound(
                                                RelationshipException.Code.STUDENT_BLOCK_NOT_FOUND,
                                                "Student block not found."));
        block.release(releasedAt);
    }

    @Transactional
    public StudentBlock block(UUID accountId, UUID blockedStudentId, Instant blockedAt) {
        CardBalanceAccount account = lockActiveAccount(accountId);
        UUID blockedId = requireOtherStudent(account.studentId(), blockedStudentId);
        lockCanonicalPair(account.studentId(), blockedId);
        Instant when = Objects.requireNonNull(blockedAt, "blockedAt");
        StudentBlock block =
                blockRepository
                        .lockByBlockerIdAndBlockedId(account.studentId(), blockedId)
                        .map(
                                existing -> {
                                    if (!existing.isCurrent()) {
                                        existing.blockAgain(when);
                                    }
                                    return existing;
                                })
                        .orElseGet(() -> new StudentBlock(account.studentId(), blockedId, when));
        UUID low = lower(account.studentId(), blockedId);
        UUID high = low.equals(account.studentId()) ? blockedId : account.studentId();
        followRepository
                .lockAllCurrentByPair(low, high)
                .forEach(
                        follow ->
                                follow.end(
                                        when.isBefore(follow.startedAt())
                                                ? follow.startedAt()
                                                : when));
        return blockRepository.save(block);
    }

    @Transactional
    public void releaseBlock(UUID accountId, UUID blockedStudentId, Instant releasedAt) {
        CardBalanceAccount account = lockActiveAccount(accountId);
        UUID blockedId = requireOtherStudent(account.studentId(), blockedStudentId);
        lockCanonicalPair(account.studentId(), blockedId);
        StudentBlock block =
                blockRepository
                        .lockByBlockerIdAndBlockedId(account.studentId(), blockedId)
                        .orElseThrow(() -> new IllegalArgumentException("Student Block not found"));
        block.release(Objects.requireNonNull(releasedAt, "releasedAt"));
    }

    private AcademyMembership currentMembership(UUID studentId, UUID academyId) {
        return membershipRepository
                .findByStudentIdAndAcademyIdAndLeftAtIsNull(studentId, academyId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "StudentFollow requires two current academy memberships"));
    }

    private CardBalanceAccount lockActiveAccount(UUID accountId) {
        CardBalanceAccount account =
                accountRepository
                        .lockById(Objects.requireNonNull(accountId, "accountId"))
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Card Balance Account not found"));
        if (!account.isActive()) {
            throw new IllegalStateException("Card Balance Account is closed");
        }
        return account;
    }

    private void lockCanonicalPair(UUID firstStudentId, UUID secondStudentId) {
        UUID low = lower(firstStudentId, secondStudentId);
        UUID high = low.equals(firstStudentId) ? secondStudentId : firstStudentId;
        studentRepository
                .lockById(low)
                .orElseThrow(
                        () -> new IllegalArgumentException("First relationship student not found"));
        studentRepository
                .lockById(high)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Second relationship student not found"));
    }

    private boolean hasCurrentBilateralBlock(UUID firstStudentId, UUID secondStudentId) {
        return blockRepository.existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(
                        firstStudentId, secondStudentId)
                || blockRepository.existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(
                        secondStudentId, firstStudentId);
    }

    private void requireActorAcademy(UUID actorId, UUID academyId) {
        if (membershipRepository
                .findByStudentIdAndAcademyIdAndLeftAtIsNull(actorId, academyId)
                .isEmpty()) {
            throw notFound(RelationshipException.Code.ACADEMY_NOT_FOUND, "Academy not found.");
        }
    }

    private void endPairRelationships(UUID first, UUID second, Instant when) {
        UUID low = lower(first, second);
        UUID high = high(first, second);
        followRepository
                .lockAllCurrentByPair(low, high)
                .forEach(
                        follow ->
                                follow.end(
                                        when.isBefore(follow.startedAt())
                                                ? follow.startedAt()
                                                : when));
    }

    private static RelationshipException conflict(RelationshipException.Code code, String message) {
        return new RelationshipException(code, message);
    }

    private static RelationshipException notFound(RelationshipException.Code code, String message) {
        return new RelationshipException(code, message);
    }

    private static UUID requireOtherStudent(UUID ownerId, UUID otherId) {
        UUID studentId = Objects.requireNonNull(otherId, "blockedStudentId");
        if (ownerId.equals(studentId)) {
            throw new IllegalArgumentException("A student cannot block themselves");
        }
        return studentId;
    }

    private static UUID lower(UUID first, UUID second) {
        return first.toString().compareTo(second.toString()) < 0 ? first : second;
    }

    private static UUID high(UUID first, UUID second) {
        return lower(first, second).equals(first) ? second : first;
    }
}
