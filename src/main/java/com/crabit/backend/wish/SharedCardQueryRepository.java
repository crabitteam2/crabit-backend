package com.crabit.backend.wish;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SharedCardQueryRepository {

	private static final String SELECT = """
			SELECT card.id AS shared_card_id,
			       card.kind,
			       owner.nickname AS owner_nickname,
			       wish.purpose,
			       wish.target_amount,
			       wish.wish_amount,
			       wish.state,
			       wish.target_date,
			       wish.created_at,
			       wish.completed_at,
			       card.updated_at AS content_updated_at,
			       EXISTS (
			           SELECT 1 FROM balance_adjustment_case adjustment
			           WHERE adjustment.account_id = wish.account_id
			             AND adjustment.status = 'OPEN'
			       ) AS balance_adjustment_in_progress
			FROM shared_card card
			JOIN wish ON wish.id = card.wish_id
			JOIN card_balance_account account ON account.id = wish.account_id
			JOIN student owner ON owner.id = account.student_id
			JOIN academy_membership owner_membership
			  ON owner_membership.student_id = account.student_id
			 AND owner_membership.academy_id = wish.academy_id
			 AND owner_membership.left_at IS NULL
			WHERE wish.academy_id = ?
			  AND wish.deleted_at IS NULL
			  AND wish.state <> 'ABANDONED'
			  AND account.closed_at IS NULL
			  AND card.visibility IN ('FRIENDS', 'ACADEMY')
			""";

	private static final String NON_OWNER_VISIBILITY = """
			  AND account.student_id <> ?
			  AND NOT EXISTS (
			      SELECT 1 FROM student_block block
			      WHERE block.released_at IS NULL
			        AND ((block.blocker_id = account.student_id AND block.blocked_id = ?)
			          OR (block.blocker_id = ? AND block.blocked_id = account.student_id))
			  )
			  AND (card.visibility = 'ACADEMY' OR EXISTS (
			      SELECT 1 FROM friendship friendship
			      WHERE friendship.academy_id = wish.academy_id
			        AND friendship.ended_at IS NULL
			        AND ((friendship.student_low_id = account.student_id
			              AND friendship.student_high_id = ?)
			          OR (friendship.student_low_id = ?
			              AND friendship.student_high_id = account.student_id))
			  ))
			""";

	private static final RowMapper<Row> ROW_MAPPER = SharedCardQueryRepository::map;

	private final JdbcTemplate jdbc;

	public SharedCardQueryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Row> findVisiblePage(
			UUID viewerId,
			UUID academyId,
			CursorBoundary cursor,
			int requestedRows) {
		String cursorClause = cursor == null ? "" : """
				  AND (card.updated_at < ? OR (card.updated_at = ? AND card.id < ?))
				""";
		String sql = SELECT + NON_OWNER_VISIBILITY + cursorClause
				+ " ORDER BY card.updated_at DESC, card.id DESC LIMIT ?";
		if (cursor == null) {
			return jdbc.query(sql, ROW_MAPPER,
					academyId, viewerId, viewerId, viewerId, viewerId, viewerId, requestedRows);
		}
		return jdbc.query(sql, ROW_MAPPER,
				academyId, viewerId, viewerId, viewerId, viewerId, viewerId,
				Timestamp.from(cursor.contentUpdatedAt()), Timestamp.from(cursor.contentUpdatedAt()),
				cursor.sharedCardId(), requestedRows);
	}

	public Optional<Row> findVisibleDetail(UUID viewerId, UUID academyId, UUID cardId) {
		String sql = SELECT + """
				  AND card.id = ?
				  AND (account.student_id = ? OR (
				      NOT EXISTS (
				          SELECT 1 FROM student_block block
				          WHERE block.released_at IS NULL
				            AND ((block.blocker_id = account.student_id AND block.blocked_id = ?)
				              OR (block.blocker_id = ? AND block.blocked_id = account.student_id))
				      )
				      AND (card.visibility = 'ACADEMY' OR EXISTS (
				          SELECT 1 FROM friendship friendship
				          WHERE friendship.academy_id = wish.academy_id
				            AND friendship.ended_at IS NULL
				            AND ((friendship.student_low_id = account.student_id
				                  AND friendship.student_high_id = ?)
				              OR (friendship.student_low_id = ?
				                  AND friendship.student_high_id = account.student_id))
				      ))
				  ))
				""";
		List<Row> rows = jdbc.query(sql, ROW_MAPPER,
				academyId, cardId, viewerId, viewerId, viewerId, viewerId, viewerId);
		return rows.stream().findFirst();
	}

	private static Row map(ResultSet result, int rowNumber) throws SQLException {
		Timestamp completedAt = result.getTimestamp("completed_at");
		return new Row(
				result.getObject("shared_card_id", UUID.class),
				SharedCardKind.valueOf(result.getString("kind")),
				result.getString("owner_nickname"),
				result.getString("purpose"),
				result.getLong("target_amount"),
				result.getLong("wish_amount"),
				WishState.valueOf(result.getString("state")),
				result.getObject("target_date", LocalDate.class),
				result.getTimestamp("created_at").toInstant(),
				completedAt == null ? null : completedAt.toInstant(),
				result.getTimestamp("content_updated_at").toInstant(),
				result.getBoolean("balance_adjustment_in_progress"));
	}

	public record CursorBoundary(Instant contentUpdatedAt, UUID sharedCardId) {
	}

	public record Row(
			UUID sharedCardId,
			SharedCardKind kind,
			String ownerNickname,
			String purpose,
			long targetAmount,
			long wishAmount,
			WishState state,
			LocalDate targetDate,
			Instant createdAt,
			Instant completedAt,
			Instant contentUpdatedAt,
			boolean balanceAdjustmentInProgress) {
	}
}
