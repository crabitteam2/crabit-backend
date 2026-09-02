package com.crabit.backend.wish;

public final class WishDateRangeException extends IllegalArgumentException {

	public static final String MESSAGE = "startDate must be on or before targetDate.";

	public WishDateRangeException() {
		super(MESSAGE);
	}
}
