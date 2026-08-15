package com.crabit.backend.relationship;

import com.crabit.backend.account.AcademyMembership;
import com.crabit.backend.account.AcademyMembershipRepository;
import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.account.Student;
import com.crabit.backend.account.StudentRepository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RelationshipCommandService {

	private final CardBalanceAccountRepository accountRepository;
	private final StudentRepository studentRepository;
	private final AcademyMembershipRepository membershipRepository;
	private final FriendshipRepository friendshipRepository;
	private final StudentBlockRepository blockRepository;

	public RelationshipCommandService(
			CardBalanceAccountRepository accountRepository,
			StudentRepository studentRepository,
			AcademyMembershipRepository membershipRepository,
			FriendshipRepository friendshipRepository,
			StudentBlockRepository blockRepository) {
		this.accountRepository = accountRepository;
		this.studentRepository = studentRepository;
		this.membershipRepository = membershipRepository;
		this.friendshipRepository = friendshipRepository;
		this.blockRepository = blockRepository;
	}

	@Transactional
	public StudentBlock block(UUID accountId, UUID blockedStudentId, Instant blockedAt) {
		CardBalanceAccount account = lockActiveAccount(accountId);
		UUID blockedId = requireOtherStudent(account.studentId(), blockedStudentId);
		lockCanonicalPair(account.studentId(), blockedId);
		Instant when = Objects.requireNonNull(blockedAt, "blockedAt");
		StudentBlock block = blockRepository
				.lockByBlockerIdAndBlockedId(account.studentId(), blockedId)
				.map(existing -> {
					if (!existing.isCurrent()) {
						existing.blockAgain(when);
					}
					return existing;
				})
				.orElseGet(() -> new StudentBlock(account.studentId(), blockedId, when));
		UUID low = lower(account.studentId(), blockedId);
		UUID high = low.equals(account.studentId()) ? blockedId : account.studentId();
		friendshipRepository.lockAllCurrentByPair(low, high)
				.forEach(friendship -> friendship.end(when));
		return blockRepository.save(block);
	}

	@Transactional
	public void releaseBlock(UUID accountId, UUID blockedStudentId, Instant releasedAt) {
		CardBalanceAccount account = lockActiveAccount(accountId);
		UUID blockedId = requireOtherStudent(account.studentId(), blockedStudentId);
		lockCanonicalPair(account.studentId(), blockedId);
		StudentBlock block = blockRepository
				.lockByBlockerIdAndBlockedId(account.studentId(), blockedId)
				.orElseThrow(() -> new IllegalArgumentException("Student Block not found"));
		block.release(Objects.requireNonNull(releasedAt, "releasedAt"));
	}

	@Transactional
	public Friendship befriend(UUID accountId, UUID friendStudentId, Instant startedAt) {
		CardBalanceAccount account = lockActiveAccount(accountId);
		UUID friendId = requireOtherStudent(account.studentId(), friendStudentId);
		lockCanonicalPair(account.studentId(), friendId);
		Instant when = Objects.requireNonNull(startedAt, "startedAt");
		if (hasCurrentBilateralBlock(account.studentId(), friendId)) {
			throw new IllegalStateException("A current global block prevents friendship");
		}
		AcademyMembership ownerMembership = currentMembership(
				account.studentId(), account.academyId());
		AcademyMembership friendMembership = currentMembership(friendId, account.academyId());
		UUID low = lower(account.studentId(), friendId);
		UUID high = low.equals(account.studentId()) ? friendId : account.studentId();
		Friendship friendship = friendshipRepository
				.lockByAcademyAndPair(account.academyId(), low, high)
				.map(existing -> {
					if (existing.isCurrent()) {
						throw new IllegalStateException("Friendship is already current");
					}
					existing.restart(when);
					return existing;
				})
				.orElseGet(() -> new Friendship(ownerMembership, friendMembership, when));
		return friendshipRepository.save(friendship);
	}

	private AcademyMembership currentMembership(UUID studentId, UUID academyId) {
		return membershipRepository.findByStudentIdAndAcademyIdAndLeftAtIsNull(studentId, academyId)
				.orElseThrow(() -> new IllegalStateException(
						"Friendship requires two current academy memberships"));
	}

	private CardBalanceAccount lockActiveAccount(UUID accountId) {
		CardBalanceAccount account = accountRepository.lockById(
				Objects.requireNonNull(accountId, "accountId"))
				.orElseThrow(() -> new IllegalArgumentException("Card Balance Account not found"));
		if (!account.isActive()) {
			throw new IllegalStateException("Card Balance Account is closed");
		}
		return account;
	}

	private void lockCanonicalPair(UUID firstStudentId, UUID secondStudentId) {
		UUID low = lower(firstStudentId, secondStudentId);
		UUID high = low.equals(firstStudentId) ? secondStudentId : firstStudentId;
		studentRepository.lockById(low)
				.orElseThrow(() -> new IllegalArgumentException("First relationship student not found"));
		studentRepository.lockById(high)
				.orElseThrow(() -> new IllegalArgumentException("Second relationship student not found"));
	}

	private boolean hasCurrentBilateralBlock(UUID firstStudentId, UUID secondStudentId) {
		return blockRepository.existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(
				firstStudentId, secondStudentId)
				|| blockRepository.existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(
						secondStudentId, firstStudentId);
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
}
