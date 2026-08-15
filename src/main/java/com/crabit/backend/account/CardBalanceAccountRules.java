package com.crabit.backend.account;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

public final class CardBalanceAccountRules {

	private CardBalanceAccountRules() {
	}

	public static void assertCanOpen(
			UUID studentId, UUID academyId, Collection<CardBalanceAccount> existingAccounts) {
		Objects.requireNonNull(studentId, "studentId");
		Objects.requireNonNull(academyId, "academyId");
		Objects.requireNonNull(existingAccounts, "existingAccounts");
		boolean duplicate = existingAccounts.stream().anyMatch(account ->
				account.isActive()
						&& studentId.equals(account.studentId())
						&& academyId.equals(account.academyId()));
		if (duplicate) {
			throw new IllegalStateException("Student already has an active Card Balance Account for academy");
		}
	}
}
