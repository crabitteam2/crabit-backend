package com.crabit.backend.relationship;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "FriendRequestStatus", enumAsRef = true,
		description = "Current request lifecycle state; processed requests never return to PENDING.")
public enum FriendRequestStatus {
	PENDING,
	ACCEPTED,
	REJECTED,
	CANCELED
}
