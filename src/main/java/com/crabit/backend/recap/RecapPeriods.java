package com.crabit.backend.recap;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;

public final class RecapPeriods {
	public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private RecapPeriods() {}
	public static Period weekly(String value, Clock clock) {
		LocalDate currentStart = LocalDate.now(clock.withZone(SEOUL)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		LocalDate start = value == null ? currentStart.minusWeeks(1) : parseDate(value);
		if (start.getDayOfWeek() != DayOfWeek.MONDAY || start.plusWeeks(1).isAfter(currentStart)) throw malformed();
		return new Period(start, start.plusWeeks(1));
	}
	public static Period monthly(String value, Clock clock) {
		YearMonth current = YearMonth.now(clock.withZone(SEOUL));
		YearMonth month;
		try { month = value == null ? current.minusMonths(1) : YearMonth.parse(value, DateTimeFormatter.ofPattern("uuuu-MM")); }
		catch (DateTimeParseException e) { throw malformed(); }
		if (!month.isBefore(current) || (value != null && !month.toString().equals(value))) throw malformed();
		return new Period(month.atDay(1), month.plusMonths(1).atDay(1));
	}
	private static LocalDate parseDate(String value) { try { return LocalDate.parse(value); } catch (DateTimeParseException e) { throw malformed(); } }
	private static RecapException malformed() { return new RecapException(RecapException.Code.MALFORMED_REQUEST, "The recap period is malformed or incomplete."); }
	public record Period(LocalDate start, LocalDate endExclusive) {}
}
