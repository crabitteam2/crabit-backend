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
	private final FriendRequestRepository requestRepository;

	public RelationshipCommandService(
			CardBalanceAccountRepository accountRepository,
			StudentRepository studentRepository,
			AcademyMembershipRepository membershipRepository,
			FriendshipRepository friendshipRepository,
			StudentBlockRepository blockRepository,
			FriendRequestRepository requestRepository) {
		this.accountRepository = accountRepository;
		this.studentRepository = studentRepository;
		this.membershipRepository = membershipRepository;
		this.friendshipRepository = friendshipRepository;
		this.blockRepository = blockRepository;
		this.requestRepository = requestRepository;
	}

	@Transactional
	public FriendRequest sendFriendRequest(
			UUID actorId, UUID academyId, UUID receiverId, Instant createdAt) {
		UUID actor = Objects.requireNonNull(actorId, "actorId");
		UUID receiver = Objects.requireNonNull(receiverId, "receiverId");
		if (actor.equals(receiver)) {
			throw conflict(RelationshipException.Code.SELF_RELATIONSHIP,
					"A student cannot target themselves.");
		}
		requireActorAcademy(actor, academyId);
		if (!studentRepository.existsById(receiver)) {
			throw notFound(RelationshipException.Code.STUDENT_NOT_FOUND, "Student not found.");
		}
		lockCanonicalPair(actor, receiver);
		if (membershipRepository.findByStudentIdAndAcademyIdAndLeftAtIsNull(receiver, academyId).isEmpty()
				|| hasCurrentBilateralBlock(actor, receiver)) {
			throw notFound(RelationshipException.Code.STUDENT_NOT_FOUND, "Student not found.");
		}
		UUID low = lower(actor, receiver);
		UUID high = high(actor, receiver);
		if (friendshipRepository.existsByAcademyIdAndStudentLowIdAndStudentHighIdAndEndedAtIsNull(
				academyId, low, high)) {
			throw conflict(RelationshipException.Code.ALREADY_FRIENDS,
					"The students are already friends.");
		}
		requestRepository.lockPendingByAcademyAndPair(academyId, low, high).ifPresent(existing -> {
			RelationshipException.Code code = existing.senderId().equals(actor)
					? RelationshipException.Code.FRIEND_REQUEST_ALREADY_PENDING
					: RelationshipException.Code.INCOMING_FRIEND_REQUEST_PENDING;
			throw conflict(code, code == RelationshipException.Code.FRIEND_REQUEST_ALREADY_PENDING
					? "A sent friend request is already pending."
					: "An incoming friend request is already pending.");
		});
		return requestRepository.save(new FriendRequest(academyId, actor, receiver, createdAt));
	}

	@Transactional
	public FriendRequest cancelFriendRequest(
			UUID actorId, UUID academyId, UUID requestId, Instant processedAt) {
		requireActorAcademy(actorId, academyId);
		FriendRequestRepository.Identity identity = ownedRequestIdentity(
				requestId, academyId, actorId, true);
		lockCanonicalPair(identity.getSenderId(), identity.getReceiverId());
		FriendRequest request = ownedRequest(requestId, academyId, actorId, true);
		if (!request.isPending()) {
			throw conflict(RelationshipException.Code.FRIEND_REQUEST_NOT_PENDING,
					"Friend request is no longer pending.");
		}
		request.cancel(processedAt);
		return request;
	}

	@Transactional
	public FriendRequest rejectFriendRequest(
			UUID actorId, UUID academyId, UUID requestId, Instant processedAt) {
		requireActorAcademy(actorId, academyId);
		FriendRequestRepository.Identity identity = ownedRequestIdentity(
				requestId, academyId, actorId, false);
		lockCanonicalPair(identity.getSenderId(), identity.getReceiverId());
		FriendRequest request = ownedRequest(requestId, academyId, actorId, false);
		if (!request.isPending()) {
			throw conflict(RelationshipException.Code.FRIEND_REQUEST_NOT_PENDING,
					"Friend request is no longer pending.");
		}
		request.reject(processedAt);
		return request;
	}

	@Transactional
	public Friendship acceptFriendRequest(
			UUID actorId, UUID academyId, UUID requestId, Instant processedAt) {
		requireActorAcademy(actorId, academyId);
		FriendRequestRepository.Identity identity = ownedRequestIdentity(
				requestId, academyId, actorId, false);
		lockCanonicalPair(identity.getSenderId(), identity.getReceiverId());
		FriendRequest request = ownedRequest(requestId, academyId, actorId, false);
		if (!request.isPending()) {
			throw conflict(RelationshipException.Code.FRIEND_REQUEST_NOT_PENDING,
					"Friend request is no longer pending.");
		}
		AcademyMembership senderMembership = membershipRepository
				.findByStudentIdAndAcademyIdAndLeftAtIsNull(request.senderId(), academyId)
				.orElseThrow(() -> conflict(RelationshipException.Code.FRIEND_REQUEST_NOT_ACTIONABLE,
						"Friend request can no longer be accepted."));
		AcademyMembership receiverMembership = membershipRepository
				.findByStudentIdAndAcademyIdAndLeftAtIsNull(request.receiverId(), academyId)
				.orElseThrow(() -> conflict(RelationshipException.Code.FRIEND_REQUEST_NOT_ACTIONABLE,
						"Friend request can no longer be accepted."));
		if (hasCurrentBilateralBlock(request.senderId(), request.receiverId())) {
			throw conflict(RelationshipException.Code.FRIEND_REQUEST_NOT_ACTIONABLE,
					"Friend request can no longer be accepted.");
		}
		UUID low = request.studentLowId();
		UUID high = request.studentHighId();
		Friendship friendship = friendshipRepository.lockByAcademyAndPair(academyId, low, high)
				.map(existing -> {
					if (existing.isCurrent()) {
						throw conflict(RelationshipException.Code.ALREADY_FRIENDS,
								"The students are already friends.");
					}
					existing.restart(processedAt);
					return existing;
				})
				.orElseGet(() -> new Friendship(senderMembership, receiverMembership, processedAt));
		request.accept(processedAt);
		return friendshipRepository.save(friendship);
	}

	@Transactional
	public void unfriend(UUID actorId, UUID academyId, UUID friendId, Instant endedAt) {
		requireActorAcademy(actorId, academyId);
		UUID friend = Objects.requireNonNull(friendId, "friendId");
		if (actorId.equals(friend) || !studentRepository.existsById(friend)) {
			throw notFound(RelationshipException.Code.FRIENDSHIP_NOT_FOUND, "Friendship not found.");
		}
		lockCanonicalPair(actorId, friend);
		UUID low = lower(actorId, friend);
		UUID high = high(actorId, friend);
		Friendship friendship = friendshipRepository
				.lockCurrentByAcademyAndPair(academyId, low, high)
				.orElseThrow(() -> notFound(RelationshipException.Code.FRIENDSHIP_NOT_FOUND,
						"Friendship not found."));
		friendship.end(endedAt);
	}

	@Transactional
	public StudentBlock blockStudent(UUID actorId, UUID blockedStudentId, Instant blockedAt) {
		UUID blocked = Objects.requireNonNull(blockedStudentId, "blockedStudentId");
		if (actorId.equals(blocked)) {
			throw conflict(RelationshipException.Code.SELF_RELATIONSHIP,
					"A student cannot target themselves.");
		}
		if (!studentRepository.existsById(blocked)) {
			throw notFound(RelationshipException.Code.STUDENT_NOT_FOUND, "Student not found.");
		}
		lockCanonicalPair(actorId, blocked);
		Instant when = Objects.requireNonNull(blockedAt, "blockedAt");
		StudentBlock block = blockRepository.lockByBlockerIdAndBlockedId(actorId, blocked)
				.map(existing -> {
					if (existing.isCurrent()) {
						throw conflict(RelationshipException.Code.STUDENT_BLOCK_ALREADY_ACTIVE,
								"The student is already blocked.");
					}
					existing.blockAgain(when);
					return existing;
				})
				.orElseGet(() -> new StudentBlock(actorId, blocked, when));
		endPairRelationshipsAndRequests(actorId, blocked, when);
		return blockRepository.save(block);
	}

	@Transactional
	public void unblockStudent(UUID actorId, UUID blockedStudentId, Instant releasedAt) {
		UUID blocked = Objects.requireNonNull(blockedStudentId, "blockedStudentId");
		if (actorId.equals(blocked) || !studentRepository.existsById(blocked)) {
			throw notFound(RelationshipException.Code.STUDENT_BLOCK_NOT_FOUND,
					"Student block not found.");
		}
		lockCanonicalPair(actorId, blocked);
		StudentBlock block = blockRepository.lockByBlockerIdAndBlockedId(actorId, blocked)
				.filter(StudentBlock::isCurrent)
				.orElseThrow(() -> notFound(RelationshipException.Code.STUDENT_BLOCK_NOT_FOUND,
						"Student block not found."));
		block.release(releasedAt);
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
		requestRepository.lockAllPendingByPair(low, high)
				.forEach(request -> request.cancel(when));
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

	private void requireActorAcademy(UUID actorId, UUID academyId) {
		if (membershipRepository.findByStudentIdAndAcademyIdAndLeftAtIsNull(actorId, academyId).isEmpty()) {
			throw notFound(RelationshipException.Code.ACADEMY_NOT_FOUND, "Academy not found.");
		}
	}

	private FriendRequest ownedRequest(UUID requestId, UUID academyId, UUID actorId, boolean sender) {
		return requestRepository.lockById(Objects.requireNonNull(requestId, "requestId"))
				.filter(request -> request.academyId().equals(academyId))
				.filter(request -> sender ? request.senderId().equals(actorId) : request.receiverId().equals(actorId))
				.orElseThrow(() -> notFound(RelationshipException.Code.FRIEND_REQUEST_NOT_FOUND,
						"Friend request not found."));
	}

	private FriendRequestRepository.Identity ownedRequestIdentity(
			UUID requestId, UUID academyId, UUID actorId, boolean sender) {
		return requestRepository.findIdentityById(Objects.requireNonNull(requestId, "requestId"))
				.filter(request -> request.getAcademyId().equals(academyId))
				.filter(request -> sender
						? request.getSenderId().equals(actorId)
						: request.getReceiverId().equals(actorId))
				.orElseThrow(() -> notFound(RelationshipException.Code.FRIEND_REQUEST_NOT_FOUND,
						"Friend request not found."));
	}

	private void endPairRelationshipsAndRequests(UUID first, UUID second, Instant when) {
		UUID low = lower(first, second);
		UUID high = high(first, second);
		friendshipRepository.lockAllCurrentByPair(low, high)
				.forEach(friendship -> friendship.end(when));
		requestRepository.lockAllPendingByPair(low, high)
				.forEach(request -> request.cancel(when));
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
