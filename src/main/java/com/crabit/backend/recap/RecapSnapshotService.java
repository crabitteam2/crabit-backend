package com.crabit.backend.recap;

import com.crabit.backend.wish.SharedCardQueryRepository;
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
		return build(UUID.randomUUID(), accountId, kind, period);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Snapshot build(UUID generationId, UUID accountId, RecapKind kind, RecapPeriods.Period period) {
		Instant snapshotAt = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
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

		UUID representative = representative(accountId);
		List<Map<String, Object>> wishes = wishes(accountId, representative, all, periodEnd);
		List<Map<String, Object>> transactions = new ArrayList<>();
		for (EffectiveTransaction tx : all) {
			if (!tx.occurredAt().isBefore(periodEnd)) continue;
			transactions.add(tx.asInput());
		}

		Map<String, Object> input = new LinkedHashMap<>();
		input.put("representative_wish_id", representative);
		input.put("wishes", wishes);
		input.put("effective_transactions", transactions);
		input.put("visit_metrics", visitMetrics(account, period, snapshotAt));
		input.put("peer_metrics", peerMetrics(account, period, snapshotAt));
		input.put("success_story_candidates", successStories(account, periodStart, periodEnd, snapshotAt));

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
			Event root = chain.stream().filter(event -> event.parent() == null).findFirst().orElseThrow(); long accountDelta = 0; Map<UUID, Long> effects = new LinkedHashMap<>();
			for (Event event : chain) {
				accountDelta = Math.addExact(accountDelta, event.accountDelta());
				event.effects().forEach((wish, delta) -> effects.merge(wish, delta, Math::addExact));
			}
			List<Map.Entry<UUID, Long>> nonzero = effects.entrySet().stream().filter(e -> e.getValue() != 0)
					.sorted(Map.Entry.comparingByKey()).toList();
			if (nonzero.isEmpty()) continue;
			if (root.type().equals("WISH_TRANSFER")) {
				if (accountDelta != 0 || nonzero.size() != 2 || Math.addExact(nonzero.get(0).getValue(), nonzero.get(1).getValue()) != 0)
					throw new IllegalStateException("Ambiguous recap transfer chain");
			} else if (nonzero.size() != 1) throw new IllegalStateException("Ambiguous recap ledger chain");
			for (var effect : nonzero) {
				String type = classify(root.type(), effect.getValue());
				result.add(new EffectiveTransaction(root.id(), effect.getKey(), root.occurredAt(),
						effect.getValue() < 0 ? Math.negateExact(effect.getValue()) : effect.getValue(), type));
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
			and state='IN_PROGRESS' order by created_at,id limit 1
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
		if (!"PROVIDED".equals(viewer.ageProvenance()))
			return Map.of("habit_active_weeks", List.of(), "achievement_rates", List.of());
		List<UUID> peers = jdbc.query("""
			select a.id from card_balance_account a join student s on s.id=a.student_id
			where a.academy_id=? and a.student_id<>? and a.closed_at is null
			and s.age_provenance='PROVIDED' and s.age between ? and ? and exists (
			 select 1 from academy_membership m where m.student_id=a.student_id and m.academy_id=a.academy_id and m.left_at is null)
			order by a.id
			""", (rs, n) -> rs.getObject(1, UUID.class), viewer.academyId(), viewer.studentId(), viewer.age() - 2, viewer.age() + 2);
		List<Integer> activeWeeks = new ArrayList<>(); List<Double> achievementRates = new ArrayList<>();
		Instant end = period.endExclusive().atStartOfDay(SEOUL).toInstant(); Instant start = period.endExclusive().minusWeeks(52).atStartOfDay(SEOUL).toInstant();
		for (UUID peer : peers) {
			List<EffectiveTransaction> transactions = effectiveTransactions(peer, snapshotAt);
			Set<LocalDate> weeks = new HashSet<>();
			for (var tx : transactions) if (!tx.occurredAt().isBefore(start) && tx.occurredAt().isBefore(end)
					&& (tx.type().equals("DEPOSIT") || tx.type().equals("TRANSFER_IN")))
				weeks.add(tx.occurredAt().atZone(SEOUL).toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)));
			activeWeeks.add(weeks.size());
			UUID representative = representative(peer);
			if (representative == null) continue;
			Long target = jdbc.query("select target_amount from wish where id=? and account_id=? and deleted_at is null",
					(rs, n) -> rs.getLong(1), representative, peer).stream().findFirst().orElse(null);
			if (target == null || target <= 0) continue;
			long saved = 0;
			for (var tx : transactions) if (tx.wishId().equals(representative) && tx.occurredAt().isBefore(end))
				saved = Math.addExact(saved, switch (tx.type()) {
					case "DEPOSIT", "TRANSFER_IN" -> tx.amount();
					default -> -tx.amount();
				});
			achievementRates.add(achievementRate(saved, target));
		}
		return Map.of("habit_active_weeks", activeWeeks, "achievement_rates", achievementRates);
	}

	private List<Map<String, Object>> successStories(Account viewer, Instant start, Instant end, Instant snapshotAt) {
		return new SharedCardQueryRepository(jdbc).findVisibleRecapCompleted(viewer.studentId(), viewer.academyId(), start, end, 5)
				.stream().map(story -> {
					LocalDate monthEnd = story.completedAt().atZone(SEOUL).toLocalDate().withDayOfMonth(1);
					LocalDate monthStart = monthEnd.minusMonths(1);
					Account author = new Account(story.ownerId(), story.academyId(), story.ownerAge(), null);
					Instant from = monthStart.atStartOfDay(SEOUL).toInstant();
					Instant to = monthEnd.atStartOfDay(SEOUL).toInstant();
					long abandons = jdbc.queryForObject("""
						select count(*) from wish where account_id=? and state='ABANDONED'
						and abandoned_at>=? and abandoned_at<?
						""", Long.class, story.accountId(), Timestamp.from(from), Timestamp.from(to));
					Map<String, Object> metrics = authorMetrics(effectiveTransactions(story.accountId(), snapshotAt),
							monthStart, abandons, countVisits(author, "actor_id", from, to, snapshotAt, false));
					return Map.<String, Object>of("wish_id", story.wishId(), "type_title", "ACADEMY_SUCCESS",
							"author_previous_month", metrics);
				}).toList();
	}

	/** Mirrors monthly_recap.compute_core_metrics over account-wide effective history. */
	static Map<String, Object> authorMetrics(List<EffectiveTransaction> all, LocalDate monthStart,
			long abandonCount, long visitCount) {
		Instant start = monthStart.atStartOfDay(SEOUL).toInstant();
		Instant end = monthStart.plusMonths(1).atStartOfDay(SEOUL).toInstant();
		Instant midpoint = monthStart.plusDays(15).atStartOfDay(SEOUL).toInstant();
		long deposits = 0, total = 0, firstHalf = 0, transfers = 0;
		Set<LocalDate> days = new java.util.TreeSet<>();
		for (var tx : all) {
			if (tx.occurredAt().isBefore(start) || !tx.occurredAt().isBefore(end)) continue;
			if (tx.type().equals("TRANSFER_OUT")) transfers++;
			if (!tx.type().equals("DEPOSIT") && !tx.type().equals("WITHDRAWAL")) continue;
			long amount = tx.type().equals("DEPOSIT") ? tx.amount() : Math.negateExact(tx.amount());
			if (tx.type().equals("DEPOSIT")) { deposits++; days.add(tx.occurredAt().atZone(SEOUL).toLocalDate()); }
			total = Math.addExact(total, amount);
			if (tx.occurredAt().isBefore(midpoint)) firstHalf = Math.addExact(firstHalf, amount);
		}
		Double regularity = null;
		if (days.size() >= 2) {
			List<LocalDate> dates = new ArrayList<>(days);
			List<Long> gaps = new ArrayList<>();
			for (int i = 1; i < dates.size(); i++) gaps.add(java.time.temporal.ChronoUnit.DAYS.between(dates.get(i-1), dates.get(i)));
			double mean = gaps.stream().mapToLong(Long::longValue).average().orElseThrow();
			regularity = Math.sqrt(gaps.stream().mapToDouble(g -> (g-mean)*(g-mean)).average().orElseThrow());
		}
		if (total < -9007199254740991L || total > 9007199254740991L)
			throw new IllegalStateException("Recap aggregate exceeds safe integer domain");
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("metrics_version", "core-metrics-v1"); metrics.put("deposit_count", deposits);
		metrics.put("total_savings", total); metrics.put("avg_amount", deposits == 0 ? 0.0 : (double) total / deposits);
		metrics.put("regularity_std", regularity);
		metrics.put("pace_bias", total > 0 ? ((double) total - 2.0 * firstHalf) / total : null);
		metrics.put("abandon_count", abandonCount); metrics.put("transfer_count", transfers); metrics.put("visit_count", visitCount);
		return metrics;
	}

	private static long depositCount(List<EffectiveTransaction> all, Instant start, Instant end) {
		return all.stream().filter(tx -> tx.type().equals("DEPOSIT") && !tx.occurredAt().isBefore(start) && tx.occurredAt().isBefore(end)).count();
	}
	static double achievementRate(long savedAmount, long targetAmount) {
		if (targetAmount <= 0) throw new IllegalArgumentException("Representative Wish target must be positive");
		return ((double) Math.max(0L, savedAmount) / targetAmount) * 100.0;
	}
	private static Map<String, Object> period(RecapPeriods.Period period) { return Map.of("start_date", period.start(), "end_date_exclusive", period.endExclusive(), "timezone", "Asia/Seoul"); }
	private String digest(Object value) {
		try {
			byte[] bytes = JsonCanonicalizer.canonicalize(json, value);
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception e) { throw new IllegalStateException("Recap input digest failed", e); }
	}
	private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

	public record Snapshot(UUID generationId, UUID studentId, UUID academyId, String inputDigest,
			String requestJson, long effectiveDepositCount) {}
	private record Account(UUID studentId, UUID academyId, int age, String ageProvenance) {}
	private record Event(UUID id, String type, long accountDelta, Instant occurredAt, UUID parent, Map<UUID, Long> effects) {}
	record EffectiveTransaction(UUID rootEventId, UUID wishId, Instant occurredAt, long amount, String type) {
		Map<String, Object> asInput() { return Map.of("root_event_id", rootEventId, "wish_id", wishId,
				"occurred_at", occurredAt, "amount", amount, "type", type); }
	}
}
