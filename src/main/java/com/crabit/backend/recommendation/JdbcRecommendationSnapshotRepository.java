package com.crabit.backend.recommendation;

import com.crabit.backend.wish.SharedCardQueryRepository;
import com.crabit.backend.wish.WishState;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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

@Repository
@ConditionalOnProperty(name = "crabit.recommendation.handoff.enabled", havingValue = "true")
class JdbcRecommendationSnapshotRepository implements RecommendationSnapshotRepository {

	private final JdbcTemplate jdbc;
	private final SharedCardQueryRepository sharedCards;

	JdbcRecommendationSnapshotRepository(JdbcTemplate jdbc, SharedCardQueryRepository sharedCards) {
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
		List<AccountRow> rows =
				jdbc.query(
						"""
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
						  AND EXISTS (
							  SELECT 1
							  FROM academy_membership viewer_membership
							  WHERE viewer_membership.student_id = account.student_id
								AND viewer_membership.academy_id = account.academy_id
								AND viewer_membership.left_at IS NULL
						  )
						""",
						JdbcRecommendationSnapshotRepository::mapAccount,
						accountId);
		return rows.stream().findFirst();
	}

	@Override
	public List<WishRow> findViewerWishes(UUID accountId, int requestedRows) {
		return jdbc.query(
				"""
				SELECT wish.id AS wish_id,
					   wish.account_id,
					   wish.academy_id,
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
					   CASE WHEN representative.wish_id = wish.id THEN TRUE ELSE FALSE END
						   AS is_representative
				FROM wish
				LEFT JOIN representative_wish_selection representative
				  ON representative.account_id = wish.account_id
				WHERE wish.account_id = ?
				  AND wish.deleted_at IS NULL
				ORDER BY is_representative DESC, wish.created_at DESC, wish.id DESC
				LIMIT ?
				""",
				JdbcRecommendationSnapshotRepository::mapWish,
				accountId,
				requestedRows);
	}

	@Override
	public List<SharedCardQueryRepository.Row> findCandidates(
			UUID viewerId, UUID academyId, int requestedRows) {
		return sharedCards.findVisibleRecommendationCandidates(viewerId, academyId, requestedRows);
	}

	@Override
	public Map<UUID, SavingsRow> summarizeSavings(Collection<UUID> wishIds) {
		if (wishIds.isEmpty()) {
			return Map.of();
		}
		List<UUID> ids = List.copyOf(wishIds);
		String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
		String sql =
				"""
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
				WHERE effect.wish_id IN (\
				"""
						+ placeholders
						+ ") "
						+ """
						GROUP BY effect.wish_id
						""";
		List<Object> arguments = new ArrayList<>(ids);
		Map<UUID, SavingsRow> summaries = new LinkedHashMap<>();
		jdbc.query(
				sql,
				result -> {
					UUID wishId = result.getObject("wish_id", UUID.class);
					SavingsRow previous =
							summaries.put(
									wishId,
									new SavingsRow(
											exactLong(result, "transaction_count"),
											exactLong(result, "total_inflow_amount"),
											exactLong(result, "total_outflow_amount"),
											instant(result, "last_transaction_at")));
					if (previous != null) {
						throw RecommendationHandoffException.incomplete();
					}
				},
				arguments.toArray());
		return Map.copyOf(summaries);
	}

	@Override
	public void validateRepresentative(UUID accountId) {
		Integer invalid =
				jdbc.queryForObject(
						"""
						SELECT count(*) FROM card_balance_account account
						LEFT JOIN representative_wish_selection selection ON selection.account_id=account.id
						LEFT JOIN wish selected ON selected.id=selection.wish_id
						WHERE account.id=? AND (
						 (selection.wish_id IS NOT NULL AND (selected.id IS NULL OR selected.account_id<>account.id OR selected.deleted_at IS NOT NULL OR selected.state NOT IN ('IN_PROGRESS','AMOUNT_REACHED')))
						 OR (selection.wish_id IS NULL AND (SELECT count(*) FROM wish active WHERE active.account_id=account.id AND active.deleted_at IS NULL AND active.state IN ('IN_PROGRESS','AMOUNT_REACHED'))=1))
						""",
						Integer.class,
						accountId);
		if (invalid == null || invalid != 0) throw RecommendationHandoffException.incomplete();
	}

	@Override
	public List<SharedCardQueryRepository.Row> findCompletedCandidates(
			UUID viewerId,
			UUID academyId,
			java.time.Instant start,
			java.time.Instant end,
			int limit) {
		return sharedCards.findVisibleCompleted(viewerId, academyId, start, end, limit);
	}

