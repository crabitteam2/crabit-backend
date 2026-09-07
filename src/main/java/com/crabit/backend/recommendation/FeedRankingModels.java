package com.crabit.backend.recommendation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class FeedRankingModels {
	private FeedRankingModels() {}

	public record Request(int schema_version, UUID request_id, UUID context_id, UUID viewer_id,
			UUID academy_id, Instant recommendation_at, String timezone, String feature_version,
			String classifier_version, Map<String, Object> viewer_previous_month,
			List<Map<String, Object>> candidates) {
		public Request {
			if (schema_version != 1 || !"Asia/Seoul".equals(timezone)
					|| !"feed-features-v1".equals(feature_version))
				throw new IllegalArgumentException("Unsupported feed ranking request version");
			Objects.requireNonNull(request_id); Objects.requireNonNull(context_id);
			Objects.requireNonNull(viewer_id); Objects.requireNonNull(academy_id);
			Objects.requireNonNull(recommendation_at); Objects.requireNonNull(classifier_version);
			viewer_previous_month = freezeMap(Objects.requireNonNull(viewer_previous_month));
			candidates = Objects.requireNonNull(candidates).stream().map(FeedRankingModels::freezeMap).toList();
			if (candidates.size() > 100) throw new IllegalArgumentException("At most 100 candidates are allowed");
			var ids = new HashSet<>();
			for (var candidate : candidates) {
				Object id = candidate == null ? null : candidate.get("card_id");
				if (id == null || !ids.add(id.toString()))
					throw new IllegalArgumentException("Candidate card IDs must be present and unique");
			}
		}
	}

	private static Map<String, Object> freezeMap(Map<String, Object> source) {
		Map<String, Object> copy = new java.util.LinkedHashMap<>();
		source.forEach((key, value) -> copy.put(Objects.requireNonNull(key), freeze(value)));
		return java.util.Collections.unmodifiableMap(copy);
	}

	private static Object freeze(Object value) {
		if (value instanceof Map<?, ?> raw) {
			Map<String, Object> map = new java.util.LinkedHashMap<>();
			raw.forEach((key, child) -> map.put(Objects.requireNonNull(key).toString(), freeze(child)));
			return java.util.Collections.unmodifiableMap(map);
		}
		if (value instanceof List<?> list) return list.stream().map(FeedRankingModels::freeze).toList();
		return value;
	}

	public record Result(UUID requestId, UUID contextId, String inputDigest, List<UUID> orderedCardIds) {
		public Result { orderedCardIds = List.copyOf(orderedCardIds); }
	}
}
