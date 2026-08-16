package com.crabit.backend.wish;

public final class WishLifecycleException extends RuntimeException {

	private final Code code;
	private final String field;

	public WishLifecycleException(Code code, String message) {
		this(code, message, null);
	}

	public WishLifecycleException(Code code, String message, String field) {
		super(message);
		this.code = code;
		this.field = field;
	}

	public Code code() {
		return code;
	}

	public String field() {
		return field;
	}

	public enum Code {
		MALFORMED_REQUEST,
		IDEMPOTENCY_KEY_REQUIRED,
		EXPECTED_VERSION_REQUIRED,
		AUTH_REQUIRED,
		CARD_BALANCE_ACCOUNT_NOT_FOUND,
		WISH_NOT_FOUND,
		VERSION_CONFLICT,
		INVALID_STATE_TRANSITION,
		BALANCE_MISMATCH_LOCKED,
		IDEMPOTENCY_KEY_REUSED,
		UNSUPPORTED_MEDIA_TYPE,
		INVALID_AMOUNT,
		INVALID_PURPOSE,
		INVALID_VERSION
	}
}
