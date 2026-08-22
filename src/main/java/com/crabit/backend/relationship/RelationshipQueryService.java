package com.crabit.backend.relationship;

import com.crabit.backend.account.AcademyMembershipRepository;
import com.crabit.backend.account.Student;
import com.crabit.backend.account.StudentRepository;
import com.crabit.backend.api.FriendManagementModels.Friend;
import com.crabit.backend.api.FriendManagementModels.FriendPage;
import com.crabit.backend.api.FriendManagementModels.FriendRequestPage;
import com.crabit.backend.api.FriendManagementModels.FriendRequestView;
import com.crabit.backend.api.FriendManagementModels.RelationshipState;
import com.crabit.backend.api.FriendManagementModels.StudentBlockPage;
import com.crabit.backend.api.FriendManagementModels.StudentBlockView;
import com.crabit.backend.api.FriendManagementModels.StudentRelationship;
import com.crabit.backend.api.FriendManagementModels.StudentRelationshipPage;
import com.crabit.backend.api.FriendManagementModels.StudentSummary;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	@Transactional(readOnly = true)
	public StudentRelationshipPage search(
			UUID actorId, UUID academyId, String nickname, String rawCursor, Integer rawLimit) {
		requireAcademy(actorId, academyId);
		String filter = normalizeNickname(nickname);
		int limit = limit(rawLimit);
		Cursor cursor = decode(rawCursor, "students", actorId, academyId, filter);
		String boundaryNickname = cursor == null ? null : cursor.text();
		UUID boundaryId = cursor == null ? null : cursor.id();
		List<StudentRelationship> rows = jdbc.query("""
				SELECT student.id, student.nickname,
				       CASE
				         WHEN friendship.id IS NOT NULL THEN 'FRIEND'
				         WHEN outgoing.id IS NOT NULL THEN 'OUTGOING_PENDING'
				         WHEN incoming.id IS NOT NULL THEN 'INCOMING_PENDING'
				         ELSE 'NONE'
				       END AS relationship_state
				FROM student
				JOIN academy_membership membership
				  ON membership.student_id = student.id
				 AND membership.academy_id = ?
				 AND membership.left_at IS NULL
				LEFT JOIN friendship
				  ON friendship.academy_id = ? AND friendship.ended_at IS NULL
				 AND friendship.student_low_id = LEAST(?, student.id)
				 AND friendship.student_high_id = GREATEST(?, student.id)
				LEFT JOIN friend_request outgoing
				  ON outgoing.academy_id = ? AND outgoing.sender_id = ?
				 AND outgoing.receiver_id = student.id AND outgoing.status = 'PENDING'
				LEFT JOIN friend_request incoming
				  ON incoming.academy_id = ? AND incoming.sender_id = student.id
				 AND incoming.receiver_id = ? AND incoming.status = 'PENDING'
				WHERE student.id <> ?
				  AND POSITION(? IN student.nickname) > 0
				  AND NOT EXISTS (
				    SELECT 1 FROM student_block block
				    WHERE block.released_at IS NULL
				      AND ((block.blocker_id = ? AND block.blocked_id = student.id)
				        OR (block.blocker_id = student.id AND block.blocked_id = ?)))
				  AND (CAST(? AS varchar) IS NULL OR student.nickname > ?
				       OR (student.nickname = ? AND student.id > ?))
				ORDER BY student.nickname ASC, student.id ASC
				LIMIT ?
				""", (rs, row) -> new StudentRelationship(
				UUID.fromString(rs.getString("id")),
				rs.getString("nickname"),
				RelationshipState.valueOf(rs.getString("relationship_state"))),
				academyId, academyId, actorId, actorId, academyId, actorId, academyId, actorId,
				actorId, filter, actorId, actorId,
				boundaryNickname, boundaryNickname, boundaryNickname, boundaryId, limit + 1);
		return studentPage(rows, limit, actorId, academyId, filter);
	}

	@Transactional(readOnly = true)
	public FriendPage friends(UUID actorId, UUID academyId, String rawCursor, Integer rawLimit) {
		requireAcademy(actorId, academyId);
		int limit = limit(rawLimit);
		Cursor cursor = decode(rawCursor, "friends", actorId, academyId, "");
		Timestamp at = cursor == null ? null : Timestamp.from(cursor.instant());
		UUID id = cursor == null ? null : cursor.id();
		List<Friend> rows = jdbc.query("""
				SELECT other_student.id, other_student.nickname, friendship.started_at
				FROM friendship
				JOIN student other_student ON other_student.id = CASE
				  WHEN friendship.student_low_id = ? THEN friendship.student_high_id
				  ELSE friendship.student_low_id END
				JOIN academy_membership other_membership
				  ON other_membership.student_id = other_student.id
				 AND other_membership.academy_id = friendship.academy_id
				 AND other_membership.left_at IS NULL
				WHERE friendship.academy_id = ? AND friendship.ended_at IS NULL
				  AND (friendship.student_low_id = ? OR friendship.student_high_id = ?)
				  AND NOT EXISTS (
				    SELECT 1 FROM student_block block
				    WHERE block.released_at IS NULL
				      AND ((block.blocker_id = ? AND block.blocked_id = other_student.id)
				        OR (block.blocker_id = other_student.id AND block.blocked_id = ?)))
				  AND (CAST(? AS timestamptz) IS NULL OR friendship.started_at < ?
				       OR (friendship.started_at = ? AND other_student.id < ?))
				ORDER BY friendship.started_at DESC, other_student.id DESC
				LIMIT ?
				""", (rs, row) -> new Friend(
				UUID.fromString(rs.getString("id")), rs.getString("nickname"),
				rs.getTimestamp("started_at").toInstant()),
				actorId, academyId, actorId, actorId, actorId, actorId,
				at, at, at, id, limit + 1);
		boolean more = rows.size() > limit;
		List<Friend> items = trim(rows, limit);
		String next = more ? encode("friends", actorId, academyId, "",
				items.getLast().friendsSince().toString(), items.getLast().studentId()) : null;
		return new FriendPage(items, next);
	}

	@Transactional(readOnly = true)
	public FriendRequestPage requests(
			UUID actorId, UUID academyId, boolean sent, String rawCursor, Integer rawLimit) {
		requireAcademy(actorId, academyId);
		String operation = sent ? "sent" : "received";
		int limit = limit(rawLimit);
		Cursor cursor = decode(rawCursor, operation, actorId, academyId, "");
		Timestamp at = cursor == null ? null : Timestamp.from(cursor.instant());
		UUID id = cursor == null ? null : cursor.id();
		String actorColumn = sent ? "request.sender_id" : "request.receiver_id";
		String counterpartColumn = sent ? "request.receiver_id" : "request.sender_id";
		String sql = """
				SELECT request.id, counterpart.id AS counterpart_id, counterpart.nickname,
				       request.status, request.created_at, request.processed_at
				FROM friend_request request
				JOIN student counterpart ON counterpart.id = %s
				JOIN academy_membership counterpart_membership
				  ON counterpart_membership.student_id = counterpart.id
				 AND counterpart_membership.academy_id = request.academy_id
				 AND counterpart_membership.left_at IS NULL
				WHERE request.academy_id = ? AND %s = ? AND request.status = 'PENDING'
				  AND (CAST(? AS timestamptz) IS NULL OR request.created_at < ?
				       OR (request.created_at = ? AND request.id < ?))
				ORDER BY request.created_at DESC, request.id DESC
				LIMIT ?
				""".formatted(counterpartColumn, actorColumn);
		List<FriendRequestView> rows = jdbc.query(sql, (rs, row) -> new FriendRequestView(
				UUID.fromString(rs.getString("id")),
				new StudentSummary(UUID.fromString(rs.getString("counterpart_id")), rs.getString("nickname")),
				FriendRequestStatus.valueOf(rs.getString("status")),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("processed_at") == null ? null : rs.getTimestamp("processed_at").toInstant()),
				academyId, actorId, at, at, at, id, limit + 1);
		boolean more = rows.size() > limit;
		List<FriendRequestView> items = trim(rows, limit);
		String next = more ? encode(operation, actorId, academyId, "",
				items.getLast().createdAt().toString(), items.getLast().friendRequestId()) : null;
		return new FriendRequestPage(items, next);
	}

	@Transactional(readOnly = true)
	public StudentBlockPage blocks(UUID actorId, String rawCursor, Integer rawLimit) {
		int limit = limit(rawLimit);
		Cursor cursor = decode(rawCursor, "blocks", actorId, null, "");
		Timestamp at = cursor == null ? null : Timestamp.from(cursor.instant());
		UUID id = cursor == null ? null : cursor.id();
		List<StudentBlockView> rows = jdbc.query("""
				SELECT student.id, student.nickname, block.blocked_at
				FROM student_block block
				JOIN student ON student.id = block.blocked_id
				WHERE block.blocker_id = ? AND block.released_at IS NULL
				  AND (CAST(? AS timestamptz) IS NULL OR block.blocked_at < ?
				       OR (block.blocked_at = ? AND student.id < ?))
				ORDER BY block.blocked_at DESC, student.id DESC
				LIMIT ?
				""", (rs, row) -> new StudentBlockView(
				UUID.fromString(rs.getString("id")), rs.getString("nickname"),
				rs.getTimestamp("blocked_at").toInstant()),
				actorId, at, at, at, id, limit + 1);
		boolean more = rows.size() > limit;
		List<StudentBlockView> items = trim(rows, limit);
		String next = more ? encode("blocks", actorId, null, "",
				items.getLast().blockedAt().toString(), items.getLast().studentId()) : null;
		return new StudentBlockPage(items, next);
	}

	@Transactional(readOnly = true)
	public FriendRequestView project(FriendRequest request, UUID actorId) {
		UUID counterpartId = request.senderId().equals(actorId) ? request.receiverId() : request.senderId();
		Student counterpart = students.findById(counterpartId)
				.orElseThrow(() -> new IllegalStateException("Friend request counterpart is missing"));
		return new FriendRequestView(request.id(),
				new StudentSummary(counterpart.id(), counterpart.nickname()), request.status(),
				request.createdAt(), request.processedAt());
	}

	@Transactional(readOnly = true)
	public Friend project(Friendship friendship, UUID actorId) {
		UUID counterpartId = friendship.studentLowId().equals(actorId)
				? friendship.studentHighId() : friendship.studentLowId();
		Student counterpart = students.findById(counterpartId)
				.orElseThrow(() -> new IllegalStateException("Friendship counterpart is missing"));
		return new Friend(counterpart.id(), counterpart.nickname(), friendship.startedAt());
	}

	@Transactional(readOnly = true)
	public StudentBlockView project(StudentBlock block) {
		Student counterpart = students.findById(block.blockedId())
				.orElseThrow(() -> new IllegalStateException("Blocked student is missing"));
		return new StudentBlockView(counterpart.id(), counterpart.nickname(), block.blockedAt());
	}

	private StudentRelationshipPage studentPage(
			List<StudentRelationship> rows, int limit, UUID actor, UUID academy, String filter) {
		boolean more = rows.size() > limit;
		List<StudentRelationship> items = trim(rows, limit);
		String next = more ? encode("students", actor, academy, filter,
				items.getLast().nickname(), items.getLast().studentId()) : null;
		return new StudentRelationshipPage(items, next);
	}

	private void requireAcademy(UUID actorId, UUID academyId) {
		if (memberships.findByStudentIdAndAcademyIdAndLeftAtIsNull(actorId, academyId).isEmpty()) {
			throw new RelationshipException(RelationshipException.Code.ACADEMY_NOT_FOUND,
					"Academy not found.");
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
		boolean disallowed = normalized.codePoints().anyMatch(codePoint -> switch (Character.getType(codePoint)) {
			case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
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

	private static String encode(
			String operation, UUID actor, UUID academy, String filter, String boundary, UUID id) {
		String value = String.join(SEP, "friend-v1", operation, actor.toString(),
				academy == null ? "-" : academy.toString(), filter, boundary, id.toString());
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static Cursor decode(
			String raw, String operation, UUID actor, UUID academy, String filter) {
		if (raw == null) {
			return null;
		}
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
			String[] parts = decoded.split(SEP, -1);
			String expectedAcademy = academy == null ? "-" : academy.toString();
			if (parts.length != 7 || !parts[0].equals("friend-v1")
					|| !parts[1].equals(operation) || !parts[2].equals(actor.toString())
					|| !parts[3].equals(expectedAcademy) || !parts[4].equals(filter)) {
				throw new IllegalArgumentException();
			}
			return new Cursor(parts[5], UUID.fromString(parts[6]));
		} catch (RuntimeException exception) {
			throw malformed("cursor", "Cursor is malformed or bound to another request.");
		}
	}

	private static RelationshipException malformed(String field, String message) {
		return new RelationshipException(RelationshipException.Code.MALFORMED_REQUEST, message, field);
	}

	private static <T> List<T> trim(List<T> values, int limit) {
		return values.size() <= limit ? List.copyOf(values) : List.copyOf(values.subList(0, limit));
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
