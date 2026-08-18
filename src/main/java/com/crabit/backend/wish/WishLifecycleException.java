package com.crabit.backend.wish;

import java.util.Map;

public final class WishLifecycleException extends RuntimeException {

	private final Code code;
	private final String field;
	private final Map<String, Object> details;

	public WishLifecycleException(Code code, String message) {
		this(code, message, null, Map.of());
	}

	public WishLifecycleException(Code code, String message, String field) {
		this(code, message, field, Map.of());
	}

	public WishLifecycleException(
			Code code, String message, String field, Map<String, Object> details) {
		super(message);
		this.code = code;
		this.field = field;
		this.details = Map.copyOf(details);
	}

	public Code code() {
		return code;
	}

	public String field() {
		return field;
	}

	public Map<String, Object> details() {
		return details;
	}

	public enum Code {
		MALFORMED_REQUEST,
		IDEMPOTENCY_KEY_REQUIRED,
		EXPECTED_VERSION_REQUIRED,
		AUTH_REQUIRED,
		FORBIDDEN,
		CARD_BALANCE_ACCOUNT_NOT_FOUND,
		WISH_NOT_FOUND,
		VERSION_CONFLICT,
		INVALID_STATE_TRANSITION,
		BALANCE_MISMATCH_LOCKED,
		IDEMPOTENCY_KEY_REUSED,
		UNSUPPORTED_MEDIA_TYPE,
		INVALID_AMOUNT,
		INVALID_PURPOSE,
		INVALID_VERSION,
		BALANCE_SYNC_FAILED,
		INSUFFICIENT_AVAILABLE_BALANCE,
		INSUFFICIENT_WISH_AMOUNT,
		TARGET_AMOUNT_EXCEEDED,
		CROSS_ACCOUNT_TRANSFER_FORBIDDEN
	}
}
