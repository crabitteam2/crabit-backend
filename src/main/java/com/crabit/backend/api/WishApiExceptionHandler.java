package com.crabit.backend.api;

import com.crabit.backend.wish.WishLifecycleException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class WishApiExceptionHandler {

	@ExceptionHandler(WishLifecycleException.class)
	public ResponseEntity<ErrorEnvelope> lifecycle(WishLifecycleException exception) {
		HttpStatus status = status(exception.code());
		List<FieldError> fields = exception.field() == null
				? List.of()
				: List.of(new FieldError(exception.field(), exception.getMessage()));
		return ResponseEntity.status(status).body(new ErrorEnvelope(new ApiError(
				exception.code().name(),
				exception.getMessage(),
				false,
				UUID.randomUUID().toString(),
				fields,
				Map.of())));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorEnvelope> mediaType(HttpMediaTypeNotSupportedException exception) {
		return lifecycle(new WishLifecycleException(
				WishLifecycleException.Code.UNSUPPORTED_MEDIA_TYPE,
				"PATCH requires application/merge-patch+json."));
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
	public ResponseEntity<ErrorEnvelope> malformed(Exception exception) {
		return lifecycle(new WishLifecycleException(
				WishLifecycleException.Code.MALFORMED_REQUEST,
				"The request is malformed."));
	}

	private static HttpStatus status(WishLifecycleException.Code code) {
		return switch (code) {
			case AUTH_REQUIRED -> HttpStatus.UNAUTHORIZED;
			case CARD_BALANCE_ACCOUNT_NOT_FOUND, WISH_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case VERSION_CONFLICT, INVALID_STATE_TRANSITION,
					BALANCE_MISMATCH_LOCKED, IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
			case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
			case INVALID_AMOUNT, INVALID_PURPOSE, INVALID_VERSION ->
					HttpStatus.UNPROCESSABLE_CONTENT;
			case MALFORMED_REQUEST, IDEMPOTENCY_KEY_REQUIRED, EXPECTED_VERSION_REQUIRED ->
					HttpStatus.BAD_REQUEST;
		};
	}

	public record ErrorEnvelope(ApiError error) {
	}

	public record ApiError(
			String code,
			String message,
			boolean retryable,
			String traceId,
			List<FieldError> fieldErrors,
			Map<String, Object> details) {
	}

	public record FieldError(String field, String message) {
	}
}
