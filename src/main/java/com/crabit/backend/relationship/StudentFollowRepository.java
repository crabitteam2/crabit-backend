package com.crabit.backend.relationship;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudentFollowRepository extends JpaRepository<StudentFollow, UUID> {

    boolean existsByAcademyIdAndSourceIdAndTargetIdAndEndedAtIsNull(
            UUID academyId, UUID sourceId, UUID targetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select follow from StudentFollow follow where follow.academyId = :academyId and"
                + " follow.sourceId = :sourceId and follow.targetId = :targetId and follow.endedAt"
                + " is null")
    Optional<StudentFollow> lockCurrentByAcademyAndPair(
            @Param("academyId") UUID academyId,
            @Param("sourceId") UUID sourceId,
            @Param("targetId") UUID targetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select follow from StudentFollow follow where ((follow.sourceId = :sourceId and"
                + " follow.targetId = :targetId) or (follow.sourceId = :targetId and"
                + " follow.targetId = :sourceId)) and follow.endedAt is null order by"
                + " follow.academyId, follow.sourceId")
    List<StudentFollow> lockAllCurrentByPair(
            @Param("sourceId") UUID sourceId, @Param("targetId") UUID targetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select follow from StudentFollow follow where follow.academyId = :academyId and"
                + " follow.sourceId = :sourceId and follow.targetId = :targetId")
    Optional<StudentFollow> lockByAcademyAndPair(
            @Param("academyId") UUID academyId,
            @Param("sourceId") UUID sourceId,
            @Param("targetId") UUID targetId);

    @Query(
            "select follow from StudentFollow follow where follow.academyId = :academyId and"
                + " follow.endedAt is null and (follow.sourceId = :studentId or follow.targetId ="
                + " :studentId)")
    List<StudentFollow> findAllCurrentByAcademyAndStudent(
            @Param("academyId") UUID academyId, @Param("studentId") UUID studentId);
}
