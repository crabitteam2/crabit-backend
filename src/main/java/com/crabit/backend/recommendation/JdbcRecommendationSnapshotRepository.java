package com.crabit.backend.recommendation;

import com.crabit.backend.wish.SharedCardQueryRepository;
import com.crabit.backend.wish.WishState;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(
		name = "crabit.recommendation.handoff.enabled", havingValue = "true")
final class JdbcRecommendationSnapshotRepository implements RecommendationSnapshotRepository {

	private final JdbcTemplate jdbc;
	private final SharedCardQueryRepository sharedCards;

	JdbcRecommendationSnapshotRepository(
			JdbcTemplate jdbc, SharedCardQueryRepository sharedCards) {
		this.jdbc = jdbc;
		this.sharedCards = sharedCards;
	}

	@Override
	public java.time.Instant transactionTimestamp() {
		Timestamp timestamp = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
		if (timestamp == null) {
			throw RecommendationHandoffException.incomplete();
		}
		return timestamp.toInstant();
	}

	@Override
	public Optional<AccountRow> findActiveAccount(UUID accountId) {
		List<AccountRow> rows = jdbc.query("""
				SELECT account.id AS account_id,
				       account.student_id,
				       account.academy_id,
				       account.opened_at,
				       student.nickname AS student_name,
				       student.age AS student_age,
				       academy.name AS academy_name
				FROM card_balance_account account
				JOIN student ON student.id = account.student_id
				JOIN academy ON academy.id = account.academy_id
				WHERE account.id = ?
				  AND account.closed_at IS NULL
				""", JdbcRecommendationSnapshotRepository::mapAccount, accountId);
		return rows.stream().findFirst();
	}

	@Override
	public List<WishRow> findViewerWishes(UUID accountId, int requestedRows) {
		return jdbc.query("""
				SELECT wish.id AS wish_id,
				       wish.account_id,
				       wish.academy_id,
				       wish.purpose,
				       wish.target_amount,
				       wish.wish_amount,
				       wish.state,
				       wish.target_date,
				       wish.created_at,
				       wish.completed_at,
				       wish.abandoned_at,
				       CASE WHEN representative.wish_id = wish.id THEN TRUE ELSE FALSE END
				           AS is_representative
				FROM wish
				LEFT JOIN representative_wish_selection representative
				  ON representative.account_id = wish.account_id
				WHERE wish.account_id = ?
				  AND wish.deleted_at IS NULL
				ORDER BY wish.created_at DESC, wish.id DESC
				LIMIT ?
				""", JdbcRecommendationSnapshotRepository::mapWish,
				accountId, requestedRows);
	}

	@Override
	public List<SharedCardQueryRepository.Row> findCandidates(
			UUID viewerId, UUID academyId, int requestedRows) {
		return sharedCards.findVisiblePage(viewerId, academyId, null, requestedRows);
	}

	@Override
	public Map<UUID, SavingsRow> summarizeSavings(Collection<UUID> wishIds) {
		if (wishIds.isEmpty()) {
			return Map.of();
		}
		List<UUID> ids = List.copyOf(wishIds);
		String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
		String sql = """
				SELECT effect.wish_id,
				       count(*) AS transaction_count,
				       sum(CASE WHEN effect.wish_delta > 0
				                THEN effect.wish_delta ELSE 0 END) AS total_inflow_amount,
				       -sum(CASE WHEN effect.wish_delta < 0
				                 THEN effect.wish_delta ELSE 0 END) AS total_outflow_amount,
				       max(event.occurred_at) AS last_transaction_at
				FROM ledger_wish_effect effect
				JOIN ledger_event event
				  ON event.id = effect.event_id
				 AND event.account_id = effect.account_id
				WHERE effect.wish_id IN (""" + placeholders + ") " + """
				GROUP BY effect.wish_id
				""";
		List<Object> arguments = new ArrayList<>(ids);
		Map<UUID, SavingsRow> summaries = new LinkedHashMap<>();
		jdbc.query(sql, result -> {
			UUID wishId = result.getObject("wish_id", UUID.class);
			SavingsRow previous = summaries.put(wishId, new SavingsRow(
					exactLong(result, "transaction_count"),
					exactLong(result, "total_inflow_amount"),
					exactLong(result, "total_outflow_amount"),
					instant(result, "last_transaction_at")));
			if (previous != null) {
				throw RecommendationHandoffException.incomplete();
			}
		}, arguments.toArray());
		return Map.copyOf(summaries);
	}

	private static AccountRow mapAccount(ResultSet result, int rowNumber) throws SQLException {
		return new AccountRow(
				result.getObject("account_id", UUID.class),
				result.getObject("student_id", UUID.class),
				result.getObject("academy_id", UUID.class),
				result.getTimestamp("opened_at").toInstant(),
				result.getString("student_name"),
				result.getInt("student_age"),
				result.getString("academy_name"));
	}

	private static WishRow mapWish(ResultSet result, int rowNumber) throws SQLException {
		return new WishRow(
				result.getObject("wish_id", UUID.class),
				result.getObject("account_id", UUID.class),
				result.getObject("academy_id", UUID.class),
				result.getString("purpose"),
				result.getLong("target_amount"),
				result.getLong("wish_amount"),
				WishState.valueOf(result.getString("state")),
				result.getObject("target_date", java.time.LocalDate.class),
				result.getTimestamp("created_at").toInstant(),
				instant(result, "completed_at"),
				instant(result, "abandoned_at"),
				result.getBoolean("is_representative"));
	}

	private static java.time.Instant instant(ResultSet result, String column)
			throws SQLException {
		Timestamp timestamp = result.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static long exactLong(ResultSet result, String column) throws SQLException {
		BigDecimal value = result.getBigDecimal(column);
		if (value == null) {
			throw RecommendationHandoffException.incomplete();
		}
		try {
			return value.longValueExact();
		}
		catch (ArithmeticException exception) {
			throw RecommendationHandoffException.incomplete();
		}
	}
}
