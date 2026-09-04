package com.crabit.backend.recap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Builds the immutable, privacy-reduced request sent to the recap service. */
@Service
public class RecapSnapshotService {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public RecapSnapshotService(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Snapshot build(UUID accountId, RecapKind kind, RecapPeriods.Period period) {
		Instant snapshotAt = jdbc.queryForObject("select current_timestamp", Timestamp.class).toInstant();
		Account account = jdbc.query(
				"""
				select a.student_id,a.academy_id,s.age,s.age_provenance
				from card_balance_account a join student s on s.id=a.student_id
				where a.id=? and a.closed_at is null and exists (
				 select 1 from academy_membership m where m.student_id=a.student_id
				 and m.academy_id=a.academy_id and m.left_at is null)
				""",
				(rs, n) -> new Account(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
						rs.getInt(3), rs.getString(4)), accountId).stream().findFirst()
				.orElseThrow(() -> new IllegalStateException("Active recap account disappeared"));
		List<EffectiveTransaction> all = effectiveTransactions(accountId, snapshotAt);
		Instant periodStart = period.start().atStartOfDay(SEOUL).toInstant();
		Instant periodEnd = period.endExclusive().atStartOfDay(SEOUL).toInstant();
		LocalDate historyStart = period.start().minusWeeks(52);

		UUID representative = representative(accountId);
		List<Map<String, Object>> wishes = wishes(accountId, representative, all, periodEnd);
		List<Map<String, Object>> transactions = new ArrayList<>();
		for (EffectiveTransaction tx : all) {
			if (!tx.occurredAt().isBefore(periodEnd)
					|| tx.occurredAt().isBefore(historyStart.atStartOfDay(SEOUL).toInstant())) continue;
			transactions.add(tx.asInput());
		}

		Map<String, Object> input = new LinkedHashMap<>();
		input.put("representative_wish_id", representative);
		input.put("wishes", wishes);
		input.put("effective_transactions", transactions);
		input.put("visit_metrics", visitMetrics(account, period, snapshotAt));
		input.put("peer_metrics", peerMetrics(account, period, snapshotAt));
		input.put("success_story_candidates", successStories(account, periodStart, periodEnd));

		Map<String, Object> digestable = new LinkedHashMap<>();
		digestable.put("schema_version", 1);
		digestable.put("algorithm_version", "recap-1");
		digestable.put("student_id", account.studentId());
		digestable.put("card_balance_account_id", accountId);
		digestable.put("academy_id", account.academyId());
		digestable.put("kind", kind.name());
		digestable.put("period", period(period));
		digestable.put("reference_date", period.endExclusive().minusDays(1));
		digestable.put("snapshot_at", snapshotAt);
		digestable.put("input", input);
		String inputDigest = digest(digestable);
		UUID generationId = UUID.randomUUID();
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("schema_version", 1);
		request.put("algorithm_version", "recap-1");
		request.put("generation_id", generationId);
		request.put("input_digest", inputDigest);
		request.putAll(digestable.entrySet().stream()
				.filter(e -> !e.getKey().equals("schema_version") && !e.getKey().equals("algorithm_version"))
				.collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll));
		try {
			return new Snapshot(generationId, account.studentId(), account.academyId(), inputDigest,
					json.writeValueAsString(request), depositCount(all, periodStart, periodEnd));
		} catch (JacksonException e) {
			throw new IllegalStateException("Recap snapshot cannot be serialized", e);
		}
	}

