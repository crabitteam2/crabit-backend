package com.crabit.backend.wish;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SharedCardQueryRepository {

	private static final String SELECT =
			"""
			SELECT card.id AS shared_card_id,
				   card.kind,
				   owner.id AS owner_id,
				   owner.nickname AS owner_nickname,
				   owner.age AS owner_age,
				   account.id AS account_id,
				   account.academy_id,
				   account.opened_at AS account_opened_at,
				   account.closed_at AS account_closed_at,
				   wish.id AS wish_id,
				   wish.purpose,
			   wish.target_amount,
			   wish.wish_amount,
			   wish.abandonment_amount,
			   wish.state,
				   wish.start_date,
				   wish.target_date,
				   wish.created_at,
				   wish.completed_at,
				   wish.abandoned_at,
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
			  AND account.closed_at IS NULL
			  AND card.visibility IN ('FOLLOWERS', 'ACADEMY')
			""";

	private static final String NON_OWNER_VISIBILITY =
			"""
			  AND account.student_id <> ?
			  AND NOT EXISTS (
				  SELECT 1 FROM student_block block
				  WHERE block.released_at IS NULL
					AND ((block.blocker_id = account.student_id AND block.blocked_id = ?)
					  OR (block.blocker_id = ? AND block.blocked_id = account.student_id))
			  )
			  AND (card.visibility = 'ACADEMY' OR EXISTS (
				  SELECT 1 FROM student_follow student_follow
				  WHERE student_follow.academy_id = wish.academy_id
					AND student_follow.ended_at IS NULL
					AND student_follow.source_id = ? AND student_follow.target_id = account.student_id
			  ))
			""";

	private static final RowMapper<Row> ROW_MAPPER = SharedCardQueryRepository::map;

	private final JdbcTemplate jdbc;

	public SharedCardQueryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Row> findVisiblePage(UUID viewerId, UUID academyId, CursorBoundary cursor, int requestedRows) {
        return findVisiblePage(viewerId, academyId, null, cursor, requestedRows);
    }

    public List<Row> findVisiblePage(UUID viewerId, UUID academyId, UUID ownerId,
            CursorBoundary cursor, int requestedRows) {
        var args = new java.util.ArrayList<Object>();
        args.add(academyId);
        String sql = SELECT;
        if (ownerId != null) {
            sql += " AND account.student_id = ? ";
            args.add(ownerId);
        }
        if (!viewerId.equals(ownerId)) {
            sql += NON_OWNER_VISIBILITY;
            args.addAll(List.of(viewerId, viewerId, viewerId, viewerId));
        }
        if (cursor != null) {
            sql += " AND (card.updated_at < ? OR (card.updated_at = ? AND card.id < ?)) ";
            args.add(Timestamp.from(cursor.contentUpdatedAt()));
            args.add(Timestamp.from(cursor.contentUpdatedAt()));
            args.add(cursor.sharedCardId());
        }
        sql += " ORDER BY card.updated_at DESC, card.id DESC LIMIT ?";
        args.add(requestedRows);
        return jdbc.query(sql, ROW_MAPPER, args.toArray());
    }

	public Optional<Row> findVisibleDetail(UUID viewerId, UUID academyId, UUID cardId) {
		String sql =
				SELECT
						+ """
						  AND card.id = ?
						  AND (account.student_id = ? OR (
							  NOT EXISTS (
								  SELECT 1 FROM student_block block
								  WHERE block.released_at IS NULL
									AND ((block.blocker_id = account.student_id AND block.blocked_id = ?)
									  OR (block.blocker_id = ? AND block.blocked_id = account.student_id))
							  )
							  AND (card.visibility = 'ACADEMY' OR EXISTS (
								  SELECT 1 FROM student_follow student_follow
								  WHERE student_follow.academy_id = wish.academy_id
									AND student_follow.ended_at IS NULL
									AND student_follow.source_id = ? AND student_follow.target_id = account.student_id
							  ))
						  ))
						""";
		List<Row> rows =
				jdbc.query(
						sql, ROW_MAPPER, academyId, cardId, viewerId, viewerId, viewerId, viewerId);
		return rows.stream().findFirst();
	}

	public List<Row> findVisibleCompleted(
			UUID viewer, UUID academy, Instant start, Instant end, int limit) {
		return jdbc.query(
				SELECT
						+ NON_OWNER_VISIBILITY
						+ " AND wish.state='COMPLETED' AND wish.completed_at>=? AND"
						+ " wish.completed_at<? ORDER BY wish.completed_at DESC,card.id DESC LIMIT"
						+ " ?",
				ROW_MAPPER,
				academy,
				viewer,
				viewer,
				viewer,
				viewer,
				Timestamp.from(start),
				Timestamp.from(end),
				limit);
	}

	public List<Row> findVisibleRecommendationCandidates(
			UUID viewer, UUID academy, int limit) {
		return jdbc.query(
				SELECT
						+ NON_OWNER_VISIBILITY
						+ " AND wish.state <> 'ABANDONED' ORDER BY card.updated_at DESC,card.id DESC LIMIT ?",
				ROW_MAPPER,
				academy,
				viewer,
				viewer,
				viewer,
				viewer,
				limit);
	}

	public List<Row> findVisibleWishIds(
			UUID viewer, UUID academy, java.util.Collection<UUID> ids, int limit) {
		if (ids.isEmpty()) return List.of();
		var args = new java.util.ArrayList<Object>();
		args.addAll(List.of(academy, viewer, viewer, viewer, viewer));
		args.addAll(ids);
		args.add(limit);
		String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
		return jdbc.query(
				SELECT
						+ NON_OWNER_VISIBILITY
						+ " AND wish.state <> 'ABANDONED' AND wish.id IN ("
						+ placeholders
						+ ") ORDER BY card.updated_at DESC,card.id DESC LIMIT ?",
				ROW_MAPPER,
				args.toArray());
	}

	private static Row map(ResultSet result, int rowNumber) throws SQLException {
		Timestamp completedAt = result.getTimestamp("completed_at");
		Timestamp abandonedAt = result.getTimestamp("abandoned_at");
		Timestamp accountClosedAt = result.getTimestamp("account_closed_at");
		return new Row(
				result.getObject("shared_card_id", UUID.class),
				SharedCardKind.valueOf(result.getString("kind")),
				result.getObject("owner_id", UUID.class),
				result.getString("owner_nickname"),
				result.getInt("owner_age"),
				result.getObject("account_id", UUID.class),
				result.getObject("academy_id", UUID.class),
				result.getTimestamp("account_opened_at").toInstant(),
				accountClosedAt == null ? null : accountClosedAt.toInstant(),
				result.getObject("wish_id", UUID.class),
				result.getString("purpose"),
				result.getLong("target_amount"),
				result.getLong("wish_amount"),
				result.getObject("abandonment_amount", Long.class),
				WishState.valueOf(result.getString("state")),
				result.getObject("start_date", LocalDate.class),
				result.getObject("target_date", LocalDate.class),
				result.getTimestamp("created_at").toInstant(),
				completedAt == null ? null : completedAt.toInstant(),
				abandonedAt == null ? null : abandonedAt.toInstant(),
				result.getTimestamp("content_updated_at").toInstant(),
				result.getBoolean("balance_adjustment_in_progress"));
	}

	public record CursorBoundary(Instant contentUpdatedAt, UUID sharedCardId) {}

	public record Row(
			UUID sharedCardId,
			SharedCardKind kind,
			UUID ownerId,
			String ownerNickname,
			int ownerAge,
			UUID accountId,
			UUID academyId,
			Instant accountOpenedAt,
			Instant accountClosedAt,
			UUID wishId,
			String purpose,
			long targetAmount,
			long wishAmount,
			Long abandonmentAmount,
			WishState state,
			LocalDate startDate,
			LocalDate targetDate,
			Instant createdAt,
			Instant completedAt,
			Instant abandonedAt,
			Instant contentUpdatedAt,
			boolean balanceAdjustmentInProgress) {

		public Row(
				UUID sharedCardId,
				SharedCardKind kind,
				UUID ownerId,
				String ownerNickname,
				int ownerAge,
				UUID accountId,
				UUID academyId,
				Instant accountOpenedAt,
				Instant accountClosedAt,
				UUID wishId,
				String purpose,
				long targetAmount,
				long wishAmount,
				WishState state,
				LocalDate startDate,
				LocalDate targetDate,
				Instant createdAt,
				Instant completedAt,
				Instant abandonedAt,
				Instant contentUpdatedAt,
				boolean balanceAdjustmentInProgress) {
			this(sharedCardId, kind, ownerId, ownerNickname, ownerAge, accountId, academyId,
					accountOpenedAt, accountClosedAt, wishId, purpose, targetAmount, wishAmount,
					null, state, startDate, targetDate, createdAt, completedAt, abandonedAt,
					contentUpdatedAt, balanceAdjustmentInProgress);
		}
	}
}
