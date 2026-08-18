package com.crabit.backend.api;

import com.crabit.backend.wish.WishLifecycleException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class WishApiExceptionHandler {

	private static final Pattern FUND_MOVEMENT_PATH = Pattern.compile(
			"^/v1/card-balance-accounts/[^/]+/(?:wishes/[^/]+/(?:deposits|withdrawals)|transfers)$");

	@ExceptionHandler(WishLifecycleException.class)
	public ResponseEntity<ErrorEnvelope> lifecycle(WishLifecycleException exception) {
		HttpStatus status = status(exception.code());
		List<FieldError> fields = exception.field() == null
				? List.of()
				: List.of(new FieldError(exception.field(), exception.getMessage()));
		return ResponseEntity.status(status).body(new ErrorEnvelope(new ApiError(
				exception.code().name(),
				exception.getMessage(),
				exception.code() == WishLifecycleException.Code.BALANCE_SYNC_FAILED,
				UUID.randomUUID().toString(),
				fields,
				exception.details())));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorEnvelope> mediaType(
			HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
		if ("POST".equals(request.getMethod())
				&& FUND_MOVEMENT_PATH.matcher(request.getRequestURI()).matches()) {
			return lifecycle(new WishLifecycleException(
					WishLifecycleException.Code.MALFORMED_REQUEST,
					"The request is malformed."));
		}
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
			case FORBIDDEN -> HttpStatus.FORBIDDEN;
			case BALANCE_SYNC_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
			case ACADEMY_NOT_FOUND, SHARED_CARD_NOT_FOUND,
					CARD_BALANCE_ACCOUNT_NOT_FOUND, WISH_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case VERSION_CONFLICT, INVALID_STATE_TRANSITION,
					BALANCE_MISMATCH_LOCKED, IDEMPOTENCY_KEY_REUSED,
					INSUFFICIENT_AVAILABLE_BALANCE, INSUFFICIENT_WISH_AMOUNT,
					TARGET_AMOUNT_EXCEEDED, CROSS_ACCOUNT_TRANSFER_FORBIDDEN ->
					HttpStatus.CONFLICT;
			case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
			case INVALID_AMOUNT, INVALID_PURPOSE, INVALID_VERSION ->
					HttpStatus.UNPROCESSABLE_CONTENT;
			case MALFORMED_REQUEST, IDEMPOTENCY_KEY_REQUIRED, EXPECTED_VERSION_REQUIRED ->
					HttpStatus.BAD_REQUEST;
		};
	}

	@Schema(
			name = "ErrorEnvelope",
			description = "The common application error wrapper.",
			example = """
					{"error":{"code":"MALFORMED_REQUEST","message":"The request is malformed.",
					"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
					"fieldErrors":[],"details":{}}}
					""")
	public record ErrorEnvelope(
			@Schema(description = "Structured error payload shared by every declared non-success JSON "
					+ "response.",
					requiredMode = Schema.RequiredMode.REQUIRED) ApiError error) {
	}

	@Schema(
			name = "ApiError",
			description = "A stable machine-readable error plus human-readable details.",
			example = """
					{"code":"INVALID_AMOUNT",
					"message":"targetAmount must be a positive JavaScript-safe integer.",
					"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
					"fieldErrors":[{"field":"targetAmount",
					"message":"targetAmount must be a positive JavaScript-safe integer."}],"details":{}}
					""")
	public record ApiError(
			@Schema(description = "Stable machine-readable ErrorCode; clients should branch on this value "
					+ "rather than message text.",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "MALFORMED_REQUEST") String code,
			@Schema(description = "Human-readable explanation of this occurrence; it is not the stable "
					+ "machine decision key.",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "The request is malformed.") String message,
			@Schema(description = "True only for BALANCE_SYNC_FAILED; false for every defined client, "
					+ "authorization, not-found, validation, and state-conflict error.",
					requiredMode = Schema.RequiredMode.REQUIRED, example = "false") boolean retryable,
			@Schema(description = "Opaque server correlation identifier for diagnostics and support; it "
					+ "has no domain meaning.",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "8f870810-a9d8-4b84-bf13-f83a2b74a136") String traceId,
			@ArraySchema(
					arraySchema = @Schema(
							description = "Field-specific validation failures; empty when the error is not "
									+ "attributable to individual request fields.",
							requiredMode = Schema.RequiredMode.REQUIRED),
					schema = @Schema(implementation = FieldError.class))
			List<FieldError> fieldErrors,
			@Schema(description = "Extensible code-specific metadata object; empty when no details apply, "
					+ "and clients must ignore unrecognized keys.",
					requiredMode = Schema.RequiredMode.REQUIRED,
					additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
					example = "{}") Map<String, Object> details) {
	}

	@Schema(
			name = "FieldError",
			description = "A field-specific validation failure.",
			example = "{\"field\":\"targetAmount\","
					+ "\"message\":\"targetAmount must be a positive JavaScript-safe integer.\"}")
	public record FieldError(
			@Schema(description = "Name of the invalid request field, parameter, or header associated with "
					+ "this validation failure.",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "targetAmount") String field,
			@Schema(description = "Human-readable explanation of the field-specific failure.",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "targetAmount must be a positive JavaScript-safe integer.") String message) {
	}
}