	private List<EffectiveTransaction> effectiveTransactions(UUID accountId, Instant snapshotAt) {
		Map<UUID, Event> events = new LinkedHashMap<>();
		jdbc.query("""
			select e.id,e.event_type,e.account_delta,e.occurred_at,e.correction_of_event_id,
			       x.wish_id,x.wish_delta
			from ledger_event e left join ledger_wish_effect x
			  on x.event_id=e.id and x.account_id=e.account_id
			where e.account_id=? and e.occurred_at<=?
			order by e.occurred_at,e.id,x.wish_id
			""", rs -> {
			UUID id = rs.getObject("id", UUID.class);
			Event event = events.get(id);
			if (event == null) {
				event = new Event(id, rs.getString("event_type"), rs.getLong("account_delta"),
						rs.getTimestamp("occurred_at").toInstant(),
						rs.getObject("correction_of_event_id", UUID.class), new LinkedHashMap<>());
				events.put(id, event);
			}
			UUID wish = rs.getObject("wish_id", UUID.class);
			if (wish != null && event.effects().put(wish, rs.getLong("wish_delta")) != null)
				throw new IllegalStateException("Duplicate recap ledger effect");
		}, accountId, Timestamp.from(snapshotAt));
		Map<UUID, Integer> children = new HashMap<>();
		for (Event event : events.values()) if (event.parent() != null) {
			if (!events.containsKey(event.parent())) throw new IllegalStateException("Missing recap correction parent");
			if (children.merge(event.parent(), 1, Integer::sum) > 1) throw new IllegalStateException("Branched recap correction chain");
		}
		Map<UUID, List<Event>> chains = new LinkedHashMap<>();
		for (Event event : events.values()) {
			Event cursor = event; Set<UUID> seen = new HashSet<>();
			while (cursor.parent() != null) {
				if (!seen.add(cursor.id())) throw new IllegalStateException("Cyclic recap correction chain");
				cursor = events.get(cursor.parent());
			}
			chains.computeIfAbsent(cursor.id(), ignored -> new ArrayList<>()).add(event);
		}
		List<EffectiveTransaction> result = new ArrayList<>();
		for (List<Event> chain : chains.values()) {
			chain.sort(Comparator.comparing(Event::occurredAt).thenComparing(Event::id));
			Event root = chain.getFirst(); long accountDelta = 0; Map<UUID, Long> effects = new LinkedHashMap<>();
			for (Event event : chain) {
				accountDelta = Math.addExact(accountDelta, event.accountDelta());
				event.effects().forEach((wish, delta) -> effects.merge(wish, delta, Math::addExact));
			}
			List<Map.Entry<UUID, Long>> nonzero = effects.entrySet().stream().filter(e -> e.getValue() != 0)
					.sorted(Map.Entry.comparingByKey()).toList();
			if (nonzero.isEmpty()) continue;
			if (root.type().equals("WISH_TRANSFER")) {
				if (accountDelta != 0 || nonzero.size() != 2 || nonzero.stream().mapToLong(Map.Entry::getValue).sum() != 0)
					throw new IllegalStateException("Ambiguous recap transfer chain");
			} else if (nonzero.size() != 1) throw new IllegalStateException("Ambiguous recap ledger chain");
			for (var effect : nonzero) {
				String type = classify(root.type(), effect.getValue());
				result.add(new EffectiveTransaction(root.id(), effect.getKey(), root.occurredAt(),
						Math.abs(effect.getValue()), type));
			}
		}
		result.sort(Comparator.comparing(EffectiveTransaction::occurredAt)
				.thenComparing(EffectiveTransaction::rootEventId).thenComparing(EffectiveTransaction::wishId));
		return List.copyOf(result);
	}

	private static String classify(String rootType, long wishDelta) {
		if (rootType.equals("WISH_TRANSFER")) return wishDelta > 0 ? "TRANSFER_IN" : "TRANSFER_OUT";
		return switch (rootType) {
			case "WISH_DEPOSIT" -> wishDelta > 0 ? "DEPOSIT" : "WITHDRAWAL";
			case "WISH_WITHDRAWAL" -> wishDelta < 0 ? "WITHDRAWAL" : "DEPOSIT";
			case "WISH_COMPLETION_RETURN" -> "COMPLETION_RETURN";
			case "WISH_ABANDONMENT_RETURN" -> "ABANDONMENT_RETURN";
			case "WISH_DELETION_RETURN" -> "DELETION_RETURN";
			default -> throw new IllegalStateException("Ambiguous recap ledger type");
		};
	}

