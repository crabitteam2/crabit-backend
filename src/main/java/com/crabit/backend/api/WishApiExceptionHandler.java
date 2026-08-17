package com.crabit.backend.api;

import com.crabit.backend.wish.WishLifecycleException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
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

	@Schema(
			name = "ErrorEnvelope",
			description = "The common application error wrapper.",
			example = """
					{"error":{"code":"MALFORMED_REQUEST","message":"The request is malformed.",
					"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
					"fieldErrors":[],"details":{}}}
					""")
	public record ErrorEnvelope(
			@Schema(description = "Required application error details.",
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
			@Schema(description = "Required documented machine-readable error code.",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "MALFORMED_REQUEST") String code,
			@Schema(description = "Required human-readable error message.",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "The request is malformed.") String message,
			@Schema(description = "Required retryability flag; current Wish errors use false.",
					requiredMode = Schema.RequiredMode.REQUIRED, example = "false") boolean retryable,
			@Schema(description = "Required opaque trace identifier.",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "8f870810-a9d8-4b84-bf13-f83a2b74a136") String traceId,
			@ArraySchema(
					arraySchema = @Schema(
							description = "Required field errors; empty when no field is identified.",
							requiredMode = Schema.RequiredMode.REQUIRED),
					schema = @Schema(implementation = FieldError.class))
			List<FieldError> fieldErrors,
			@Schema(description = "Required extensible details object; currently empty for Wish errors.",
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
			@Schema(description = "Required field or header name.",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "targetAmount") String field,
			@Schema(description = "Required validation message.",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "targetAmount must be a positive JavaScript-safe integer.") String message) {
	}
}
