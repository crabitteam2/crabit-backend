package com.crabit.backend.recap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The existing snake_case presenter view contract, before query-time story authorization. */
final class RecapResultValidator {
	private static final long SAFE = 9_007_199_254_740_991L;
	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
	private RecapResultValidator() {}

	static Map<String, Object> parse(byte[] bytes, RecapGenerationCoordinator.Claim claim) {
		try {
			var root = object(JSON.readValue(bytes, Object.class), "schema_version", "algorithm_version", "generation_id",
					"input_digest", "student_id", "card_balance_account_id", "academy_id", "kind", "period", "view", "internal_metrics");
			equal(root.get("generation_id"), claim.id().toString()); equal(root.get("input_digest"), claim.inputDigest());
			equal(root.get("student_id"), claim.studentId().toString()); equal(root.get("card_balance_account_id"), claim.accountId().toString());
			equal(root.get("academy_id"), claim.academyId().toString()); equal(root.get("kind"), claim.kind().name());
			number(root.get("schema_version"), 1, 1, true, false); equal(root.get("algorithm_version"), "recap-1");
			var request = castObject(JSON.readValue(claim.requestJson(), Object.class));
			var period = object(root.get("period"), "start_date", "end_date_exclusive", "timezone");
			equal(period, request.get("period")); equal(period.get("timezone"), "Asia/Seoul");
			LocalDate start = date(period.get("start_date"), false), end = date(period.get("end_date_exclusive"), false);
			if (!end.isAfter(start)) invalid();
			castObject(root.get("internal_metrics")); // Deliberately open: the existing data API defines no required metric keys.
			if (claim.kind() == RecapKind.WEEKLY) weekly(root.get("view"), start, end);
			else monthly(root.get("view"), start, end);
			return root;
		} catch (RuntimeException e) { throw new RecapTransportException("INVALID_RESPONSE", false); }
	}

