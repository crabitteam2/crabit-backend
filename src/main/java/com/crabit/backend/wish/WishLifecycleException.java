package com.crabit.backend.wish;

import java.util.List;
import java.util.Map;

public final class WishLifecycleException extends RuntimeException {

	private final Code code;
	private final String field;
	private final List<FieldViolation> fieldErrors;
	private final Map<String, Object> details;

	public WishLifecycleException(Code code, String message) {
		this(code, message, (String) null, Map.of());
	}

	public WishLifecycleException(Code code, String message, String field) {
		this(code, message, field, Map.of());
	}

	public WishLifecycleException(
			Code code, String message, String field, Map<String, Object> details) {
		this(code, message,
				details,
				field == null ? List.of() : List.of(new FieldViolation(field, message)));
	}

	private WishLifecycleException(
			Code code,
			String message,
			Map<String, Object> details,
			List<FieldViolation> fieldErrors) {
		super(message);
		this.code = code;
		this.fieldErrors = List.copyOf(fieldErrors);
		this.field = this.fieldErrors.size() == 1 ? this.fieldErrors.getFirst().field() : null;
		this.details = Map.copyOf(details);
	}

	public Code code() {
		return code;
	}

	public String field() {
		return field;
	}

	public List<FieldViolation> fieldErrors() {
		return fieldErrors;
	}

	public Map<String, Object> details() {
		return details;
	}

	public static WishLifecycleException invalidDateRange() {
		return new WishLifecycleException(
				Code.INVALID_DATE_RANGE,
				WishDateRangeException.MESSAGE,
				Map.of(),
				List.of(
						new FieldViolation("startDate", WishDateRangeException.MESSAGE),
						new FieldViolation(
								"targetDate", "targetDate must be on or after startDate.")));
	}

	public record FieldViolation(String field, String message) {
	}

	public enum Code {
		MALFORMED_REQUEST,
		IDEMPOTENCY_KEY_REQUIRED,
		EXPECTED_VERSION_REQUIRED,
		AUTH_REQUIRED,
		FORBIDDEN,
		ACADEMY_NOT_FOUND,
		SHARED_CARD_NOT_FOUND,
		CARD_BALANCE_ACCOUNT_NOT_FOUND,
		WISH_NOT_FOUND,
		VERSION_CONFLICT,
		INVALID_STATE_TRANSITION,
		BALANCE_MISMATCH_LOCKED,
		IDEMPOTENCY_KEY_REUSED,
		UNSUPPORTED_MEDIA_TYPE,
		INVALID_AMOUNT,
		INVALID_PURPOSE,
		INVALID_DATE_RANGE,
		INVALID_VERSION,
		BALANCE_SYNC_FAILED,
		INSUFFICIENT_AVAILABLE_BALANCE,
		INSUFFICIENT_WISH_AMOUNT,
		TARGET_AMOUNT_EXCEEDED,
		CROSS_ACCOUNT_TRANSFER_FORBIDDEN
	}
}
