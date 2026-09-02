package com.crabit.backend.api;

import com.crabit.backend.wish.WishLifecycleException;
import com.crabit.backend.relationship.RelationshipException;
import com.crabit.backend.recommendation.RecommendationHandoffException;
import com.crabit.backend.wishphoto.WishPhotoException;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class WishApiExceptionHandler implements ResponseBodyAdvice<Object> {

	private static final Pattern FUND_MOVEMENT_PATH = Pattern.compile(
			"^/v1/card-balance-accounts/[^/]+/(?:wishes/[^/]+/(?:deposits|withdrawals)|transfers)$");
	private static final Pattern STUDENT_BLOCKS_PATH = Pattern.compile(
			"^/v1/me/student-blocks$");
	private static final Pattern REPRESENTATIVE_WISH_PATH = Pattern.compile(
			"^/v1/card-balance-accounts/[^/]+/representative-wish$");

	@Override
	public boolean supports(MethodParameter returnType,
			Class<? extends HttpMessageConverter<?>> converterType) {
		return true;
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType mediaType,
			Class<? extends HttpMessageConverter<?>> converterType,
			ServerHttpRequest request, ServerHttpResponse response) {
		String path = request.getURI().getPath();
		if (path.startsWith("/v1/wish-photos") || path.contains("/wishes")
				|| path.contains("/shared-cards") || path.endsWith("/representative-wish")) {
			response.getHeaders().setCacheControl(CacheControl.noStore());
		}
		return body;
	}

	@ExceptionHandler(WishLifecycleException.class)
	public ResponseEntity<ErrorEnvelope> lifecycle(WishLifecycleException exception) {
		HttpStatus status = status(exception.code());
		List<FieldError> fields = exception.fieldErrors().stream()
				.map(field -> new FieldError(field.field(), field.message()))
				.toList();
		return ResponseEntity.status(status).body(new ErrorEnvelope(new ApiError(
				exception.code().name(),
				exception.getMessage(),
				exception.code() == WishLifecycleException.Code.BALANCE_SYNC_FAILED,
				UUID.randomUUID().toString(),
				fields,
				exception.details())));
	}

	@ExceptionHandler(RelationshipException.class)
	public ResponseEntity<ErrorEnvelope> relationship(RelationshipException exception) {
		HttpStatus status = switch (exception.code()) {
			case AUTH_REQUIRED -> HttpStatus.UNAUTHORIZED;
			case FORBIDDEN -> HttpStatus.FORBIDDEN;
			case ACADEMY_NOT_FOUND, STUDENT_NOT_FOUND, STUDENT_BLOCK_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case SELF_RELATIONSHIP, STUDENT_BLOCK_ALREADY_ACTIVE -> HttpStatus.CONFLICT;
			case MALFORMED_REQUEST -> HttpStatus.BAD_REQUEST;
		};
		List<FieldError> fields = exception.field() == null ? List.of()
				: List.of(new FieldError(exception.field(), exception.getMessage()));
		return ResponseEntity.status(status).body(new ErrorEnvelope(new ApiError(
				exception.code().name(), exception.getMessage(), false,
				UUID.randomUUID().toString(), fields, exception.details())));
	}

	@ExceptionHandler(WishPhotoException.class)
	public ResponseEntity<ErrorEnvelope> photo(WishPhotoException exception) {
		HttpStatus status = switch (exception.code()) {
			case MALFORMED_REQUEST, IDEMPOTENCY_KEY_REQUIRED -> HttpStatus.BAD_REQUEST;
			case WISH_PHOTO_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case IDEMPOTENCY_KEY_REUSED, WISH_PHOTO_EXPIRED, WISH_PHOTO_ALREADY_ATTACHED ->
					HttpStatus.CONFLICT;
			case PHOTO_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
			case UNSUPPORTED_PHOTO_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
			case INVALID_PHOTO, PHOTO_CONTENT_NOT_ALLOWED -> HttpStatus.UNPROCESSABLE_CONTENT;
			case PHOTO_UPLOAD_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
			case PHOTO_PROCESSING_UNAVAILABLE, PHOTO_DELIVERY_UNAVAILABLE ->
					HttpStatus.SERVICE_UNAVAILABLE;
		};
		ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
		if (exception.retryAfterSeconds() > 0) {
			response.header("Retry-After", Integer.toString(exception.retryAfterSeconds()));
		}
		boolean retryable = exception.code() == WishPhotoException.Code.PHOTO_UPLOAD_RATE_LIMITED
				|| exception.code() == WishPhotoException.Code.PHOTO_PROCESSING_UNAVAILABLE
				|| exception.code() == WishPhotoException.Code.PHOTO_DELIVERY_UNAVAILABLE;
		return response.body(new ErrorEnvelope(new ApiError(exception.code().name(),
				exception.getMessage(), retryable, UUID.randomUUID().toString(), List.of(), Map.of())));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorEnvelope> oversizedPhoto(MaxUploadSizeExceededException exception) {
		return photo(new WishPhotoException(WishPhotoException.Code.PHOTO_TOO_LARGE,
				"Wish photo exceeds 5 MiB."));
	}

	@ExceptionHandler(RecommendationHandoffException.class)
	public ResponseEntity<ErrorEnvelope> recommendation(
			RecommendationHandoffException exception) {
		return ResponseEntity.status(exception.code().status()).body(
				new ErrorEnvelope(new ApiError(
						exception.code().name(),
						exception.getMessage(),
						exception.code().retryable(),
						UUID.randomUUID().toString(),
						List.of(),
						Map.of())));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorEnvelope> mediaType(
			HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
		if ("POST".equals(request.getMethod())
				&& request.getRequestURI().endsWith("/v1/wish-photos")) {
			return photo(new WishPhotoException(
					WishPhotoException.Code.UNSUPPORTED_PHOTO_TYPE,
					"Wish photo uploads require multipart/form-data with a JPEG photo part."));
		}
		if ("POST".equals(request.getMethod())
				&& (FUND_MOVEMENT_PATH.matcher(request.getRequestURI()).matches()
						|| STUDENT_BLOCKS_PATH.matcher(request.getRequestURI()).matches())) {
			return lifecycle(new WishLifecycleException(
					WishLifecycleException.Code.MALFORMED_REQUEST,
				"The request is malformed."));
		}
		if ("PUT".equals(request.getMethod())
				&& REPRESENTATIVE_WISH_PATH.matcher(request.getRequestURI()).matches()) {
			return lifecycle(new WishLifecycleException(
					WishLifecycleException.Code.UNSUPPORTED_MEDIA_TYPE,
					"Content-Type must be application/json."));
		}
		return lifecycle(new WishLifecycleException(
				WishLifecycleException.Code.UNSUPPORTED_MEDIA_TYPE,
				"PATCH requires application/merge-patch+json."));
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
			MethodArgumentNotValidException.class, MissingServletRequestParameterException.class,
			HandlerMethodValidationException.class})
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
			case INVALID_AMOUNT, INVALID_PURPOSE, INVALID_DATE_RANGE, INVALID_VERSION ->
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
