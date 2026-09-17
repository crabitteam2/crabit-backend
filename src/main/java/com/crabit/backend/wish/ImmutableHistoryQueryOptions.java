package com.crabit.backend.wish;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;

/** Normalized account-history filters. These values also identify a cursor traversal. */
record ImmutableHistoryQueryOptions(Instant from, Instant to, String query, String sort) {
	static ImmutableHistoryQueryOptions parse(String from, String to, String query, String sort) {
		Instant start = instant(from, "from");
		Instant end = instant(to, "to");
		if (start != null && end != null && !start.isBefore(end)) {
			throw invalid("to");
		}
		if (query != null && query.codePointCount(0, query.length()) > 100) throw invalid("q");
		String direction = sort == null ? "desc" : sort;
		if (!direction.equals("asc") && !direction.equals("desc")) throw invalid("sort");
		return new ImmutableHistoryQueryOptions(start, end, normalize(query), direction);
	}

	static String normalize(String value) {
		return value == null ? "" : value.replaceAll("(?U)\\s+", " ").trim().toLowerCase(Locale.ROOT);
	}

	Long amount() {
		if (!query.matches("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?: ?원)?")) return null;
		try {
			long value = Long.parseLong(query.replaceAll("[, 원+\\-]", "").replaceFirst("^0+(?!$)", ""));
			return value <= 9_007_199_254_740_991L ? value : null;
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	boolean defaults() {
		return from == null && to == null && query.isEmpty() && sort.equals("desc");
	}

	private static Instant instant(String value, String field) {
		if (value == null) return null;
		try {
			if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})")) throw invalid(field);
			return OffsetDateTime.parse(value).toInstant();
		} catch (RuntimeException exception) {
			throw invalid(field);
		}
	}

	private static WishLifecycleException invalid(String field) {
		return new WishLifecycleException(WishLifecycleException.Code.MALFORMED_REQUEST,
				"Invalid account history " + field + ".", field);
	}
}
