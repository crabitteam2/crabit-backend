package com.crabit.backend.relationship;

import com.crabit.backend.account.AcademyMembershipRepository;
import com.crabit.backend.account.Student;
import com.crabit.backend.account.StudentRepository;
import com.crabit.backend.api.StudentFollowModels.Follow;
import com.crabit.backend.api.StudentFollowModels.FollowPage;
import com.crabit.backend.api.StudentFollowModels.StudentBlockPage;
import com.crabit.backend.api.StudentFollowModels.StudentBlockView;
import com.crabit.backend.api.StudentFollowModels.StudentRelationship;
import com.crabit.backend.api.StudentFollowModels.StudentRelationshipPage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class RelationshipQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final String SEP = "\u001f";

    private final JdbcTemplate jdbc;
    private final AcademyMembershipRepository memberships;
    private final StudentRepository students;

    public RelationshipQueryService(
            JdbcTemplate jdbc,
            AcademyMembershipRepository memberships,
            StudentRepository students) {
        this.jdbc = jdbc;
        this.memberships = memberships;
        this.students = students;
    }

    private static final String ELIGIBLE =
            """
            FROM student s JOIN academy_membership m ON m.student_id = s.id AND m.academy_id = ? AND m.left_at IS NULL
            LEFT JOIN student_follow outgoing ON outgoing.academy_id = m.academy_id AND outgoing.source_id = ? AND outgoing.target_id = s.id AND outgoing.ended_at IS NULL
            LEFT JOIN student_follow incoming ON incoming.academy_id = m.academy_id AND incoming.source_id = s.id AND incoming.target_id = ? AND incoming.ended_at IS NULL
            WHERE s.id <> ? AND NOT EXISTS (SELECT 1 FROM student_block b WHERE b.released_at IS NULL AND ((b.blocker_id = ? AND b.blocked_id = s.id) OR (b.blocker_id = s.id AND b.blocked_id = ?)))
            """;

    @Transactional(readOnly = true)
    public StudentRelationship getStudent(UUID actorId, UUID academyId, UUID studentId) {
        requireAcademy(actorId, academyId);
        return jdbc.query("""
                SELECT s.id, s.nickname,
                  EXISTS (SELECT 1 FROM student_follow f WHERE f.academy_id = m.academy_id
                    AND f.source_id = ? AND f.target_id = s.id AND f.ended_at IS NULL) AS outgoing,
                  EXISTS (SELECT 1 FROM student_follow f WHERE f.academy_id = m.academy_id
                    AND f.source_id = s.id AND f.target_id = ? AND f.ended_at IS NULL) AS incoming
                FROM student s JOIN academy_membership m ON m.student_id = s.id
                WHERE m.academy_id = ? AND m.left_at IS NULL AND s.id = ?
                  AND NOT EXISTS (SELECT 1 FROM student_block b WHERE b.released_at IS NULL
                    AND ((b.blocker_id = ? AND b.blocked_id = s.id)
                      OR (b.blocker_id = s.id AND b.blocked_id = ?)))
                """, (rs, n) -> new StudentRelationship(rs.getObject("id", UUID.class),
                    rs.getString("nickname"), !actorId.equals(studentId) && rs.getBoolean("outgoing"),
                    !actorId.equals(studentId) && rs.getBoolean("incoming")),
                actorId, actorId, academyId, studentId, actorId, actorId)
                .stream().findFirst().orElseThrow(() -> new RelationshipException(
                    RelationshipException.Code.STUDENT_NOT_FOUND, "Student not found."));
    }

    @Transactional(readOnly = true)
    public StudentRelationshipPage search(
            UUID actorId, UUID academyId, String nickname, String rawCursor, Integer rawLimit) {
        requireAcademy(actorId, academyId);
        String filter = normalizeNickname(nickname);
        int limit = limit(rawLimit);
        Cursor cursor = decode(rawCursor, "students", actorId, null, academyId, filter);
        String name = cursor == null ? null : cursor.text();
        UUID id = cursor == null ? null : cursor.id();
        List<StudentRelationship> rows =
                jdbc.query(
                        "SELECT s.id, s.nickname, outgoing.id IS NOT NULL AS outgoing, incoming.id"
                            + " IS NOT NULL AS incoming "
                                + ELIGIBLE
                                + """
                                AND POSITION(? IN s.nickname) > 0 AND (CAST(? AS varchar) IS NULL OR s.nickname > ? OR (s.nickname = ? AND s.id > ?))
                                ORDER BY s.nickname ASC, s.id ASC LIMIT ?
                                """,
                        (rs, n) ->
                                new StudentRelationship(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("nickname"),
                                        rs.getBoolean("outgoing"),
                                        rs.getBoolean("incoming")),
                        academyId,
                        actorId,
                        actorId,
                        actorId,
                        actorId,
                        actorId,
                        filter,
                        name,
                        name,
                        name,
                        id,
                        limit + 1);
        return studentPage(rows, limit, actorId, academyId, filter);
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public FollowPage follows(
            UUID actorId,
            UUID academyId,
            boolean outgoing,
            String nickname,
            String rawCursor,
            Integer rawLimit) {
        return follows(actorId, actorId, academyId, outgoing, nickname, rawCursor, rawLimit);
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public FollowPage follows(
            UUID viewerId,
            UUID ownerId,
            UUID academyId,
            boolean outgoing,
            String nickname,
            String rawCursor,
            Integer rawLimit) {
        requireAcademy(viewerId, academyId);
        requireVisibleOwner(viewerId, ownerId, academyId);
        String filter = nickname == null ? "" : normalizeNickname(nickname);
        int limit = limit(rawLimit);
        String operation = outgoing ? "following" : "followers";
        Cursor cursor = decode(rawCursor, operation, viewerId, ownerId, academyId, filter);
        long watermark;
        String snapshot;
        Timestamp boundary = null;
        UUID id = null;
        try {
            if (cursor == null) {
                watermark =
                        jdbc.queryForObject(
                                "SELECT COALESCE(MAX(activation),0) FROM student_follow",
                                Long.class);
                snapshot = jdbc.queryForObject("SELECT pg_current_snapshot()::text", String.class);
            } else {
                String[] parts = cursor.text().split("/", -1);
                if (parts.length != 3) throw new IllegalArgumentException();
                watermark = Long.parseLong(parts[0]);
                snapshot = parts[1];
                boundary = Timestamp.from(Instant.parse(parts[2]));
                id = cursor.id();
            }
        } catch (RuntimeException e) {
            throw malformed("cursor", "Cursor is malformed or bound to another request.");
        }
        String ownerColumn = outgoing ? "source_id" : "target_id";
        String listedColumn = outgoing ? "target_id" : "source_id";
        List<Follow> rows =
                jdbc.query(
                        "SELECT listed.id, listed.nickname, relation.started_at, outgoing.id IS NOT"
                                + " NULL AS outgoing, incoming.id IS NOT NULL AS incoming"
                                + " FROM student_follow relation"
                                + " JOIN student listed ON listed.id = relation."
                                + listedColumn
                                + " JOIN academy_membership membership ON membership.student_id ="
                                + " listed.id AND membership.academy_id = relation.academy_id AND"
                                + " membership.left_at IS NULL"
                                + " LEFT JOIN student_follow outgoing ON outgoing.academy_id ="
                                + " relation.academy_id AND outgoing.source_id = ? AND"
                                + " outgoing.target_id = listed.id AND outgoing.ended_at IS NULL"
                                + " LEFT JOIN student_follow incoming ON incoming.academy_id ="
                                + " relation.academy_id AND incoming.source_id = listed.id AND"
                                + " incoming.target_id = ? AND incoming.ended_at IS NULL"
                                + " WHERE relation.academy_id = ? AND relation."
                                + ownerColumn
                                + " = ? AND relation.ended_at IS NULL"
                                + " AND relation.activation <= ? AND pg_visible_in_snapshot("
                                + "relation.xmin::text::xid8, CAST(? AS pg_snapshot)) AND POSITION(?"
                                + " IN listed.nickname) > 0 AND (CAST(? AS timestamptz) IS NULL OR"
                                + " relation.started_at < ? OR (relation.started_at = ? AND listed.id"
                                + " < ?)) ORDER BY relation.started_at DESC, listed.id DESC LIMIT ?",
                        (rs, n) -> {
                            UUID listedId = rs.getObject("id", UUID.class);
                            boolean viewerIsListed = viewerId.equals(listedId);
                            return new Follow(
                                    listedId,
                                    rs.getString("nickname"),
                                    rs.getTimestamp("started_at").toInstant(),
                                    !viewerIsListed && rs.getBoolean("outgoing"),
                                    !viewerIsListed && rs.getBoolean("incoming"));
                        },
                        viewerId,
                        viewerId,
                        academyId,
                        ownerId,
                        watermark,
                        snapshot,
                        filter,
                        boundary,
                        boundary,
                        boundary,
                        id,
                        limit + 1);
        List<Follow> items = trim(rows, limit);
        String next =
                rows.size() > limit
                        ? encode(
                                operation,
                                viewerId,
                                ownerId,
                                academyId,
                                filter,
                                watermark + "/" + snapshot + "/" + items.getLast().followedAt(),
                                items.getLast().studentId())
                        : null;
        return new FollowPage(
                items,
                next,
                countOwnerFollows(ownerId, academyId, true),
                countOwnerFollows(ownerId, academyId, false));
    }

    private long countOwnerFollows(UUID ownerId, UUID academyId, boolean outgoing) {
        String ownerColumn = outgoing ? "source_id" : "target_id";
        String listedColumn = outgoing ? "target_id" : "source_id";
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM student_follow relation"
                        + " JOIN academy_membership membership ON membership.student_id = relation."
                        + listedColumn
                        + " AND membership.academy_id = relation.academy_id AND"
                        + " membership.left_at IS NULL"
                        + " WHERE relation.academy_id = ? AND relation."
                        + ownerColumn
                        + " = ? AND relation.ended_at IS NULL",
                Long.class,
                academyId,
                ownerId);
    }

    private void requireVisibleOwner(UUID viewerId, UUID ownerId, UUID academyId) {
        Boolean visible =
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM academy_membership membership WHERE"
                                + " membership.student_id = ? AND membership.academy_id = ? AND"
                                + " membership.left_at IS NULL AND NOT EXISTS (SELECT 1 FROM"
                                + " student_block block WHERE block.released_at IS NULL AND"
                                + " ((block.blocker_id = ? AND block.blocked_id = ?) OR"
                                + " (block.blocker_id = ? AND block.blocked_id = ?))))",
                        Boolean.class,
                        ownerId,
                        academyId,
                        viewerId,
                        ownerId,
                        ownerId,
                        viewerId);
        if (!Boolean.TRUE.equals(visible)) {
            throw new RelationshipException(
                    RelationshipException.Code.STUDENT_NOT_FOUND, "Student not found.");
        }
    }

    @Transactional(readOnly = true)
    public StudentBlockPage blocks(UUID actorId, String rawCursor, Integer rawLimit) {
        int limit = limit(rawLimit);
        Cursor cursor = decode(rawCursor, "blocks", actorId, null, null, "");
        Timestamp at = cursor == null ? null : Timestamp.from(cursor.instant());
        UUID id = cursor == null ? null : cursor.id();
        List<StudentBlockView> rows =
                jdbc.query(
                        """
                        SELECT student.id, student.nickname, block.blocked_at
                        FROM student_block block
                        JOIN student ON student.id = block.blocked_id
                        WHERE block.blocker_id = ? AND block.released_at IS NULL
                          AND (CAST(? AS timestamptz) IS NULL OR block.blocked_at < ?
                               OR (block.blocked_at = ? AND student.id < ?))
                        ORDER BY block.blocked_at DESC, student.id DESC
                        LIMIT ?
                        """,
                        (rs, row) ->
                                new StudentBlockView(
                                        UUID.fromString(rs.getString("id")),
                                        rs.getString("nickname"),
                                        rs.getTimestamp("blocked_at").toInstant()),
                        actorId,
                        at,
                        at,
                        at,
                        id,
                        limit + 1);
        boolean more = rows.size() > limit;
        List<StudentBlockView> items = trim(rows, limit);
        String next =
                more
                        ? encode(
                                "blocks",
                                actorId,
                                null,
                                null,
                                "",
                                items.getLast().blockedAt().toString(),
                                items.getLast().studentId())
                        : null;
        return new StudentBlockPage(items, next);
    }

    @Transactional(readOnly = true)
    public StudentBlockView project(StudentBlock block) {
        Student counterpart =
                students.findById(block.blockedId())
                        .orElseThrow(() -> new IllegalStateException("Blocked student is missing"));
        return new StudentBlockView(counterpart.id(), counterpart.nickname(), block.blockedAt());
    }

    private StudentRelationshipPage studentPage(
            List<StudentRelationship> rows, int limit, UUID actor, UUID academy, String filter) {
        boolean more = rows.size() > limit;
        List<StudentRelationship> items = trim(rows, limit);
        String next =
                more
                        ? encode(
                                "students",
                                actor,
                                null,
                                academy,
                                filter,
                                items.getLast().nickname(),
                                items.getLast().studentId())
                        : null;
        return new StudentRelationshipPage(items, next);
    }

    private void requireAcademy(UUID actorId, UUID academyId) {
        if (memberships.findByStudentIdAndAcademyIdAndLeftAtIsNull(actorId, academyId).isEmpty()) {
            throw new RelationshipException(
                    RelationshipException.Code.ACADEMY_NOT_FOUND, "Academy not found.");
        }
    }

    static String normalizeNickname(String value) {
        if (value == null) {
            throw malformed("nickname", "Nickname search is required.");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        int start = 0;
        int end = normalized.length();
        while (start < end && isSpaceSeparator(normalized.codePointAt(start))) {
            start += Character.charCount(normalized.codePointAt(start));
        }
        while (end > start && isSpaceSeparator(normalized.codePointBefore(end))) {
            end -= Character.charCount(normalized.codePointBefore(end));
        }
        normalized = normalized.substring(start, end);
        long count = normalized.codePoints().count();
        boolean disallowed =
                normalized
                        .codePoints()
                        .anyMatch(
                                codePoint ->
                                        switch (Character.getType(codePoint)) {
                                            case Character.CONTROL,
                                                    Character.FORMAT,
                                                    Character.LINE_SEPARATOR,
                                                    Character.PARAGRAPH_SEPARATOR ->
                                                    true;
                                            default -> false;
                                        });
        if (count < 1 || count > 80 || disallowed) {
            throw malformed("nickname", "Must contain 1 through 80 allowed Unicode code points.");
        }
        return normalized;
    }

    private static boolean isSpaceSeparator(int codePoint) {
        return Character.getType(codePoint) == Character.SPACE_SEPARATOR;
    }

    private static int limit(Integer value) {
        int resolved = value == null ? DEFAULT_LIMIT : value;
        if (resolved < 1 || resolved > MAX_LIMIT) {
            throw malformed("limit", "Must be between 1 and 100.");
        }
        return resolved;
    }

    private String encode(
            String operation,
            UUID actor,
            UUID owner,
            UUID academy,
            String filter,
            String boundary,
            UUID id) {
        String value =
                String.join(
                        SEP,
                        "follow-v2",
                        operation,
                        actor.toString(),
                        owner == null ? "-" : owner.toString(),
                        academy == null ? "-" : academy.toString(),
                        filter,
                        boundary,
                        id.toString());
        String payload =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(payload);
    }

    private Cursor decode(
            String raw, String operation, UUID actor, UUID owner, UUID academy, String filter) {
        if (raw == null) {
            return null;
        }
        try {
            String[] signed = raw.split("\\.", -1);
            if (signed.length != 2
                    || !java.security.MessageDigest.isEqual(
                            sign(signed[0]).getBytes(StandardCharsets.UTF_8),
                            signed[1].getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException();
            String decoded =
                    new String(Base64.getUrlDecoder().decode(signed[0]), StandardCharsets.UTF_8);
            String[] parts = decoded.split(SEP, -1);
            String expectedOwner = owner == null ? "-" : owner.toString();
            String expectedAcademy = academy == null ? "-" : academy.toString();
            if (parts.length != 8
                    || !parts[0].equals("follow-v2")
                    || !parts[1].equals(operation)
                    || !parts[2].equals(actor.toString())
                    || !parts[3].equals(expectedOwner)
                    || !parts[4].equals(expectedAcademy)
                    || !parts[5].equals(filter)) {
                throw new IllegalArgumentException();
            }
            return new Cursor(parts[6], UUID.fromString(parts[7]));
        } catch (RuntimeException exception) {
            throw malformed("cursor", "Cursor is malformed or bound to another request.");
        }
    }

    private static RelationshipException malformed(String field, String message) {
        return new RelationshipException(
                RelationshipException.Code.MALFORMED_REQUEST, message, field);
    }

    private static <T> List<T> trim(List<T> values, int limit) {
        return values.size() <= limit ? List.copyOf(values) : List.copyOf(values.subList(0, limit));
    }

    private String sign(String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(
                    new javax.crypto.spec.SecretKeySpec(
                            jdbc.queryForObject(
                                            "SELECT secret FROM relationship_cursor_key WHERE id ="
                                                + " 1",
                                            String.class)
                                    .getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Cursor(String text, UUID id) {
        Instant instant() {
            try {
                return Instant.parse(text);
            } catch (RuntimeException exception) {
                throw malformed("cursor", "Cursor is malformed or bound to another request.");
            }
        }
    }
}