	private UUID representative(UUID accountId) {
		List<UUID> explicit = jdbc.query("select wish_id from representative_wish_selection where account_id=?",
				(rs, n) -> rs.getObject(1, UUID.class), accountId);
		if (!explicit.isEmpty()) return explicit.getFirst();
		return jdbc.query("""
			select id from wish where account_id=? and deleted_at is null
			and state in ('IN_PROGRESS','AMOUNT_REACHED') order by created_at,id limit 1
			""", (rs, n) -> rs.getObject(1, UUID.class), accountId).stream().findFirst().orElse(null);
	}

	private List<Map<String, Object>> wishes(UUID accountId, UUID representative,
			List<EffectiveTransaction> all, Instant periodEnd) {
		Map<UUID, Long> saved = new HashMap<>();
		for (var tx : all) if (tx.occurredAt().isBefore(periodEnd)) {
			long signed = switch (tx.type()) {
				case "DEPOSIT", "TRANSFER_IN" -> tx.amount();
				default -> -tx.amount();
			};
			saved.merge(tx.wishId(), signed, Math::addExact);
		}
		return jdbc.query("""
			select id,purpose,target_amount,created_at,completed_at,abandoned_at,deleted_at,state
			from wish where account_id=? order by created_at,id
			""", (rs, n) -> {
			Map<String, Object> row = new LinkedHashMap<>(); UUID id = rs.getObject("id", UUID.class);
			row.put("wish_id", id); row.put("title", rs.getString("purpose"));
			row.put("target_amount", rs.getLong("target_amount")); row.put("created_at", instant(rs.getTimestamp("created_at")));
			Instant completed = instant(rs.getTimestamp("completed_at")); Instant abandoned = instant(rs.getTimestamp("abandoned_at"));
			row.put("closed_at", completed == null ? abandoned : completed); row.put("deleted_at", instant(rs.getTimestamp("deleted_at")));
			row.put("status", rs.getString("state")); row.put("is_representative", id.equals(representative));
			row.put("saved_amount_at_period_end", Math.max(0, saved.getOrDefault(id, 0L))); return row;
		}, accountId);
	}

	private Map<String, Object> visitMetrics(Account account, RecapPeriods.Period period, Instant snapshotAt) {
		Instant start = period.start().atStartOfDay(SEOUL).toInstant(); Instant end = period.endExclusive().atStartOfDay(SEOUL).toInstant();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("received_visit_count", countVisits(account, "target_id", start, end, snapshotAt, false));
		out.put("unique_received_visitor_count", countVisits(account, "target_id", start, end, snapshotAt, true));
		out.put("previous_week_received_visit_count", countVisits(account, "target_id", start.minusSeconds(7 * 86400L), start, snapshotAt, false));
		LocalDate monthStart = period.endExclusive().minusDays(1).withDayOfMonth(1);
		out.put("monthly_outgoing_visit_count", countVisits(account, "actor_id", monthStart.atStartOfDay(SEOUL).toInstant(), end, snapshotAt, false));
		return out;
	}

	private long countVisits(Account account, String side, Instant start, Instant end, Instant snapshotAt, boolean distinct) {
		String count = distinct ? "count(distinct actor_id)" : "count(*)";
		return jdbc.queryForObject("select " + count + " from behavior_event where academy_id=? and event_type='PROFILE_VISIT' and "
				+ side + "=? and occurred_at>=? and occurred_at<? and received_at<=?", Long.class,
				account.academyId(), account.studentId(), Timestamp.from(start), Timestamp.from(end), Timestamp.from(snapshotAt));
	}

