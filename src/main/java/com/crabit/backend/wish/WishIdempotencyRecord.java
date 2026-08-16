package com.crabit.backend.wish;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

record WishIdempotencyRecord(
		String operation,
		UUID targetId,
		String requestFingerprint,
		int httpStatus,
		WishSnapshot snapshot,
		UUID eventId,
		Instant recordedAt) {

	WishIdempotencyRecord {
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(targetId, "targetId");
		Objects.requireNonNull(requestFingerprint, "requestFingerprint");
		Objects.requireNonNull(snapshot, "snapshot");
		Objects.requireNonNull(recordedAt, "recordedAt");
	}

	static WishIdempotencyRecord capture(
			String operation,
			UUID targetId,
			String fingerprint,
			int status,
			WishSnapshot wish,
			UUID eventId,
			Instant recordedAt) {
		return new WishIdempotencyRecord(
				operation, targetId, fingerprint, status, wish, eventId, recordedAt);
	}

	boolean matches(String requestedOperation, UUID requestedTarget, String requestedFingerprint) {
		return operation.equals(requestedOperation)
				&& targetId.equals(requestedTarget)
				&& requestFingerprint.equals(requestedFingerprint);
	}
}
