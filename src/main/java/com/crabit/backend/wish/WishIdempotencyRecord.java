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
		WishSnapshot destinationSnapshot,
		UUID eventId,
		Instant occurredAt,
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
				operation, targetId, fingerprint, status, wish, null, eventId,
				eventId == null ? null : recordedAt, recordedAt);
	}

	static WishIdempotencyRecord captureTransfer(
			String operation,
			UUID targetId,
			String fingerprint,
			int status,
			WishSnapshot sourceWish,
			WishSnapshot destinationWish,
			UUID eventId,
			Instant occurredAt,
			Instant recordedAt) {
		return new WishIdempotencyRecord(
				operation, targetId, fingerprint, status, sourceWish,
				Objects.requireNonNull(destinationWish, "destinationWish"),
				Objects.requireNonNull(eventId, "eventId"),
				Objects.requireNonNull(occurredAt, "occurredAt"), recordedAt);
	}

	boolean matches(String requestedOperation, UUID requestedTarget, String requestedFingerprint) {
		return operation.equals(requestedOperation)
				&& targetId.equals(requestedTarget)
				&& requestFingerprint.equals(requestedFingerprint);
	}
}
