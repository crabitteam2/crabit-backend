package com.crabit.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.crabit.backend.e2e.SeedFixtureCatalog;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DemoRecapFixtureCatalogTest {

	private static final Instant CUTOFF = Instant.parse("2026-09-06T05:30:00Z");
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final SeedFixtureCatalog fixtures = new SeedFixtureCatalog();

	@Test
	void oneCutoffBindsTheCompletedPeriodsAndThreeEligibleDeposits() {
		SeedFixtureCatalog.RecapResetFixture recap = fixtures.recapResetFixture(CUTOFF);

		assertThat(recap.weekly()).isEqualTo(new SeedFixtureCatalog.Period(
				LocalDate.parse("2026-08-24"), LocalDate.parse("2026-08-31")));
		assertThat(recap.monthly()).isEqualTo(new SeedFixtureCatalog.Period(
				LocalDate.parse("2026-08-01"), LocalDate.parse("2026-09-01")));
		assertThat(recap.deposits()).hasSize(3);
		assertThat(recap.deposits()).extracting(SeedFixtureCatalog.DepositFixture::amount)
				.containsExactly(100_000L, 75_000L, 75_000L);
		assertThat(recap.deposits()).allSatisfy(deposit -> {
			LocalDate date = deposit.occurredAt().atZone(SEOUL).toLocalDate();
			assertThat(date).isAfterOrEqualTo(recap.monthly().start())
					.isBefore(recap.monthly().endExclusive());
			assertThat(date.isBefore(recap.weekly().start())
					|| !date.isBefore(recap.weekly().endExclusive())).isTrue();
		});
		assertThat(recap.deposits())
				.extracting(SeedFixtureCatalog.DepositFixture::eventId)
				.doesNotHaveDuplicates();
	}

	@Test
	void reservationKeysAreStableForOneTargetAndChangeWithThePeriods() {
		SeedFixtureCatalog.RecapResetFixture first = fixtures.recapResetFixture(CUTOFF);
		SeedFixtureCatalog.RecapResetFixture samePeriods = fixtures.recapResetFixture(
				CUTOFF.plusSeconds(3_600));
		SeedFixtureCatalog.RecapResetFixture laterPeriods = fixtures.recapResetFixture(
				Instant.parse("2026-10-10T05:30:00Z"));

		assertThat(samePeriods.weeklyRequestKey()).isEqualTo(first.weeklyRequestKey());
		assertThat(samePeriods.monthlyRequestKey()).isEqualTo(first.monthlyRequestKey());
		assertThat(laterPeriods.weeklyRequestKey()).isNotEqualTo(first.weeklyRequestKey());
		assertThat(laterPeriods.monthlyRequestKey()).isNotEqualTo(first.monthlyRequestKey());
	}
}
