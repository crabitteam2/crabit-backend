package com.crabit.backend.wishphoto;

public final class WishPhotoException extends RuntimeException {
	private final Code code;
	private final int retryAfterSeconds;

	public WishPhotoException(Code code, String message) { this(code, message, 0); }
	public WishPhotoException(Code code, String message, int retryAfterSeconds) {
		super(message);
		this.code = code;
		this.retryAfterSeconds = retryAfterSeconds;
	}
	public Code code() { return code; }
	public int retryAfterSeconds() { return retryAfterSeconds; }

	public enum Code {
		MALFORMED_REQUEST, IDEMPOTENCY_KEY_REQUIRED, IDEMPOTENCY_KEY_REUSED,
		WISH_PHOTO_NOT_FOUND, WISH_PHOTO_EXPIRED, WISH_PHOTO_ALREADY_ATTACHED,
		PHOTO_TOO_LARGE, UNSUPPORTED_PHOTO_TYPE, INVALID_PHOTO, PHOTO_CONTENT_NOT_ALLOWED,
		PHOTO_UPLOAD_RATE_LIMITED, PHOTO_PROCESSING_UNAVAILABLE, PHOTO_DELIVERY_UNAVAILABLE
	}
}
