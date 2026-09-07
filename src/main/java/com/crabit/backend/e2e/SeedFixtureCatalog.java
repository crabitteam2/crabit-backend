package com.crabit.backend.e2e;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"e2e", "demo"})
public final class SeedFixtureCatalog {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final long DEMO_CARD_BALANCE = 2_000_000L;
	private static final List<Long> RECAP_DEPOSIT_AMOUNTS = List.of(100_000L, 75_000L, 75_000L);

	public static final UUID PRIMARY_ACADEMY_ID = id("00000000-0000-0000-0000-000000000101");
	public static final UUID OTHER_ACADEMY_ID = id("00000000-0000-0000-0000-000000000102");
	public static final UUID OWNER_ID = id("00000000-0000-0000-0000-000000000201");
	public static final UUID FRIEND_ID = id("00000000-0000-0000-0000-000000000202");
	public static final UUID NONFRIEND_ID = id("00000000-0000-0000-0000-000000000203");
	public static final UUID BLOCKED_ID = id("00000000-0000-0000-0000-000000000204");
	public static final UUID OTHER_ACADEMY_STUDENT_ID = id("00000000-0000-0000-0000-000000000205");
	public static final UUID STAFF_ID = id("00000000-0000-0000-0000-000000000206");
	public static final UUID OWNER_ACCOUNT_ID = id("00000000-0000-0000-0000-000000000301");
	public static final UUID LAPTOP_WISH_ID = id("00000000-0000-0000-0000-000000000401");
	public static final UUID CAMP_WISH_ID = id("00000000-0000-0000-0000-000000000402");

	public static final String OWNER_TOKEN = "seed-owner-token";
	public static final String FRIEND_TOKEN = "seed-friend-token";
	public static final String NONFRIEND_TOKEN = "seed-nonfriend-token";
	public static final String BLOCKED_TOKEN = "seed-blocked-token";
	public static final String OTHER_ACADEMY_TOKEN = "seed-other-academy-token";
	public static final String STAFF_TOKEN = "seed-staff-token";

	public static final Instant FIXTURE_TIME = Instant.parse("2026-08-16T00:00:00Z");

	public List<Persona> personas() {
		return List.of(
				new Persona(OWNER_ID, "owner", "오너", 15, SeedPrincipal.Role.STUDENT,
						PRIMARY_ACADEMY_ID, OWNER_TOKEN, true),
				new Persona(FRIEND_ID, "same-academy-friend", "친구", 15, SeedPrincipal.Role.STUDENT,
						PRIMARY_ACADEMY_ID, FRIEND_TOKEN, true),
				new Persona(NONFRIEND_ID, "same-academy-nonfriend", "같은 학원 학생", 16, SeedPrincipal.Role.STUDENT,
						PRIMARY_ACADEMY_ID, NONFRIEND_TOKEN, true),
				new Persona(BLOCKED_ID, "blocked-student", "차단 학생", 17, SeedPrincipal.Role.STUDENT,
						PRIMARY_ACADEMY_ID, BLOCKED_TOKEN, true),
				new Persona(OTHER_ACADEMY_STUDENT_ID, "other-academy-student", "다른 학원 학생", 16,
						SeedPrincipal.Role.STUDENT, OTHER_ACADEMY_ID, OTHER_ACADEMY_TOKEN, true),
				new Persona(STAFF_ID, "same-academy-staff", "같은 학원 선생님", 30, SeedPrincipal.Role.STAFF,
						PRIMARY_ACADEMY_ID, STAFF_TOKEN, false));
	}

	public List<WishFixture> wishes() {
		return List.of(
				new WishFixture(LAPTOP_WISH_ID, "노트북", 1_500_000L, 250_000L,
						"IN_PROGRESS", "FOLLOWERS", LocalDate.of(2026, 12, 31)),
				new WishFixture(CAMP_WISH_ID, "여름 캠프", 500_000L, 500_000L,
						"AMOUNT_REACHED", "ACADEMY", LocalDate.of(2026, 9, 1)));
	}

