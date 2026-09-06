package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RecapPeriodsTest {
	private final Clock clock=Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);
	@Test void selectsOnlyCompletedSeoulPeriods(){
		assertThat(RecapPeriods.weekly(null,clock).start()).isEqualTo(LocalDate.parse("2026-08-31"));
		assertThat(RecapPeriods.monthly(null,clock).start()).isEqualTo(LocalDate.parse("2026-08-01"));
		assertThatThrownBy(()->RecapPeriods.weekly("2026-09-07",clock)).isInstanceOf(RecapException.class);
		assertThatThrownBy(()->RecapPeriods.monthly("2026-09",clock)).isInstanceOf(RecapException.class);
	}
}
