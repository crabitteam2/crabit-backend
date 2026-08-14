package com.crabit.backend.wish;

import java.util.Objects;

/** Integer Korean won. Signed values are allowed for derived ledger balances. */
public final class KrwAmount implements Comparable<KrwAmount> {

	private static final KrwAmount ZERO = new KrwAmount(0);

	private final long won;

	private KrwAmount(long won) {
		this.won = won;
	}

	public static KrwAmount of(long won) {
		return won == 0 ? ZERO : new KrwAmount(won);
	}

	public static KrwAmount zero() {
		return ZERO;
	}

	public static KrwAmount positive(long won) {
		if (won <= 0) {
			throw new IllegalArgumentException("KRW amount must be positive");
		}
		return of(won);
	}

	public static KrwAmount nonNegative(long won) {
		if (won < 0) {
			throw new IllegalArgumentException("KRW amount must be non-negative");
		}
		return of(won);
	}

	public long won() {
		return won;
	}

	public KrwAmount plus(KrwAmount other) {
		return of(Math.addExact(won, require(other).won));
	}

	public KrwAmount minus(KrwAmount other) {
		return of(Math.subtractExact(won, require(other).won));
	}

	public KrwAmount negate() {
		return of(Math.negateExact(won));
	}

	public KrwAmount absolute() {
		return won < 0 ? negate() : this;
	}

	public boolean isPositive() {
		return won > 0;
	}

	public boolean isNegative() {
		return won < 0;
	}

	public boolean isZero() {
		return won == 0;
	}

	@Override
	public int compareTo(KrwAmount other) {
		return Long.compare(won, require(other).won);
	}

	private static KrwAmount require(KrwAmount amount) {
		return Objects.requireNonNull(amount, "amount");
	}

	@Override
	public boolean equals(Object other) {
		return this == other || other instanceof KrwAmount amount && won == amount.won;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(won);
	}

	@Override
	public String toString() {
		return won + " KRW";
	}
}