	@Override
	public List<SharedCardQueryRepository.Row> findInterestCandidates(
			UUID viewerId, UUID academyId, Collection<UUID> ids, int limit) {
		return sharedCards.findVisibleWishIds(viewerId, academyId, ids, limit);
	}

	@Override
	public Map<UUID, String> findOwnTitles(UUID accountId, Collection<UUID> ids) {
		if (ids.isEmpty()) return Map.of();
		var args = new ArrayList<Object>();
		args.add(accountId);
		args.addAll(ids);
		Map<UUID, String> titles = new LinkedHashMap<>();
		jdbc.query(
				"SELECT id,purpose FROM wish WHERE account_id=? AND deleted_at IS NULL AND id IN ("
						+ String.join(",", Collections.nCopies(ids.size(), "?"))
						+ ")",
				r -> {
					titles.put(r.getObject("id", UUID.class), r.getString("purpose"));
				},
				args.toArray());
		return Map.copyOf(titles);
	}

	@Override
	public Map<UUID, String> findVisibleTitles(
			UUID viewerId, UUID academyId, Collection<UUID> ids) {
		var result = new LinkedHashMap<UUID, String>();
		for (var row : sharedCards.findVisibleWishIds(viewerId, academyId, ids, 500))
			result.put(row.wishId(), row.purpose());
		return Map.copyOf(result);
	}

	@Override
	public List<RecommendationPeriodSavings.Row> periodSavings(
			UUID accountId, java.time.Instant start, java.time.Instant end) {
		if (!start.isBefore(end)) return List.of();
		String sql =
				"""
				WITH facts AS (
				 SELECT event.id,event.event_type,event.correction_of_event_id,
				   (event.occurred_at AT TIME ZONE 'Asia/Seoul')::date AS day,
				   count(effect.id) AS effect_count,count(DISTINCT effect.wish_id) AS wish_count,
				   coalesce(sum(CASE WHEN effect.wish_delta>0 THEN effect.wish_delta::numeric ELSE 0 END),0) AS positive,
				   coalesce(sum(CASE WHEN effect.wish_delta<0 THEN -effect.wish_delta::numeric ELSE 0 END),0) AS negative
				 FROM ledger_event event LEFT JOIN ledger_wish_effect effect ON effect.event_id=event.id AND effect.account_id=event.account_id
				 WHERE event.account_id=? AND event.occurred_at>=? AND event.occurred_at<? AND event.event_type<>'CARD_BALANCE_CHANGE'
				 GROUP BY event.id,event.event_type,event.correction_of_event_id,event.occurred_at
				)
				SELECT day,event_type,correction_of_event_id IS NOT NULL AS correction,count(*) AS event_count,sum(positive) AS positive,sum(negative) AS negative,
				 bool_or(CASE WHEN correction_of_event_id IS NOT NULL THEN effect_count=0
				   WHEN event_type='WISH_TRANSFER' THEN effect_count<>2 OR wish_count<>2 OR positive<=0 OR positive<>negative
				   WHEN event_type='WISH_DEPOSIT' THEN effect_count<>1 OR positive<=0 OR negative<>0
				   ELSE effect_count<>1 OR negative<=0 OR positive<>0 END) AS invalid
				FROM facts GROUP BY day,event_type,correction_of_event_id IS NOT NULL
				UNION ALL
				SELECT (abandoned_at AT TIME ZONE 'Asia/Seoul')::date,'ABANDONMENT_FACT',false,count(*),0,0,false
				FROM wish WHERE account_id=? AND state='ABANDONED' AND abandoned_at>=? AND abandoned_at<? GROUP BY (abandoned_at AT TIME ZONE 'Asia/Seoul')::date
				""";
		return jdbc.query(
				sql,
				(r, n) ->
						new RecommendationPeriodSavings.Row(
								r.getObject("day", java.time.LocalDate.class),
								r.getString("event_type"),
								r.getBoolean("correction"),
								exactLong(r, "event_count"),
								exactLong(r, "positive"),
								exactLong(r, "negative"),
								r.getBoolean("invalid")),
				accountId,
				Timestamp.from(start),
				Timestamp.from(end),
				accountId,
				Timestamp.from(start),
				Timestamp.from(end));
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
				result.getObject("abandonment_amount", Long.class),
				WishState.valueOf(result.getString("state")),
				result.getObject("start_date", java.time.LocalDate.class),
				result.getObject("target_date", java.time.LocalDate.class),
				result.getTimestamp("created_at").toInstant(),
				instant(result, "completed_at"),
				instant(result, "abandoned_at"),
				result.getBoolean("is_representative"));
	}

	private static java.time.Instant instant(ResultSet result, String column) throws SQLException {
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
		} catch (ArithmeticException exception) {
			throw RecommendationHandoffException.incomplete();
		}
	}
}
