package com.crabit.backend.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountDomainInvariantTest {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	@Test
	void accountRulesRejectASecondActiveLogicalAccountForTheSameStudentAndAcademy() {
		UUID studentId = UUID.randomUUID();
		UUID academyId = UUID.randomUUID();
		CardBalanceAccount active = CardBalanceAccount.open(studentId, academyId, NOW);

		assertThatThrownBy(() -> CardBalanceAccountRules.assertCanOpen(
				studentId, academyId, List.of(active)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("active");

		active.close(NOW.plusSeconds(1));
		CardBalanceAccountRules.assertCanOpen(studentId, academyId, List.of(active));
	}
}
