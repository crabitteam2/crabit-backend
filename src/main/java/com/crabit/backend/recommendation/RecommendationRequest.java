package com.crabit.backend.recommendation;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

record RecommendationRequest(
		UUID handoffId, UUID accountId, PeriodInput period, InterestContext interest) {
	static final int MAX_BYTES = 262144;
	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	static final JsonMapper JSON =
			JsonMapper.builder()
					.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
					.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.build();
	static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

	static RecommendationRequest parse(byte[] bytes) {
		try {
			if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
				throw RecommendationHandoffException.malformed();
			JsonNode root = JSON.readTree(bytes);
			fields(
					root,
					Set.of("handoff_id", "card_balance_account_id"),
					Set.of("period", "interest_context"));
			UUID account = uuid(root.get("card_balance_account_id"));
			PeriodInput period = null;
			if (root.has("period")) {
				JsonNode node = root.get("period");
				fields(node, Set.of("start_date", "end_date_exclusive"), Set.of());
				period =
						new PeriodInput(
								date(node.get("start_date")), date(node.get("end_date_exclusive")));
			}
			InterestContext interest = null;
			if (root.has("interest_context")) {
				JsonNode node = root.get("interest_context");
				fields(
						node,
						Set.of(
								"source",
								"taxonomy_version",
								"classifier_version",
								"classified_at",
								"card_balance_account_id",
								"viewer_interest_category_ids",
								"wish_classifications"),
						Set.of());
				if (!"python".equals(text(node.get("source")))
						|| !account.equals(uuid(node.get("card_balance_account_id"))))
					throw RecommendationHandoffException.malformed();
				var classifications = new ArrayList<Classification>();
				var ids = new HashSet<UUID>();
				JsonNode array = node.get("wish_classifications");
				if (!array.isArray() || array.size() > 500)
					throw RecommendationHandoffException.malformed();
				for (JsonNode value : array) {
					fields(value, Set.of("wish_id", "category_ids", "title_sha256"), Set.of());
					UUID id = uuid(value.get("wish_id"));
					String hash = text(value.get("title_sha256"));
					if (!ids.add(id) || !hash.matches("[a-f0-9]{64}"))
						throw RecommendationHandoffException.malformed();
					classifications.add(
							new Classification(
									id, identifiers(value.get("category_ids"), 5), hash));
				}
				interest =
						new InterestContext(
								identifier(node.get("taxonomy_version")),
								identifier(node.get("classifier_version")),
								timestamp(node.get("classified_at")),
								identifiers(node.get("viewer_interest_category_ids"), 20),
								List.copyOf(classifications));
			}
			return new RecommendationRequest(
					uuid(root.get("handoff_id")), account, period, interest);
		} catch (RecommendationHandoffException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw RecommendationHandoffException.malformed();
		}
	}

	static void fields(JsonNode node, Set<String> required, Set<String> optional) {
		if (node == null || !node.isObject()) throw RecommendationHandoffException.malformed();
		var keys = Set.copyOf(node.propertyNames());
		var allowed = new HashSet<>(required);
		allowed.addAll(optional);
		if (!keys.containsAll(required) || !allowed.containsAll(keys))
			throw RecommendationHandoffException.malformed();
	}

	static String text(JsonNode n) {
		if (n == null || !n.isTextual()) throw RecommendationHandoffException.malformed();
		return n.textValue();
	}

	static UUID uuid(JsonNode n) {
		String s = text(n);
		if (!s.matches(
				"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
			throw RecommendationHandoffException.malformed();
		return UUID.fromString(s);
	}

	static LocalDate date(JsonNode n) {
		String s = text(n);
		if (!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
			throw RecommendationHandoffException.malformed();
		LocalDate d = LocalDate.parse(s);
		if (d.getYear() < 1) throw RecommendationHandoffException.malformed();
		return d;
	}

	static Instant timestamp(JsonNode node) {
		String text = text(node);
		if (!text.matches(
				"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})"))
			throw RecommendationHandoffException.malformed();
		OffsetDateTime parsed = OffsetDateTime.parse(text);
		if (parsed.getYear() < 1) throw RecommendationHandoffException.malformed();
		return parsed.toInstant();
	}

	static String identifier(JsonNode n) {
		String s = text(n);
		if (!IDENTIFIER.matcher(s).matches()) throw RecommendationHandoffException.malformed();
		return s;
	}

	static List<String> identifiers(JsonNode n, int max) {
		if (n == null || !n.isArray() || n.size() > max)
			throw RecommendationHandoffException.malformed();
		var values = new ArrayList<String>();
		var seen = new HashSet<String>();
		for (JsonNode v : n) {
			String s = identifier(v);
			if (!seen.add(s)) throw RecommendationHandoffException.malformed();
			values.add(s);
		}
		return List.copyOf(values);
	}

	PeriodInput resolve(Instant at) {
		if (interest != null && interest.classifiedAt().isAfter(at))
			throw RecommendationHandoffException.malformed();
		LocalDate first = at.atZone(ZONE).toLocalDate().withDayOfMonth(1);
		return period == null ? new PeriodInput(first, first.plusMonths(1)) : period;
	}

	record PeriodInput(LocalDate start, LocalDate end) {
		PeriodInput {
			long days = ChronoUnit.DAYS.between(start, end);
			if (days < 1 || days > 366) throw RecommendationHandoffException.malformed();
		}

		Instant startAt() {
			return start.atStartOfDay(ZONE).toInstant();
		}

		Instant endAt() {
			return end.atStartOfDay(ZONE).toInstant();
		}
	}

	record InterestContext(
			String taxonomyVersion,
			String classifierVersion,
			Instant classifiedAt,
			List<String> viewerCategories,
			List<Classification> classifications) {}

	record Classification(UUID wishId, List<String> categories, String titleHash) {}
}
