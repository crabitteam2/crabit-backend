package com.crabit.backend.relationship;

import com.crabit.backend.account.AcademyMembershipRepository;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RelationshipContextAuthorizationService {

	private final AcademyMembershipRepository membershipRepository;
	private final FriendshipRepository friendshipRepository;
	private final StudentBlockRepository blockRepository;

	public RelationshipContextAuthorizationService(
			AcademyMembershipRepository membershipRepository,
			FriendshipRepository friendshipRepository,
			StudentBlockRepository blockRepository) {
		this.membershipRepository = membershipRepository;
		this.friendshipRepository = friendshipRepository;
		this.blockRepository = blockRepository;
	}

	@Transactional(readOnly = true)
	public boolean canAccessAcademy(UUID viewerId, UUID academyId) {
		return membershipRepository.existsByStudentIdAndAcademyIdAndLeftAtIsNull(
				Objects.requireNonNull(viewerId, "viewerId"),
				Objects.requireNonNull(academyId, "academyId"));
	}

	@Transactional(readOnly = true)
	public boolean canViewAcademyCard(UUID ownerId, UUID viewerId, UUID academyId) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(academyId, "academyId");
		if (!canAccessAcademy(ownerId, academyId) || !canAccessAcademy(viewerId, academyId)) {
			return false;
		}
		if (ownerId.equals(viewerId)) {
			return true;
		}
		return !isBlockedInEitherDirection(ownerId, viewerId);
	}

	@Transactional(readOnly = true)
	public boolean canViewFriendsCard(UUID ownerId, UUID viewerId, UUID academyId) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(academyId, "academyId");
		if (ownerId.equals(viewerId)) {
			return false;
		}
		if (!canViewAcademyCard(ownerId, viewerId, academyId)) {
			return false;
		}
		UUID low = ownerId.toString().compareTo(viewerId.toString()) < 0 ? ownerId : viewerId;
		UUID high = low.equals(ownerId) ? viewerId : ownerId;
		if (!friendshipRepository.existsByAcademyIdAndStudentLowIdAndStudentHighIdAndEndedAtIsNull(
				academyId, low, high)) {
			return false;
		}
		return true;
	}

	private boolean isBlockedInEitherDirection(UUID ownerId, UUID viewerId) {
		return blockRepository.existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(ownerId, viewerId)
				|| blockRepository.existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(viewerId, ownerId);
	}
}
