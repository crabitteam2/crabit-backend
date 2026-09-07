package com.crabit.backend.recap;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared account-wide CoreMetrics calculation used by recap and feed features. */
public final class RecapAuthorMetrics {
    private RecapAuthorMetrics() {}
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public record Transaction(Instant occurredAt, long amount, String type) {}

	/** Mirrors monthly_recap.compute_core_metrics over account-wide effective history. */
	public static Map<String, Object> compute(List<Transaction> all, LocalDate monthStart,
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
}