	public RecapResetFixture recapResetFixture(Instant cutoff) {
		LocalDate currentDate = Objects.requireNonNull(cutoff, "cutoff")
				.atZone(SEOUL).toLocalDate();
		LocalDate currentWeekStart = currentDate
				.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		Period weekly = new Period(currentWeekStart.minusWeeks(1), currentWeekStart);
		LocalDate currentMonthStart = currentDate.withDayOfMonth(1);
		Period monthly = new Period(currentMonthStart.minusMonths(1), currentMonthStart);

		LocalDate fixtureCreationDate = FIXTURE_TIME.atZone(SEOUL).toLocalDate();
		List<LocalDate> eligibleDates = monthly.start().datesUntil(monthly.endExclusive())
				.filter(date -> date.isAfter(fixtureCreationDate))
				.filter(date -> date.isBefore(weekly.start())
						|| !date.isBefore(weekly.endExclusive()))
				.toList();
		if (eligibleDates.size() < RECAP_DEPOSIT_AMOUNTS.size()) {
			throw new IllegalStateException(
					"The completed month cannot hold the deterministic Demo recap fixture");
		}

		int last = eligibleDates.size() - 1;
		List<LocalDate> selectedDates = List.of(
				eligibleDates.get(0), eligibleDates.get(last / 2), eligibleDates.get(last));
		List<DepositFixture> deposits = new ArrayList<>();
		for (int index = 0; index < RECAP_DEPOSIT_AMOUNTS.size(); index++) {
			String target = monthly.start() + ":" + monthly.endExclusive() + ":" + (index + 1);
			deposits.add(new DepositFixture(
					stableId("observation:" + target),
					stableId("deposit-event:" + target),
					stableId("deposit-effect:" + target),
					selectedDates.get(index).atTime(LocalTime.NOON).atZone(SEOUL).toInstant(),
					RECAP_DEPOSIT_AMOUNTS.get(index)));
		}

		return new RecapResetFixture(
				cutoff,
				weekly,
				monthly,
				stableId("balance-baseline:" + monthly.start() + ":" + monthly.endExclusive()),
				List.copyOf(deposits),
				stableId(reservationTarget("WEEKLY", weekly)),
				stableId(reservationTarget("MONTHLY", monthly)),
				DEMO_CARD_BALANCE);
	}

	private static String reservationTarget(String kind, Period period) {
		return "recap-reservation:" + OWNER_ACCOUNT_ID + ":" + kind + ":"
				+ period.start() + ":" + period.endExclusive();
	}

	private static UUID stableId(String target) {
		return UUID.nameUUIDFromBytes(("crabit-stable-demo-recap-v1:" + target).getBytes(UTF_8));
	}

	private static UUID id(String value) {
		return UUID.fromString(value);
	}

	public record Persona(
			UUID id,
			String key,
			String displayName,
			int age,
			SeedPrincipal.Role role,
			UUID academyId,
			String token,
			boolean persistedStudent) {
	}

	public record WishFixture(
			UUID id,
			String purpose,
			long targetAmount,
			long wishAmount,
			String state,
			String visibility,
			LocalDate targetDate) {
	}

	public record Period(LocalDate start, LocalDate endExclusive) {
		public Period {
			Objects.requireNonNull(start, "start");
			Objects.requireNonNull(endExclusive, "endExclusive");
			if (!start.isBefore(endExclusive)) {
				throw new IllegalArgumentException("Recap period must be non-empty");
			}
		}
	}

	public record DepositFixture(
			UUID observationId,
			UUID eventId,
			UUID effectId,
			Instant occurredAt,
			long amount) {
		public DepositFixture {
			Objects.requireNonNull(observationId, "observationId");
			Objects.requireNonNull(eventId, "eventId");
			Objects.requireNonNull(effectId, "effectId");
			Objects.requireNonNull(occurredAt, "occurredAt");
			if (amount <= 0) throw new IllegalArgumentException("Deposit amount must be positive");
		}
	}

	public record RecapResetFixture(
			Instant cutoff,
			Period weekly,
			Period monthly,
			UUID balanceBaselineEventId,
			List<DepositFixture> deposits,
			UUID weeklyRequestKey,
			UUID monthlyRequestKey,
			long actualCardBalance) {
		public RecapResetFixture {
			Objects.requireNonNull(cutoff, "cutoff");
			Objects.requireNonNull(weekly, "weekly");
			Objects.requireNonNull(monthly, "monthly");
			Objects.requireNonNull(balanceBaselineEventId, "balanceBaselineEventId");
			deposits = List.copyOf(deposits);
			Objects.requireNonNull(weeklyRequestKey, "weeklyRequestKey");
			Objects.requireNonNull(monthlyRequestKey, "monthlyRequestKey");
			if (deposits.size() != 3) {
				throw new IllegalArgumentException("Demo recap fixture requires exactly three deposits");
			}
			if (actualCardBalance <= 0) {
				throw new IllegalArgumentException("Demo card balance must be positive");
			}
		}
	}
}
