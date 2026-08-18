package com.crabit.backend.wish;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ImmutableHistoryQueryRepository {

	private static final String EVENT_FACTS = """
			with event_effect_totals as (
			  select event.id,
			         event.account_id,
			         event.event_type,
			         event.account_delta,
			         event.occurred_at,
			         event.correction_of_event_id,
			         event.account_delta - coalesce(sum(effect.wish_delta), 0) as available_delta
			  from ledger_event event
			  left join ledger_wish_effect effect
			    on effect.event_id = event.id and effect.account_id = event.account_id
			  where event.account_id = :accountId
			  group by event.id, event.account_id, event.event_type, event.account_delta,
			           event.occurred_at, event.correction_of_event_id
			), current_snapshot as (
			  select coalesce((
			           select observation.actual_card_balance
			           from balance_observation observation
			           where observation.account_id = :accountId
			             and observation.status = 'SUCCEEDED'
			           order by observation.account_lookup_version desc nulls last,
			                    observation.observed_at desc, observation.id desc
			           limit 1
			         ), 0) - coalesce((
			           select sum(wish.wish_amount)
			           from wish
			           where wish.account_id = :accountId
			             and wish.deleted_at is null
			             and wish.state in ('IN_PROGRESS', 'AMOUNT_REACHED')
			         ), 0) as current_available
			), event_balances as (
			  select event_effect_totals.*,
			         current_snapshot.current_available - coalesce(sum(available_delta) over (
			           order by occurred_at desc, id desc rows between unbounded preceding and 1 preceding
			         ), 0) as available_after
			  from event_effect_totals
			  cross join current_snapshot
			)
			select event_balances.*,
			       observation.id as observation_id,
			       observation.lookup_method,
			       observation.actual_card_balance,
			       adjustment.adjustment_case_id,
			       adjustment.event_role,
			       adjustment.sequence_number
			from event_balances
			left join balance_observation observation
			  on observation.account_id = event_balances.account_id
			 and observation.balance_change_event_id = event_balances.id
			left join balance_adjustment_case_event adjustment
			  on adjustment.account_id = event_balances.account_id
			 and adjustment.event_id = event_balances.id
			where event_balances.id in (:eventIds)
			""";

	private static final String EFFECT_FACTS = """
			with effect_balances as (
			  select effect.id,
			         effect.event_id,
			         effect.account_id,
			         effect.wish_id,
			         effect.wish_purpose_snapshot,
			         effect.wish_delta,
			         event.occurred_at,
			         wish.wish_amount - coalesce(sum(effect.wish_delta) over (
			           partition by effect.wish_id
			           order by event.occurred_at desc, event.id desc
			           rows between unbounded preceding and 1 preceding
			         ), 0) as wish_amount_after
			  from ledger_wish_effect effect
			  join ledger_event event
			    on event.id = effect.event_id and event.account_id = effect.account_id
			  join wish
			    on wish.id = effect.wish_id and wish.account_id = effect.account_id
			  where effect.account_id = :accountId
			)
			select effect_balances.*,
			       wish.deleted_at
			from effect_balances
			join wish
			  on wish.id = effect_balances.wish_id
			 and wish.account_id = effect_balances.account_id
			where effect_balances.event_id in (:eventIds)
			order by effect_balances.event_id, effect_balances.wish_id
			""";

	private final NamedParameterJdbcTemplate jdbc;

	ImmutableHistoryQueryRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	List<EventKey> findPageKeys(
			UUID accountId,
			UUID wishId,
			LedgerEventType eventType,
			ImmutableHistoryCursor.Boundary cursor,
			int size) {
		StringBuilder sql = new StringBuilder("""
				select distinct event.id, event.occurred_at
				from ledger_event event
				""");
		if (wishId != null) {
			sql.append("""
					join ledger_wish_effect target_effect
					  on target_effect.event_id = event.id
					 and target_effect.account_id = event.account_id
					 and target_effect.wish_id = :wishId
					""");
		}
		sql.append("\nwhere event.account_id = :accountId\n");
		if (eventType != null) {
			sql.append("and event.event_type = :eventType\n");
		}
		if (cursor != null) {
			sql.append("""
					and (event.occurred_at < :cursorOccurredAt
					     or (event.occurred_at = :cursorOccurredAt and event.id < :cursorEventId))
					""");
		}
		sql.append("order by event.occurred_at desc, event.id desc\nlimit :size");

		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("accountId", accountId)
				.addValue("size", size);
		if (wishId != null) parameters.addValue("wishId", wishId);
		if (eventType != null) parameters.addValue("eventType", eventType.name());
		if (cursor != null) {
			parameters.addValue("cursorOccurredAt", Timestamp.from(cursor.occurredAt()));
			parameters.addValue("cursorEventId", cursor.eventId());
		}
		return jdbc.query(sql.toString(), parameters,
				(rs, rowNumber) -> new EventKey(uuid(rs, "id"), instant(rs, "occurred_at")));
	}

	Map<UUID, EventFact> findEventFacts(UUID accountId, List<UUID> eventIds) {
		if (eventIds.isEmpty()) return Map.of();
		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("accountId", accountId)
				.addValue("eventIds", eventIds);
		Map<UUID, EventFact> events = new LinkedHashMap<>();
		jdbc.query(EVENT_FACTS, parameters, rs -> {
			EventFact event = eventFact(rs);
			EventFact previous = events.putIfAbsent(event.eventId(), event);
			if (previous != null && !previous.equals(event)) {
				throw new IllegalStateException(
						"A ledger event is linked to multiple adjustment facts");
			}
		});
		return Map.copyOf(events);
	}

	Map<UUID, List<WishEffectFact>> findEffectFacts(UUID accountId, List<UUID> eventIds) {
		if (eventIds.isEmpty()) return Map.of();
		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("accountId", accountId)
				.addValue("eventIds", eventIds);
		Map<UUID, List<WishEffectFact>> effects = new LinkedHashMap<>();
		jdbc.query(EFFECT_FACTS, parameters, rs -> {
			WishEffectFact effect = effectFact(rs);
			effects.computeIfAbsent(effect.eventId(), ignored -> new ArrayList<>()).add(effect);
		});
		effects.replaceAll((eventId, values) -> List.copyOf(values));
		return Map.copyOf(effects);
	}

	private static EventFact eventFact(ResultSet rs) throws SQLException {
		UUID adjustmentCaseId = nullableUuid(rs, "adjustment_case_id");
		return new EventFact(
				uuid(rs, "id"),
				LedgerEventType.valueOf(rs.getString("event_type")),
				instant(rs, "occurred_at"),
				rs.getLong("available_delta"),
				rs.getLong("available_after"),
				nullableUuid(rs, "correction_of_event_id"),
				nullableUuid(rs, "observation_id"),
				rs.getString("lookup_method") == null
						? null : BalanceLookupMethod.valueOf(rs.getString("lookup_method")),
				nullableLong(rs, "actual_card_balance"),
				adjustmentCaseId,
				adjustmentCaseId == null
						? null : BalanceAdjustmentEventRole.valueOf(rs.getString("event_role")),
				adjustmentCaseId == null ? null : rs.getInt("sequence_number"));
	}

	private static WishEffectFact effectFact(ResultSet rs) throws SQLException {
		return new WishEffectFact(
				uuid(rs, "event_id"),
				uuid(rs, "wish_id"),
				rs.getString("wish_purpose_snapshot"),
				rs.getLong("wish_delta"),
				rs.getLong("wish_amount_after"),
				rs.getTimestamp("deleted_at") != null);
	}

	private static UUID uuid(ResultSet rs, String column) throws SQLException {
		return UUID.fromString(rs.getObject(column).toString());
	}

	private static UUID nullableUuid(ResultSet rs, String column) throws SQLException {
		Object value = rs.getObject(column);
		return value == null ? null : UUID.fromString(value.toString());
	}

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getTimestamp(column).toInstant();
	}

	record EventKey(UUID eventId, Instant occurredAt) {
	}

	record EventFact(
			UUID eventId,
			LedgerEventType eventType,
			Instant occurredAt,
			long availableDelta,
			long availableAfter,
			UUID correctionOfEventId,
			UUID observationId,
			BalanceLookupMethod lookupMethod,
			Long actualCardBalanceAfter,
			UUID adjustmentCaseId,
			BalanceAdjustmentEventRole adjustmentRole,
			Integer adjustmentSequence) {
	}

	record WishEffectFact(
			UUID eventId,
			UUID wishId,
			String purposeSnapshot,
			long delta,
			long amountAfter,
			boolean deleted) {
	}
}
