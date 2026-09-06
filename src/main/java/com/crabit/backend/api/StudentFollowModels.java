package com.crabit.backend.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StudentFollowModels {
    private StudentFollowModels() {}

    public record StudentRelationship(
            UUID studentId, String nickname, boolean isFollowing, boolean isFollowedBy) {}

    public record StudentRelationshipPage(List<StudentRelationship> items, String nextCursor) {}

    public record Follow(
            UUID studentId,
            String nickname,
            Instant followedAt,
            boolean isFollowing,
            boolean isFollowedBy) {}

    public record FollowPage(
            List<Follow> items, String nextCursor, long followingCount, long followerCount) {}

    @Schema(
            name = "CreateStudentBlockRequest",
            description =
                    "Request payload naming only the blocked student; the blocker always comes from"
                        + " CurrentPrincipal.subjectId.")
    public record CreateStudentBlockRequest(
            @NotNull
                    @Schema(
                            description = "UUID of the student to block globally.",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    UUID studentId) {

        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported field: " + field);
        }
    }

    @Schema(name = "StudentBlock")
    public record StudentBlockView(
            @Schema(
                            description = "Stable UUID of the blocked student.",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    UUID studentId,
            @Schema(
                            description = "Current nonblank nickname of the blocked student.",
                            minLength = 1,
                            maxLength = 80,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String nickname,
            @Schema(
                            description =
                                    "RFC 3339 UTC Z instant at which the current directional block"
                                        + " was created or recreated.",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    Instant blockedAt) {}

    @Schema(name = "StudentBlockPage")
    public record StudentBlockPage(
            @ArraySchema(
                            arraySchema =
                                    @Schema(
                                            description =
                                                    "Active blocks created by the authenticated"
                                                        + " student, ordered by blockedAt"
                                                        + " descending, studentId descending.",
                                            requiredMode = Schema.RequiredMode.REQUIRED),
                            schema = @Schema(implementation = StudentBlockView.class))
                    List<StudentBlockView> items,
            @Schema(
                            description =
                                    "Opaque cursor derived from the final returned (blockedAt,"
                                        + " studentId) tuple; null when no further item exists.",
                            nullable = true,
                            minLength = 1,
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    String nextCursor) {}
}
