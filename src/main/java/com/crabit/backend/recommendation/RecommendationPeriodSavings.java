package com.crabit.backend.recommendation;

import com.crabit.backend.recommendation.RecommendationSnapshotRepository.AccountRow;

import java.time.*;
import java.util.*;

final class RecommendationPeriodSavings {
	static final long MAX = 9_007_199_254_740_991L;
	static final List<String> TYPES =
			List.of(
					"WISH_DEPOSIT",
					"WISH_WITHDRAWAL",
					"WISH_TRANSFER",
					"WISH_COMPLETION_RETURN",
					"WISH_ABANDONMENT_RETURN",
					"WISH_DELETION_RETURN");
	static final List<String> NAMES =
			List.of(
					"deposits",
					"withdrawals",
					"transfers",
					"completion_returns",
					"abandonment_returns",
					"deletion_returns");

	record Row(
			LocalDate date,
			String type,
			boolean correction,
			long count,
			long positive,
			long negative,
			boolean invalid) {}

	record CountAmount(long count, long amount) {}

	record Correction(long count, long positive_amount, long negative_amount) {}

	static Map<String, Object> assemble(
			RecommendationRequest request, AccountRow account, Instant at, List<Row> rows) {
		var period = request.resolve(at);
		var days = new LinkedHashMap<LocalDate, Metrics>();
		for (LocalDate d = period.start(); d.isBefore(period.end()); d = d.plusDays(1))
			days.put(d, new Metrics());
		for (Row row : rows) {
			Metrics m = days.get(row.date());
			if (m == null || row.invalid()) throw RecommendationHandoffException.incomplete();
			m.add(row);
		}
		var totals = new Metrics();
		var daily = new ArrayList<Map<String, Object>>();
		for (var entry : days.entrySet()) {
			LocalDate date = entry.getKey();
			Metrics value = entry.getValue();
			totals.merge(value);
			daily.add(
					Map.of(
							"date",
							date.toString(),
							"coverage",
							coverage(
									date.atStartOfDay(RecommendationRequest.ZONE).toInstant(),
									date.plusDays(1)
											.atStartOfDay(RecommendationRequest.ZONE)
											.toInstant(),
									account.openedAt(),
									at),
							"has_activity",
							value.active(),
							"totals",
							value.payload()));
		}
		return Map.of(
				"scope",
				Map.of(
						"account_id",
						account.accountId(),
						"user_id",
						account.studentId(),
						"academy_id",
						account.academyId()),
				"period",
				Map.of(
						"start_date",
						period.start().toString(),
						"end_date_exclusive",
						period.end().toString(),
						"timezone",
						"Asia/Seoul",
						"start_at",
						period.startAt().toString(),
						"end_at_exclusive",
						period.endAt().toString(),
						"input",
						request.period() == null ? "default_current_month" : "explicit"),
				"coverage",
				coverage(period.startAt(), period.endAt(), account.openedAt(), at),
				"has_activity",
				totals.active(),
				"totals",
				totals.payload(),
				"daily",
				List.copyOf(daily));
	}

	static Map<String, Object> coverage(Instant start, Instant end, Instant opened, Instant at) {
		Instant from = start.isAfter(opened) ? start : opened, until = end.isBefore(at) ? end : at;
		boolean empty = !from.isBefore(until);
		var reasons = new ArrayList<String>();
		if (start.isBefore(opened)) reasons.add("before_account_opened");
		if (end.isAfter(at)) reasons.add("after_snapshot");
		var map = new LinkedHashMap<String, Object>();
		map.put("history_source", "backend_recorded");
		map.put(
				"status",
				empty ? "unobserved" : reasons.isEmpty() ? "fully_observed" : "partially_observed");
		map.put("observed_start_at", empty ? null : from.toString());
		map.put("observed_end_at_exclusive", empty ? null : until.toString());
		map.put("reasons", List.copyOf(reasons));
		return Collections.unmodifiableMap(map);
	}

	static long safe(long value) {
		if (value < 0 || value > MAX) throw RecommendationHandoffException.incomplete();
		return value;
	}

	static long sum(long a, long b) {
		try {
			return safe(Math.addExact(a, b));
		} catch (ArithmeticException ex) {
			throw RecommendationHandoffException.incomplete();
		}
	}

	static final class Metrics {
		final long[][] normal = new long[6][2], corrections = new long[6][3];
		long abandoned;

		void add(Row r) {
			safe(r.count());
			safe(r.positive());
			safe(r.negative());
			if (r.type().equals("ABANDONMENT_FACT")) {
				abandoned = sum(abandoned, r.count());
				return;
			}
			int i = TYPES.indexOf(r.type());
			if (i < 0) throw RecommendationHandoffException.incomplete();
			if (r.correction()) {
				corrections[i][0] = sum(corrections[i][0], r.count());
				corrections[i][1] = sum(corrections[i][1], r.positive());
				corrections[i][2] = sum(corrections[i][2], r.negative());
			} else {
				normal[i][0] = sum(normal[i][0], r.count());
				normal[i][1] = sum(normal[i][1], i == 0 || i == 2 ? r.positive() : r.negative());
			}
		}

		void merge(Metrics m) {
			abandoned = sum(abandoned, m.abandoned);
			for (int i = 0; i < 6; i++) {
				for (int j = 0; j < 2; j++) normal[i][j] = sum(normal[i][j], m.normal[i][j]);
				for (int j = 0; j < 3; j++)
					corrections[i][j] = sum(corrections[i][j], m.corrections[i][j]);
			}
		}

		boolean active() {
			if (abandoned > 0) return true;
			for (int i = 0; i < 6; i++) if (normal[i][0] > 0 || corrections[i][0] > 0) return true;
			return false;
		}

		Map<String, Object> payload() {
			var map = new LinkedHashMap<String, Object>();
			var corrected = new LinkedHashMap<String, Correction>();
			for (int i = 0; i < 6; i++) {
				map.put(NAMES.get(i), new CountAmount(normal[i][0], normal[i][1]));
				corrected.put(
						TYPES.get(i),
						new Correction(corrections[i][0], corrections[i][1], corrections[i][2]));
			}
			map.put("abandonment_count", abandoned);
			map.put("corrections", Collections.unmodifiableMap(corrected));
			return Collections.unmodifiableMap(map);
		}
	}
}
