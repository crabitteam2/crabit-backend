package com.crabit.backend.relationship;

import com.crabit.backend.account.AcademyMembershipRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class RelationshipContextAuthorizationService {

    private final AcademyMembershipRepository membershipRepository;
    private final StudentFollowRepository followRepository;
    private final StudentBlockRepository blockRepository;

    public RelationshipContextAuthorizationService(
            AcademyMembershipRepository membershipRepository,
            StudentFollowRepository followRepository,
            StudentBlockRepository blockRepository) {
        this.membershipRepository = membershipRepository;
        this.followRepository = followRepository;
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
    public boolean canViewFollowersCard(UUID ownerId, UUID viewerId, UUID academyId) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(viewerId, "viewerId");
        Objects.requireNonNull(academyId, "academyId");
        if (ownerId.equals(viewerId)) {
            return false;
        }
        if (!canViewAcademyCard(ownerId, viewerId, academyId)) {
            return false;
        }
        return followRepository.existsByAcademyIdAndSourceIdAndTargetIdAndEndedAtIsNull(
                academyId, viewerId, ownerId);
    }

    private boolean isBlockedInEitherDirection(UUID ownerId, UUID viewerId) {
        return blockRepository.existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(ownerId, viewerId)
                || blockRepository.existsByBlockerIdAndBlockedIdAndReleasedAtIsNull(
                        viewerId, ownerId);
    }
}