	private Map<String, Object> peerMetrics(Account viewer, RecapPeriods.Period period, Instant snapshotAt) {
		List<UUID> peers = jdbc.query("""
			select a.id from card_balance_account a join student s on s.id=a.student_id
			where a.academy_id=? and a.student_id<>? and a.closed_at is null
			and s.age_provenance='PROVIDED' and s.age between ? and ? and exists (
			 select 1 from academy_membership m where m.student_id=a.student_id and m.academy_id=a.academy_id and m.left_at is null)
			order by a.id
			""", (rs, n) -> rs.getObject(1, UUID.class), viewer.academyId(), viewer.studentId(), viewer.age() - 2, viewer.age() + 2);
		List<Integer> activeWeeks = new ArrayList<>(); List<Double> achievementRates = new ArrayList<>();
		Instant end = period.endExclusive().atStartOfDay(SEOUL).toInstant(); Instant start = period.start().minusWeeks(52).atStartOfDay(SEOUL).toInstant();
		for (UUID peer : peers) {
			Set<LocalDate> weeks = new HashSet<>();
			for (var tx : effectiveTransactions(peer, snapshotAt)) if (!tx.occurredAt().isBefore(start) && tx.occurredAt().isBefore(end)
					&& (tx.type().equals("DEPOSIT") || tx.type().equals("TRANSFER_IN")))
				weeks.add(tx.occurredAt().atZone(SEOUL).toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)));
			activeWeeks.add(weeks.size());
			Double rate = jdbc.queryForObject("select case when count(*)=0 then null else count(*) filter(where state='COMPLETED')::float/count(*) end from wish where account_id=? and deleted_at is null", Double.class, peer);
			if (rate != null) achievementRates.add(rate);
		}
		return Map.of("habit_active_weeks", activeWeeks, "achievement_rates", achievementRates);
	}

	private List<Map<String, Object>> successStories(Account viewer, Instant start, Instant end) {
		return jdbc.query("""
			select w.id,a.student_id,
			 (select count(*) from ledger_event le where le.account_id=a.id and le.event_type='WISH_DEPOSIT'
			  and le.occurred_at>=date_trunc('month', ?::timestamptz)-interval '1 month'
			  and le.occurred_at<date_trunc('month', ?::timestamptz)) as prior_deposit_count
			from wish w join card_balance_account a on a.id=w.account_id join shared_card c on c.wish_id=w.id
			where w.academy_id=? and a.student_id<>? and w.state='COMPLETED'
			and w.completed_at>=? and w.completed_at<? and c.updated_at>=? and c.updated_at<?
			order by w.completed_at,w.id limit 5
			""", (rs, n) -> Map.of("wish_id", rs.getObject(1, UUID.class), "type_title", "ACADEMY_SUCCESS",
					"author_previous_month", Map.of("deposit_count", rs.getLong(3))),
			Timestamp.from(start), Timestamp.from(start), viewer.academyId(), viewer.studentId(),
			Timestamp.from(start), Timestamp.from(end), Timestamp.from(start), Timestamp.from(end));
	}

	private static long depositCount(List<EffectiveTransaction> all, Instant start, Instant end) {
		return all.stream().filter(tx -> tx.type().equals("DEPOSIT") && !tx.occurredAt().isBefore(start) && tx.occurredAt().isBefore(end)).count();
	}
	private static Map<String, Object> period(RecapPeriods.Period period) { return Map.of("start_date", period.start(), "end_date_exclusive", period.endExclusive(), "timezone", "Asia/Seoul"); }
	private String digest(Object value) {
		try {
			byte[] bytes = json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception e) { throw new IllegalStateException("Recap input digest failed", e); }
	}
	private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

	public record Snapshot(UUID generationId, UUID studentId, UUID academyId, String inputDigest,
			String requestJson, long effectiveDepositCount) {}
	private record Account(UUID studentId, UUID academyId, int age, String ageProvenance) {}
	private record Event(UUID id, String type, long accountDelta, Instant occurredAt, UUID parent, Map<UUID, Long> effects) {}
	private record EffectiveTransaction(UUID rootEventId, UUID wishId, Instant occurredAt, long amount, String type) {
		Map<String, Object> asInput() { return Map.of("root_event_id", rootEventId, "wish_id", wishId,
				"occurred_at", occurredAt, "amount", amount, "type", type); }
	}
}
