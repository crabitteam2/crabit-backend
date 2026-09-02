package com.crabit.backend.wishphoto.googlecloud;

import java.time.Clock;
import java.util.Objects;

/** A typed seam: never replaces or makes ambiguous the fixture/domain Clock. */
public record WishPhotoClock(Clock value) {
	public WishPhotoClock { Objects.requireNonNull(value); }
}
