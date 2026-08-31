package com.crabit.backend.wish;

import java.time.LocalDate;
import java.util.Optional;

public record WishPatch(
		String purposeValue,
		KrwAmount targetAmountValue,
		boolean startDatePresent,
		LocalDate startDate,
		boolean targetDatePresent,
		LocalDate targetDate,
		WishVisibility visibility) {

	public WishPatch(
			String purposeValue,
			KrwAmount targetAmountValue,
			boolean targetDatePresent,
			LocalDate targetDate,
			WishVisibility visibility) {
		this(purposeValue, targetAmountValue, false, null,
				targetDatePresent, targetDate, visibility);
	}

	public Optional<String> purpose() {
		return Optional.ofNullable(purposeValue);
	}

	public Optional<KrwAmount> targetAmount() {
		return Optional.ofNullable(targetAmountValue);
	}

	public Optional<WishVisibility> visibilityValue() {
		return Optional.ofNullable(visibility);
	}

	public boolean onlyVisibility() {
		return purposeValue == null && targetAmountValue == null
				&& !startDatePresent && !targetDatePresent && visibility != null;
	}
}
