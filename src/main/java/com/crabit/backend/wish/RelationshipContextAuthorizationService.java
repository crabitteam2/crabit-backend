package com.crabit.backend.wish;

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
	public boolean canViewFriendsCard(UUID ownerId, UUID viewerId, UUID academyId) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(academyId, "academyId");
		if (ownerId.equals(viewerId)) {
			return false;
		}
		if (!membershipRepository.existsByStudentIdAndAcademyIdAndLeftAtIsNull(ownerId, academyId)
				|| !membershipRepository.existsByStudentIdAndAcademyIdAndLeftAtIsNull(
						viewerId, academyId)) {
			return false;
		}
		UUID low = ownerId.toString().compareTo(viewerId.toString()) < 0 ? ownerId : viewerId;
		UUID high = low.equals(ownerId) ? viewerId : ownerId;
		if (!friendshipRepository.existsByAcademyIdAndStudentLowIdAndStudentHighIdAndEndedAtIsNull(
				academyId, low, high)) {
			return false;
		}
		return !blockRepository.existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(ownerId, viewerId)
				&& !blockRepository.existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(viewerId, ownerId);
	}
}
