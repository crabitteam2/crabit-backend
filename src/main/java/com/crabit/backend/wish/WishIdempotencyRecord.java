package com.crabit.backend.wish;

import com.crabit.backend.wishphoto.WishPhotoView;
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
		PhotoReplayState photoReplayState,
		PhotoReplayState destinationPhotoReplayState,
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
				operation, targetId, fingerprint, status, wish.withPhoto(null), null,
				PhotoReplayState.capture(wish.photo()), null, eventId,
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
				operation, targetId, fingerprint, status, sourceWish.withPhoto(null),
				Objects.requireNonNull(destinationWish, "destinationWish").withPhoto(null),
				PhotoReplayState.capture(sourceWish.photo()),
				PhotoReplayState.capture(destinationWish.photo()),
				Objects.requireNonNull(eventId, "eventId"),
				Objects.requireNonNull(occurredAt, "occurredAt"), recordedAt);
	}

	WishIdempotencyRecord normalizedLegacyPhotoStates() {
		PhotoReplayState source = photoReplayState == null
				? PhotoReplayState.noPhoto()
				: photoReplayState;
		PhotoReplayState destination = destinationSnapshot != null
				&& destinationPhotoReplayState == null
						? PhotoReplayState.noPhoto()
						: destinationPhotoReplayState;
		if (source == photoReplayState && destination == destinationPhotoReplayState) {
			return this;
		}
		return new WishIdempotencyRecord(operation, targetId, requestFingerprint, httpStatus,
				snapshot.withPhoto(null),
				destinationSnapshot == null ? null : destinationSnapshot.withPhoto(null),
				source, destination, eventId, occurredAt, recordedAt);
	}

	boolean hasLegacyPhotoState() {
		return photoReplayState == null
				|| (destinationSnapshot != null && destinationPhotoReplayState == null);
	}

	boolean matches(String requestedOperation, UUID requestedTarget, String requestedFingerprint) {
		return operation.equals(requestedOperation)
				&& targetId.equals(requestedTarget)
				&& requestFingerprint.equals(requestedFingerprint);
	}

	record PhotoReplayState(Kind kind, UUID photoId) {
		PhotoReplayState {
			Objects.requireNonNull(kind, "kind");
			if ((kind == Kind.ACTIVE_PHOTO) != (photoId != null)) {
				throw new IllegalArgumentException(
						"photoId must exist only for ACTIVE_PHOTO replay state");
			}
		}

		static PhotoReplayState capture(WishPhotoView photo) {
			return photo == null ? noPhoto() : new PhotoReplayState(Kind.ACTIVE_PHOTO, photo.id());
		}

		static PhotoReplayState noPhoto() {
			return new PhotoReplayState(Kind.NO_PHOTO, null);
		}

		enum Kind {
			NO_PHOTO,
			ACTIVE_PHOTO,
			PHOTO_REVOKED
		}
	}
}
