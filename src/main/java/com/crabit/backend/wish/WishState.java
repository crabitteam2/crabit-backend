package com.crabit.backend.wish;

public enum WishState {
	IN_PROGRESS,
	AMOUNT_REACHED,
	COMPLETED,
	ABANDONED;

	public boolean isActive() {
		return this == IN_PROGRESS || this == AMOUNT_REACHED;
	}

	public boolean isTerminal() {
		return this == COMPLETED || this == ABANDONED;
	}
}