	private static void weekly(Object value, LocalDate start, LocalDate end) {
		var view = object(value, "period", "page1_last_week_performance", "page2_growth_report", "page3_academy_success_stories");
		var period = object(view.get("period"), "week_start", "week_end");
		equal(date(period.get("week_start"), false), start); equal(date(period.get("week_end"), false), end.minusDays(1));
		if (start.getDayOfWeek() != java.time.DayOfWeek.MONDAY || !end.equals(start.plusWeeks(1))) invalid();
		var p1 = object(view.get("page1_last_week_performance"), "achievement", "milestone", "streak");
		var achievement = object(p1.get("achievement"), "save_count", "net_savings", "new_wish_count", "message");
		integer(achievement, "save_count", 0, SAFE, false); integer(achievement, "net_savings", -SAFE, SAFE, false);
		integer(achievement, "new_wish_count", 0, SAFE, false); message(achievement, "message", false);
		var milestone = object(p1.get("milestone"), "wish_title", "rate_before", "rate_after", "message");
		string(milestone.get("wish_title"), 0, 200, true); integer(milestone, "rate_before", 0, SAFE, true);
		integer(milestone, "rate_after", 0, SAFE, true); message(milestone, "message", true);
		var streak = object(p1.get("streak"), "streak_weeks", "message");
		integer(streak, "streak_weeks", 0, SAFE, false); message(streak, "message", false);
		var p2 = object(view.get("page2_growth_report"), "total_visits", "unique_visitors", "growth_pct", "message_visits", "message_growth");
		integer(p2, "total_visits", 0, SAFE, false); integer(p2, "unique_visitors", 0, SAFE, false);
		integer(p2, "growth_pct", -SAFE, SAFE, true); message(p2, "message_visits", false); message(p2, "message_growth", true);
		var p3 = object(view.get("page3_academy_success_stories"), "message_summary", "stories");
		message(p3, "message_summary", false);
		if (!(p3.get("stories") instanceof List<?> stories) || stories.size() > 5) invalid();
		for (var item : (List<?>) p3.get("stories")) {
			var story = object(item, "wish_id", "type_title");
			String id = string(story.get("wish_id"), 36, 36, false);
			if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) invalid();
			string(story.get("type_title"), 0, 100, true);
		}
	}

	private static void monthly(Object value, LocalDate start, LocalDate end) {
		var view = object(value, "period", "is_active", "type_section", "objective_performance", "pattern_analysis", "group_comparison", "pace_prediction");
		var period = object(view.get("period"), "year", "month");
		number(period.get("year"), start.getYear(), start.getYear(), true, false);
		number(period.get("month"), start.getMonthValue(), start.getMonthValue(), true, false);
		if (start.getYear() < 1 || start.getYear() > 9999 || start.getDayOfMonth() != 1 || !end.equals(start.plusMonths(1))) invalid();
		if (!(view.get("is_active") instanceof Boolean)) invalid();
		var type = object(view.get("type_section"), "type_title", "message");
		oneOf(type.get("type_title"), false, "불도저형 토끼", "꾸준형 토끼", "단기 집중형 토끼", "탐색형 토끼"); message(type, "message", false);
		var objective = object(view.get("objective_performance"), "total_savings", "completed_wish_count", "representative_wish_title",
				"prev_rate_pct", "curr_rate_pct", "message_total_savings", "message_completed_count", "message_rate_change");
		integer(objective, "total_savings", -SAFE, SAFE, false); integer(objective, "completed_wish_count", 0, SAFE, false);
		string(objective.get("representative_wish_title"), 0, 200, true);
		integer(objective, "prev_rate_pct", -SAFE, SAFE, true); integer(objective, "curr_rate_pct", -SAFE, SAFE, true);
		message(objective, "message_total_savings", false); message(objective, "message_completed_count", false); message(objective, "message_rate_change", true);
		var pattern = object(view.get("pattern_analysis"), "top_week", "top_weekday", "message_week_weekday", "message_regularity", "message_avg_amount");
		integer(pattern, "top_week", 1, 5, true); oneOf(pattern.get("top_weekday"), true, "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일");
		message(pattern, "message_week_weekday", false); message(pattern, "message_regularity", false); message(pattern, "message_avg_amount", false);
		var group = object(view.get("group_comparison"), "habit_percentile", "habit_percentile_status", "achievement_percentile", "achievement_percentile_status", "message_habit", "message_achievement");
		percentile(group, "habit", false); percentile(group, "achievement", true);
		message(group, "message_habit", false); message(group, "message_achievement", true);
		if (group.get("achievement_percentile_status") == null) equal(group.get("message_achievement"), null);
		else message(group, "message_achievement", false);
		var pace = object(view.get("pace_prediction"), "daily_pace", "expected_completion_date", "required_daily_amount", "message_daily_pace", "message_expected_date", "message_required_daily");
		number(pace.get("daily_pace"), -SAFE, SAFE, false, false); date(pace.get("expected_completion_date"), true);
		number(pace.get("required_daily_amount"), 0, SAFE, false, true);
		message(pace, "message_daily_pace", false); message(pace, "message_expected_date", true); message(pace, "message_required_daily", true);
	}

	private static void percentile(Map<String, Object> group, String name, boolean nullable) {
		Object status = group.get(name + "_percentile_status"); oneOf(status, nullable, "ok", "no_peers", "all_tied");
		if ("ok".equals(status)) integer(group, name + "_percentile", 1, 99, false);
		else equal(group.get(name + "_percentile"), null);
	}
	private static void integer(Map<String, Object> map, String key, long min, long max, boolean nullable) { number(map.get(key), min, max, true, nullable); }
	private static void number(Object value, long min, long max, boolean integer, boolean nullable) {
		if (nullable && value == null) return;
		if (!(value instanceof Number)) invalid();
		BigDecimal decimal = new BigDecimal(value.toString());
		if (decimal.compareTo(BigDecimal.valueOf(min)) < 0 || decimal.compareTo(BigDecimal.valueOf(max)) > 0
				|| (integer && decimal.stripTrailingZeros().scale() > 0)) invalid();
	}
	private static void message(Map<String, Object> map, String key, boolean nullable) { string(map.get(key), 1, 1000, nullable); }
	private static String string(Object value, int min, int max, boolean nullable) {
		if (nullable && value == null) return null;
		if (!(value instanceof String)) invalid();
		String s = (String) value; int length = s.codePointCount(0, s.length());
		if (length < min || length > max) invalid(); return s;
	}
	private static LocalDate date(Object value, boolean nullable) {
		if (nullable && value == null) return null;
		String s = string(value, 10, 10, false); LocalDate date = LocalDate.parse(s);
		if (!date.toString().equals(s) || date.getYear() < 1 || date.getYear() > 9999) invalid(); return date;
	}
	private static void oneOf(Object value, boolean nullable, String... values) {
		if (nullable && value == null) return;
		if (!(value instanceof String) || !Set.of(values).contains(value)) invalid();
	}
	private static void equal(Object actual, Object expected) { if (!Objects.equals(actual, expected)) invalid(); }
	private static Map<String, Object> object(Object value, String... keys) {
		var map = castObject(value); if (!map.keySet().equals(Set.of(keys))) invalid(); return map;
	}
	@SuppressWarnings("unchecked") private static Map<String, Object> castObject(Object value) {
		if (!(value instanceof Map<?, ?>)) invalid(); return (Map<String, Object>) value;
	}
	private static void invalid() { throw new IllegalArgumentException("Invalid recap response"); }
}
