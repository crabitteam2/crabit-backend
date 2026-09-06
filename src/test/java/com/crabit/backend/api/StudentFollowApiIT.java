package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

class StudentFollowApiIT extends SharedCardApiIntegrationSupport {
    @Test
    void followEndpointsPreserveAuthenticationMembershipAndMalformedInputBoundaries()
            throws Exception {
        mockMvc.perform(put(follow(FRIEND_ID))).andExpect(status().isUnauthorized());
        asToken(STAFF_TOKEN, put(follow(FRIEND_ID))).andExpect(status().isForbidden());
        asToken(OTHER_ACADEMY_TOKEN, put(follow(FRIEND_ID))).andExpect(status().isNotFound());
        asOwner(get(BASE + "/followers").param("limit", "0")).andExpect(status().isBadRequest());
        asOwner(get(BASE + "/following").param("nickname", " ")).andExpect(status().isBadRequest());
        asOwner(get(BASE + "/following").param("nickname", "a\nb"))
                .andExpect(status().isBadRequest());
        jdbc.update(
                "UPDATE academy_membership SET left_at=now() WHERE student_id=? AND academy_id=?",
                OWNER_ID,
                PRIMARY_ACADEMY_ID);
        asOwner(put(follow(FRIEND_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));
        asOwner(get(BASE + "/followers")).andExpect(status().isNotFound());
    }

    @Test
    void followersSearchAndPaginationUseIncomingRowsAndIndependentOutgoingFlags() throws Exception {
        commands.follow(NONFRIEND_ID, PRIMARY_ACADEMY_ID, OWNER_ID, COMMAND_TIME);
        String friendName =
                jdbc.queryForObject(
                        "SELECT nickname FROM student WHERE id=?", String.class, FRIEND_ID);
        asOwner(get(BASE + "/followers").param("nickname", friendName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].studentId").value(FRIEND_ID.toString()))
                .andExpect(jsonPath("$.items[0].isFollowing").value(false))
                .andExpect(jsonPath("$.items[0].isFollowedBy").value(true))
                .andExpect(jsonPath("$.followerCount").value(2))
                .andExpect(jsonPath("$.followingCount").value(0));
        String page =
                asOwner(get(BASE + "/followers").param("limit", "1"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.read(page, "$.nextCursor");
        String first = JsonPath.read(page, "$.items[0].studentId");
        String second =
                asOwner(get(BASE + "/followers").param("cursor", cursor))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(1))
                        .andExpect(jsonPath("$.nextCursor").isEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat((String) JsonPath.read(second, "$.items[0].studentId")).isNotEqualTo(first);
    }

    @Test
    void academyScopedUnfollowLeavesOtherAcademyAndReverseDirectionIntact() throws Exception {
        jdbc.update(
                "INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES"
                        + " (?,?,?,now()),(?,?,?,now())",
                UUID.randomUUID(),
                OWNER_ID,
                OTHER_ACADEMY_ID,
                UUID.randomUUID(),
                FRIEND_ID,
                OTHER_ACADEMY_ID);
        commands.follow(OWNER_ID, PRIMARY_ACADEMY_ID, FRIEND_ID, COMMAND_TIME);
        commands.follow(OWNER_ID, OTHER_ACADEMY_ID, FRIEND_ID, COMMAND_TIME);
        asOwner(delete(follow(FRIEND_ID))).andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM student_follow WHERE ended_at IS NULL AND"
                                        + " academy_id=?",
                                Long.class,
                                OTHER_ACADEMY_ID))
                .isOne();
        asOwner(get(BASE + "/followers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1));
    }

    @Test
    void followInAnotherAcademyRacingGlobalBlockCannotSurvive() throws Exception {
        jdbc.update(
                "INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES"
                        + " (?,?,?,now()),(?,?,?,now())",
                UUID.randomUUID(),
                OWNER_ID,
                OTHER_ACADEMY_ID,
                UUID.randomUUID(),
                FRIEND_ID,
                OTHER_ACADEMY_ID);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> follow =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    commands.follow(
                                            OWNER_ID, OTHER_ACADEMY_ID, FRIEND_ID, COMMAND_TIME);
                                    return true;
                                } catch (com.crabit.backend.relationship.RelationshipException e) {
                                    assertThat(e.code())
                                            .isEqualTo(
                                                    com.crabit.backend.relationship
                                                            .RelationshipException.Code
                                                            .STUDENT_NOT_FOUND);
                                    return false;
                                }
                            });
            Future<Integer> block =
                    executor.submit(
                            () -> {
                                start.await();
                                return asToken(
                                                FRIEND_TOKEN,
                                                post("/v1/me/student-blocks")
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"studentId\":\""
                                                                        + OWNER_ID
                                                                        + "\"}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            follow.get(15, TimeUnit.SECONDS);
            assertThat(block.get(15, TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(count()).isZero();
        }
    }

    private static final String BASE = "/v1/academies/" + PRIMARY_ACADEMY_ID;

    private String follow(UUID id) {
        return BASE + "/following/" + id;
    }

    private String studentFollowing(UUID studentId) {
        return BASE + "/students/" + studentId + "/following";
    }

    private String studentFollowers(UUID studentId) {
        return BASE + "/students/" + studentId + "/followers";
    }

    private long count() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM student_follow WHERE ended_at IS NULL", Long.class);
    }

    @Test
    void independentDirectionsIdempotenceAndNewActivationAfterRefollow() throws Exception {
        assertThat(count()).isEqualTo(1);
        asOwner(put(follow(FRIEND_ID))).andExpect(status().isNoContent());
        Instant first =
                jdbc.queryForObject(
                                "SELECT started_at FROM student_follow WHERE source_id = ? AND"
                                        + " target_id = ?",
                                java.sql.Timestamp.class,
                                OWNER_ID,
                                FRIEND_ID)
                        .toInstant();
        asOwner(put(follow(FRIEND_ID))).andExpect(status().isNoContent());
        assertThat(count()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT started_at FROM student_follow WHERE source_id = ?"
                                                + " AND target_id = ?",
                                        java.sql.Timestamp.class,
                                        OWNER_ID,
                                        FRIEND_ID)
                                .toInstant())
                .isEqualTo(first);
        asOwner(get(BASE + "/following"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followingCount").value(1))
                .andExpect(jsonPath("$.followerCount").value(1))
                .andExpect(jsonPath("$.items[0].isFollowedBy").value(true));
        asOwner(delete(follow(FRIEND_ID))).andExpect(status().isNoContent());
        asOwner(delete(follow(FRIEND_ID))).andExpect(status().isNoContent());
        assertThat(count()).isEqualTo(1);
        asOwner(put(follow(FRIEND_ID))).andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT started_at FROM student_follow WHERE source_id = ?"
                                                + " AND target_id = ?",
                                        java.sql.Timestamp.class,
                                        OWNER_ID,
                                        FRIEND_ID)
                                .toInstant())
                .isAfter(first);
    }

    @Test
    void privateErrorsAndNoCardAccountRequirement() throws Exception {
        asToken(FRIEND_TOKEN, put(follow(NONFRIEND_ID))).andExpect(status().isNoContent());
        asOwner(put(follow(OWNER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SELF_RELATIONSHIP"));
        for (UUID hidden : List.of(BLOCKED_ID, OTHER_ACADEMY_STUDENT_ID, UUID.randomUUID())) {
            asOwner(put(follow(hidden)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
            asOwner(delete(follow(hidden)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
        }
        asOwner(get(BASE + "/friend-requests/sent")).andExpect(status().isNotFound());
        asOwner(get(BASE + "/friends")).andExpect(status().isNotFound());
    }

    @Test
    void otherStudentListsKeepOwnerMembershipSeparateFromViewerRelationshipState()
            throws Exception {
        commands.follow(OWNER_ID, PRIMARY_ACADEMY_ID, NONFRIEND_ID, COMMAND_TIME);
        commands.follow(FRIEND_ID, PRIMARY_ACADEMY_ID, NONFRIEND_ID, COMMAND_TIME);
        commands.follow(NONFRIEND_ID, PRIMARY_ACADEMY_ID, OWNER_ID, COMMAND_TIME);

        asToken(FRIEND_TOKEN, get(studentFollowing(OWNER_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].studentId").value(NONFRIEND_ID.toString()))
                .andExpect(jsonPath("$.items[0].isFollowing").value(true))
                .andExpect(jsonPath("$.items[0].isFollowedBy").value(false))
                .andExpect(jsonPath("$.followingCount").value(1))
                .andExpect(jsonPath("$.followerCount").value(2));
        asToken(FRIEND_TOKEN, get(studentFollowing(OWNER_ID)).param("nickname", "없는검색어"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.followingCount").value(1))
                .andExpect(jsonPath("$.followerCount").value(2));

        String friendName =
                jdbc.queryForObject("SELECT nickname FROM student WHERE id=?", String.class, FRIEND_ID);
        asToken(FRIEND_TOKEN, get(studentFollowers(OWNER_ID)).param("nickname", friendName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].studentId").value(FRIEND_ID.toString()))
                .andExpect(jsonPath("$.items[0].isFollowing").value(false))
                .andExpect(jsonPath("$.items[0].isFollowedBy").value(false));

        String page =
                asToken(FRIEND_TOKEN, get(studentFollowers(OWNER_ID)).param("limit", "1"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.read(page, "$.nextCursor");
        asToken(FRIEND_TOKEN, get(studentFollowing(OWNER_ID)).param("cursor", cursor))
                .andExpect(status().isBadRequest());
        asOwner(get(studentFollowers(OWNER_ID)).param("cursor", cursor))
                .andExpect(status().isBadRequest());
        asToken(FRIEND_TOKEN, get(studentFollowers(FRIEND_ID)).param("cursor", cursor))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherStudentListsHideOnlyAnInaccessibleOwnerNotBlockedThirdPartyRows() throws Exception {
        commands.follow(OWNER_ID, PRIMARY_ACADEMY_ID, NONFRIEND_ID, COMMAND_TIME);

        asToken(
                        FRIEND_TOKEN,
                        post("/v1/me/student-blocks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"studentId\":\"" + NONFRIEND_ID + "\"}"))
                .andExpect(status().isCreated());
        asToken(FRIEND_TOKEN, get(studentFollowing(OWNER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].studentId").value(NONFRIEND_ID.toString()));

        asToken(
                        FRIEND_TOKEN,
                        post("/v1/me/student-blocks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"studentId\":\"" + OWNER_ID + "\"}"))
                .andExpect(status().isCreated());
        asToken(FRIEND_TOKEN, get(studentFollowing(OWNER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
        asToken(FRIEND_TOKEN, get(studentFollowers(OTHER_ACADEMY_STUDENT_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));
    }

    @Test
    void pagesBindFiltersDirectionActorAndSnapshotWhileCountsIgnoreSearch() throws Exception {
        asOwner(put(follow(FRIEND_ID))).andExpect(status().isNoContent());
        asOwner(put(follow(NONFRIEND_ID))).andExpect(status().isNoContent());
        String page =
                asOwner(get(BASE + "/following").param("limit", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.followingCount").value(2))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.read(page, "$.nextCursor");
        String first = JsonPath.read(page, "$.items[0].studentId");
        String second =
                asOwner(get(BASE + "/following").param("cursor", cursor))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(1))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat((String) JsonPath.read(second, "$.items[0].studentId")).isNotEqualTo(first);
        asOwner(get(BASE + "/following").param("nickname", "없는검색어"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.followingCount").value(2));
        asOwner(get(BASE + "/followers").param("cursor", cursor))
                .andExpect(status().isBadRequest());
        asOwner(get(BASE + "/following").param("nickname", "other").param("cursor", cursor))
                .andExpect(status().isBadRequest());
        asToken(FRIEND_TOKEN, get(BASE + "/following").param("cursor", cursor))
                .andExpect(status().isBadRequest());
        asOwner(get(BASE + "/following").param("cursor", cursor + "x"))
                .andExpect(status().isBadRequest());
        UUID remaining = UUID.fromString(JsonPath.read(second, "$.items[0].studentId"));
        asOwner(delete(follow(remaining))).andExpect(status().isNoContent());
        asOwner(put(follow(remaining))).andExpect(status().isNoContent());
        asOwner(get(BASE + "/following").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.followingCount").value(2));
    }

    @Test
    void followerVisibilityRequiresViewerToOwnerAndRechecksUnfollowAndBlock() throws Exception {
        String card = cardIdForWish(LAPTOP_WISH_ID);
        getAs(FRIEND_TOKEN, card).andExpect(status().isOk());
        asToken(FRIEND_TOKEN, delete(follow(OWNER_ID))).andExpect(status().isNoContent());
        asOwner(put(follow(FRIEND_ID))).andExpect(status().isNoContent());
        getAs(FRIEND_TOKEN, card).andExpect(status().isNotFound());
        asToken(FRIEND_TOKEN, put(follow(OWNER_ID))).andExpect(status().isNoContent());
        getAs(FRIEND_TOKEN, card).andExpect(status().isOk());
        asOwner(
                        post("/v1/me/student-blocks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"studentId\":\"" + FRIEND_ID + "\"}"))
                .andExpect(status().isCreated());
        assertThat(count()).isZero();
        getAs(FRIEND_TOKEN, card).andExpect(status().isNotFound());
        asOwner(delete("/v1/me/student-blocks/" + FRIEND_ID)).andExpect(status().isNoContent());
        assertThat(count()).isZero();
        asToken(FRIEND_TOKEN, put(follow(OWNER_ID))).andExpect(status().isNoContent());
        getAs(FRIEND_TOKEN, card).andExpect(status().isOk());
    }

    @Test
    void concurrentFollowAndBlockNeverLeaveActivePairOrFiveHundreds() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> a =
                    executor.submit(
                            () -> {
                                start.await();
                                return asOwner(put(follow(NONFRIEND_ID)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            Future<Integer> b =
                    executor.submit(
                            () -> {
                                start.await();
                                return asOwner(
                                                post("/v1/me/student-blocks")
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                "{\"studentId\":\""
                                                                        + NONFRIEND_ID
                                                                        + "\"}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS)).isIn(204, 404);
            assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM student_follow WHERE ended_at IS NULL AND"
                                            + " (source_id = ? OR target_id = ?)",
                                    Long.class,
                                    NONFRIEND_ID,
                                    NONFRIEND_ID))
                    .isZero();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.crabit.backend.relationship.RelationshipCommandService commands;

    @org.springframework.beans.factory.annotation.Autowired private javax.sql.DataSource dataSource;

    @Test
    void globalBlockEndsBothDirectionsInEveryAcademyAndPartialUnblockDoesNotRestore()
            throws Exception {
        jdbc.update(
                "INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES"
                        + " (?,?,?,now()),(?,?,?,now())",
                UUID.randomUUID(),
                OWNER_ID,
                OTHER_ACADEMY_ID,
                UUID.randomUUID(),
                FRIEND_ID,
                OTHER_ACADEMY_ID);
        commands.follow(OWNER_ID, PRIMARY_ACADEMY_ID, FRIEND_ID, COMMAND_TIME);
        commands.follow(OWNER_ID, OTHER_ACADEMY_ID, FRIEND_ID, COMMAND_TIME);
        commands.follow(FRIEND_ID, OTHER_ACADEMY_ID, OWNER_ID, COMMAND_TIME);
        assertThat(count()).isEqualTo(4);
        asOwner(
                        post("/v1/me/student-blocks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"studentId\":\"" + FRIEND_ID + "\"}"))
                .andExpect(status().isCreated());
        assertThat(count()).isZero();
        asToken(
                        FRIEND_TOKEN,
                        post("/v1/me/student-blocks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"studentId\":\"" + OWNER_ID + "\"}"))
                .andExpect(status().isCreated());
        asOwner(delete("/v1/me/student-blocks/" + FRIEND_ID)).andExpect(status().isNoContent());
        asOwner(put(follow(FRIEND_ID))).andExpect(status().isNotFound());
        asToken(FRIEND_TOKEN, delete("/v1/me/student-blocks/" + OWNER_ID))
                .andExpect(status().isNoContent());
        assertThat(count()).isZero();
        asOwner(put(follow(FRIEND_ID))).andExpect(status().isNoContent());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void subsequentPagesRecheckMembershipAndBilateralBlock() throws Exception {
        asOwner(put(follow(FRIEND_ID))).andExpect(status().isNoContent());
        asOwner(put(follow(NONFRIEND_ID))).andExpect(status().isNoContent());
        String page =
                asOwner(get(BASE + "/following").param("limit", "1"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.read(page, "$.nextCursor");
        // Equal clock instants sort by student ID, so the lower friend ID is the next row.
        jdbc.update(
                "UPDATE academy_membership SET left_at = now() WHERE student_id = ? AND academy_id"
                        + " = ?",
                FRIEND_ID,
                PRIMARY_ACADEMY_ID);
        asOwner(get(BASE + "/following").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.followingCount").value(1));
        jdbc.update(
                "UPDATE academy_membership SET left_at = NULL WHERE student_id = ? AND academy_id ="
                        + " ?",
                FRIEND_ID,
                PRIMARY_ACADEMY_ID);
        asToken(
                        FRIEND_TOKEN,
                        post("/v1/me/student-blocks")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"studentId\":\"" + OWNER_ID + "\"}"))
                .andExpect(status().isCreated());
        asOwner(get(BASE + "/following").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.followingCount").value(1));
    }

    @Test
    void overlappingDuplicateAndOpposingMutationsSerializeWithoutDuplicateRows() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> a =
                    executor.submit(
                            () -> {
                                start.await();
                                return asOwner(put(follow(NONFRIEND_ID)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            Future<Integer> b =
                    executor.submit(
                            () -> {
                                start.await();
                                return asOwner(put(follow(NONFRIEND_ID)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM student_follow WHERE source_id=? AND"
                                            + " target_id=? AND ended_at IS NULL",
                                    Long.class,
                                    OWNER_ID,
                                    NONFRIEND_ID))
                    .isOne();
            CountDownLatch race = new CountDownLatch(1);
            Future<Integer> c =
                    executor.submit(
                            () -> {
                                race.await();
                                return asOwner(delete(follow(NONFRIEND_ID)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            Future<Integer> d =
                    executor.submit(
                            () -> {
                                race.await();
                                return asOwner(put(follow(NONFRIEND_ID)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            race.countDown();
            assertThat(c.get(15, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(d.get(15, TimeUnit.SECONDS)).isEqualTo(204);
            asOwner(delete(follow(NONFRIEND_ID))).andExpect(status().isNoContent());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM student_follow WHERE source_id=? AND"
                                            + " target_id=? AND ended_at IS NULL",
                                    Long.class,
                                    OWNER_ID,
                                    NONFRIEND_ID))
                    .isZero();
            asOwner(put(follow(NONFRIEND_ID))).andExpect(status().isNoContent());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM student_follow WHERE source_id=? AND"
                                            + " target_id=? AND ended_at IS NULL",
                                    Long.class,
                                    OWNER_ID,
                                    NONFRIEND_ID))
                    .isOne();
        }
    }

    @Test
    void firstPageSnapshotExcludesUncommittedLowerSequenceActivationAfterItCommits()
            throws Exception {
        jdbc.update("UPDATE student_block SET released_at=now() WHERE blocked_id=?", BLOCKED_ID);
        asOwner(put(follow(FRIEND_ID))).andExpect(status().isNoContent());
        try (java.sql.Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (java.sql.PreparedStatement insert =
                    connection.prepareStatement(
                            "INSERT INTO"
                                + " student_follow(id,academy_id,source_id,target_id,started_at)"
                                + " VALUES(?,?,?,?,?)")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, PRIMARY_ACADEMY_ID);
                insert.setObject(3, OWNER_ID);
                insert.setObject(4, BLOCKED_ID);
                insert.setTimestamp(5, java.sql.Timestamp.from(COMMAND_TIME.minusSeconds(1)));
                insert.executeUpdate();
            }
            asOwner(put(follow(NONFRIEND_ID))).andExpect(status().isNoContent());
            String page =
                    asOwner(get(BASE + "/following").param("limit", "1"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.followingCount").value(2))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            String cursor = JsonPath.read(page, "$.nextCursor");
            connection.commit();
            asOwner(get(BASE + "/following").param("cursor", cursor))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].studentId").value(FRIEND_ID.toString()))
                    .andExpect(jsonPath("$.followingCount").value(3));
            asOwner(get(BASE + "/following"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(3));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    void cursorIssuedByOneInstanceRemainsValidOnAnotherInstance() throws Exception {
        asOwner(put(follow(FRIEND_ID))).andExpect(status().isNoContent());
        asOwner(put(follow(NONFRIEND_ID))).andExpect(status().isNoContent());
        String page =
                asOwner(get(BASE + "/following").param("limit", "1"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String cursor = JsonPath.read(page, "$.nextCursor");
        var otherInstance =
                new com.crabit.backend.relationship.RelationshipQueryService(
                        jdbc,
                        context.getBean(
                                com.crabit.backend.account.AcademyMembershipRepository.class),
                        context.getBean(com.crabit.backend.account.StudentRepository.class));
        assertThat(
                        otherInstance
                                .follows(OWNER_ID, PRIMARY_ACADEMY_ID, true, null, cursor, 20)
                                .items())
                .hasSize(1);
    }
}
