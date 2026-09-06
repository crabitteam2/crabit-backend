package com.crabit.backend.recommendation;

import com.crabit.backend.recommendation.RecommendationSnapshotRepository.AccountRow;
import com.crabit.backend.wish.SharedCardQueryRepository.Row;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

final class RecommendationCandidateSelection {
	record Result(
			List<Row> rows,
			Map<String, Object> selection,
			Map<String, Object> interest,
			boolean truncated) {}

	static Result select(
			RecommendationSnapshotRepository repository,
			RecommendationRequest request,
			AccountRow account,
			Instant at) {
		List<Row> latest =
				bounded(repository.findCandidates(account.studentId(), account.academyId(), 101));
		List<Row> completed =
				bounded(
						repository.findCompletedCandidates(
								account.studentId(),
								account.academyId(),
								at.minus(Duration.ofDays(30)),
								at,
								101));
		var context = request.interest();
		var categories = new TreeSet<String>();
		List<Row> interests = List.of();
		String status = "absent";
		if (context != null) {
			status = "no_usable_classifications";
			if (context.classifiedAt().isBefore(at.minus(Duration.ofDays(30)))) status = "stale";
			else {
				var ids =
						context.classifications().stream()
								.map(RecommendationRequest.Classification::wishId)
								.toList();
				var own = repository.findOwnTitles(account.accountId(), ids);
				for (var classification : context.classifications())
					if (matches(classification, own.get(classification.wishId())))
						categories.addAll(classification.categories());
				categories.retainAll(context.viewerCategories());
				if (!categories.isEmpty()) {
					status = "used";
					var matching = new ArrayList<UUID>();
					var visible =
							repository.findVisibleTitles(
									account.studentId(), account.academyId(), ids);
					for (var classification : context.classifications())
						if (matches(classification, visible.get(classification.wishId()))
								&& !Collections.disjoint(categories, classification.categories()))
							matching.add(classification.wishId());
					interests =
							bounded(
									repository.findInterestCandidates(
											account.studentId(),
											account.academyId(),
											matching,
											101));
				}
			}
		}
		var chosen = new LinkedHashMap<UUID, String>();
		take(interests, "interest", 25, chosen);
		take(completed, "recently_completed", 25, chosen);
		take(latest, "latest", 50, chosen);
		take(latest, "latest", 100, chosen);
		take(completed, "recently_completed", 100, chosen);
		take(interests, "interest", 100, chosen);
		var rows = new ArrayList<Row>();
		var provenance = new ArrayList<Map<String, Object>>();
		var counts = new LinkedHashMap<String, Integer>();
		append(latest, "latest", chosen, rows, provenance, counts);
		append(completed, "recently_completed", chosen, rows, provenance, counts);
		append(interests, "interest", chosen, rows, provenance, counts);
		var metadata =
				Map.<String, Object>of(
						"total_limit",
						100,
						"query_limit_per_group",
						101,
						"quotas",
						Map.of("latest", 50, "recently_completed", 25, "interest", 25),
						"selected_counts",
						counts,
						"group_order",
						List.of("latest", "recently_completed", "interest"),
						"recent_completed_start_at",
						at.minus(Duration.ofDays(30)).toString(),
						"recent_completed_end_at_exclusive",
						at.toString(),
						"provenance",
						List.copyOf(provenance));
		var evidence = new LinkedHashMap<String, Object>();
		evidence.put("status", status);
		evidence.put("source", context == null ? null : "python");
		evidence.put("taxonomy_version", context == null ? null : context.taxonomyVersion());
		evidence.put("classifier_version", context == null ? null : context.classifierVersion());
		evidence.put("classified_at", context == null ? null : context.classifiedAt().toString());
		evidence.put("usable_viewer_category_ids", List.copyOf(categories));
		evidence.put("exhaustive", false);
		return new Result(
				List.copyOf(rows),
				metadata,
				Collections.unmodifiableMap(evidence),
				latest.size() > 100);
	}

	static boolean matches(RecommendationRequest.Classification c, String title) {
		if (title == null) return false;
		try {
			return c.titleHash()
					.equals(
							HexFormat.of()
									.formatHex(
											MessageDigest.getInstance("SHA-256")
													.digest(
															title.getBytes(
																	StandardCharsets.UTF_8))));
		} catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static List<Row> bounded(List<Row> rows) {
		if (rows == null || rows.size() > 101) throw RecommendationHandoffException.incomplete();
		return rows;
	}

	static void take(List<Row> rows, String group, int max, Map<UUID, String> chosen) {
		int count = 0;
		for (Row row : rows) {
			if (chosen.size() >= 100 || count >= max) break;
			if (chosen.putIfAbsent(row.sharedCardId(), group) == null) count++;
		}
	}

	static void append(
			List<Row> source,
			String group,
			Map<UUID, String> chosen,
			List<Row> rows,
			List<Map<String, Object>> provenance,
			Map<String, Integer> counts) {
		int count = 0;
		var seen = new HashSet<UUID>();
		for (Row row : source)
			if (group.equals(chosen.get(row.sharedCardId())) && seen.add(row.sharedCardId())) {
				rows.add(row);
				provenance.add(Map.of("feed_id", row.sharedCardId(), "group", group));
				count++;
			}
		counts.put(group, count);
	}
}
