package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SYNTHETIC_BEARER;
import static com.crabit.backend.config.SwaggerUiConfiguration.WISH_TAG;

import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.wish.KrwAmount;
import com.crabit.backend.wish.Wish;
import com.crabit.backend.wish.WishLifecycleException;
import com.crabit.backend.wish.WishLifecycleService;
import com.crabit.backend.wish.WishLifecycleService.MutationOutcome;
import com.crabit.backend.wish.WishPatch;
import com.crabit.backend.wish.WishSnapshot;
import com.crabit.backend.wish.WishState;
import com.crabit.backend.wish.WishVisibility;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/card-balance-accounts/{cardBalanceAccountId}/wishes")
@Tag(name = WISH_TAG)
public class WishController {

	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
	private static final String IDEMPOTENCY_REPLAYED = "Idempotency-Replayed";
	private static final String MALFORMED_EXAMPLE = """
			{"error":{"code":"MALFORMED_REQUEST","message":"The request is malformed.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[],"details":{}}}
			""";
	private static final String IDEMPOTENCY_REQUIRED_EXAMPLE = """
			{"error":{"code":"IDEMPOTENCY_KEY_REQUIRED","message":"Idempotency-Key is required.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[{"field":"Idempotency-Key","message":"Idempotency-Key is required."}],
			"details":{}}}
			""";
	private static final String EXPECTED_VERSION_REQUIRED_EXAMPLE = """
			{"error":{"code":"EXPECTED_VERSION_REQUIRED","message":"If-Match is required.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[{"field":"If-Match","message":"If-Match is required."}],"details":{}}}
			""";
	private static final String AUTH_REQUIRED_EXAMPLE = """
			{"error":{"code":"AUTH_REQUIRED","message":"A known Bearer token is required.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[],"details":{}}}
			""";
	private static final String FORBIDDEN_EXAMPLE = """
			{"error":{"code":"FORBIDDEN","message":"The authenticated principal is not a student.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[],"details":{}}}
			""";
	private static final String ACCOUNT_NOT_FOUND_EXAMPLE = """
			{"error":{"code":"CARD_BALANCE_ACCOUNT_NOT_FOUND",
			"message":"Card Balance Account not found.","retryable":false,
			"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136","fieldErrors":[],"details":{}}}
			""";
	private static final String WISH_NOT_FOUND_EXAMPLE = """
			{"error":{"code":"WISH_NOT_FOUND","message":"Wish not found.","retryable":false,
			"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136","fieldErrors":[],"details":{}}}
			""";
	private static final String VERSION_CONFLICT_EXAMPLE = """
			{"error":{"code":"VERSION_CONFLICT","message":"The supplied Wish version is stale.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[{"field":"expectedVersion","message":"The supplied Wish version is stale."}],
			"details":{}}}
			""";
	private static final String INVALID_TRANSITION_EXAMPLE = """
			{"error":{"code":"INVALID_STATE_TRANSITION",
			"message":"The Wish cannot transition from its current state.","retryable":false,
			"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136","fieldErrors":[],"details":{}}}
			""";
	private static final String BALANCE_MISMATCH_EXAMPLE = """
			{"error":{"code":"BALANCE_MISMATCH_LOCKED",
			"message":"The account balance must be reconciled before this operation.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[],"details":{}}}
			""";
	private static final String IDEMPOTENCY_REUSED_EXAMPLE = """
			{"error":{"code":"IDEMPOTENCY_KEY_REUSED",
			"message":"Idempotency-Key was already used for a different request.","retryable":false,
			"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136","fieldErrors":[],"details":{}}}
			""";
	private static final String UNSUPPORTED_MEDIA_TYPE_EXAMPLE = """
			{"error":{"code":"UNSUPPORTED_MEDIA_TYPE",
			"message":"PATCH requires application/merge-patch+json.","retryable":false,
			"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136","fieldErrors":[],"details":{}}}
			""";
	private static final String INVALID_AMOUNT_EXAMPLE = """
			{"error":{"code":"INVALID_AMOUNT",
			"message":"targetAmount must be a positive JavaScript-safe integer.","retryable":false,
			"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[{"field":"targetAmount",
			"message":"targetAmount must be a positive JavaScript-safe integer."}],"details":{}}}
			""";
	private static final String INVALID_PURPOSE_EXAMPLE = """
			{"error":{"code":"INVALID_PURPOSE","message":"purpose is invalid.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[{"field":"purpose","message":"purpose is invalid."}],"details":{}}}
			""";
	private static final String INVALID_DATE_RANGE_EXAMPLE = """
			{"error":{"code":"INVALID_DATE_RANGE",
			"message":"startDate must be on or before targetDate.","retryable":false,
			"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[
			{"field":"startDate","message":"startDate must be on or before targetDate."},
			{"field":"targetDate","message":"targetDate must be on or after startDate."}],
			"details":{}}}
			""";
	private static final String INVALID_VERSION_EXAMPLE = """
			{"error":{"code":"INVALID_VERSION","message":"Version must be non-negative.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[{"field":"expectedVersion","message":"Version must be non-negative."}],
			"details":{}}}
			""";

	private final WishLifecycleService wishes;

	public WishController(WishLifecycleService wishes) {
		this.wishes = wishes;
	}

	@Operation(
			operationId = "listWishes",
			summary = "List owned Wishes",
			description = "Returns only owned, non-tombstoned Wishes ordered by createdAt "
					+ "descending and id descending, with opaque cursor continuation and optional "
					+ "state filtering.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "A page containing owned WishSnapshot items and either an opaque "
						+ "nextCursor or null.",
				content = @Content(
						mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishLifecycleService.WishPage.class),
						examples = @ExampleObject(
								name = "ownedWishPage",
								value = "{\"items\":[],\"nextCursor\":null}"))),
		@ApiResponse(
				responseCode = "400",
				description = "MALFORMED_REQUEST: malformed account UUID, cursor, limit, state "
						+ "value, or duplicate state filter.",
				content = @Content(
						mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "malformedRequest", value = MALFORMED_EXAMPLE))),
		@ApiResponse(
				responseCode = "401",
				description = "AUTH_REQUIRED: Bearer credential is absent, blank, malformed, or unknown.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE,
						description = "Bearer authentication challenge.", example = "Bearer"),
				content = @Content(
						mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "authRequired", value = AUTH_REQUIRED_EXAMPLE))),
		@ApiResponse(
				responseCode = "403",
				description = "FORBIDDEN: authenticated synthetic principal is not a student.",
				content = @Content(
						mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "forbidden", value = FORBIDDEN_EXAMPLE))),
		@ApiResponse(
				responseCode = "404",
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND: account is absent, closed, or not "
						+ "owned in the principal academy.",
				content = @Content(
						mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(
								name = "accountNotFound", value = ACCOUNT_NOT_FOUND_EXAMPLE)))
	})
	@GetMapping
	public WishLifecycleService.WishPage list(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			@Parameter(description = "Optional opaque URL-safe cursor; malformed content is rejected.",
					schema = @Schema(type = "string"))
			@RequestParam(required = false) String cursor,
			@Parameter(description = "Maximum page size, from 1 through 100.",
					schema = @Schema(type = "integer", format = "int32", minimum = "1",
						maximum = "100", defaultValue = "20"))
			@RequestParam(defaultValue = "20") int limit,
			@Parameter(description = "Optional repeated unique lifecycle-state filter.",
					array = @ArraySchema(uniqueItems = true,
							schema = @Schema(implementation = WishState.class,
									allowableValues = {"IN_PROGRESS", "AMOUNT_REACHED", "COMPLETED", "ABANDONED"})))
			@RequestParam(required = false) List<WishState> state,
			HttpServletRequest request) {
		CurrentPrincipal principal = principal(request);
		Set<WishState> states = state == null ? Set.of() : new HashSet<>(state);
		if (state != null && states.size() != state.size()) {
			throw malformed("state must not contain duplicate values.", "state");
		}
		return wishes.list(principal.subjectId(), principal.academyId(), cardBalanceAccountId,
				cursor, limit, states);
	}

	@Operation(
			operationId = "createWish",
			summary = "Create a Wish",
			description = "Creates an IN_PROGRESS, PRIVATE Wish with zero allocated amount. startDate "
					+ "and targetDate are independent nullable calendar dates, and when both are set "
					+ "startDate must be on or before targetDate. The normalized startDate is part of "
					+ "the create idempotency fingerprint. An "
					+ "identical prior successful Idempotency-Key result is replayed before evaluating "
					+ "the current mismatch guard. Otherwise, an OPEN Balance Adjustment Case rejects "
					+ "creation with BALANCE_MISMATCH_LOCKED before a new Wish is persisted.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(
				responseCode = "201",
				description = "The newly created Wish. Idempotency-Replayed is false on first "
						+ "execution and true on an identical replay.",
				headers = @Header(name = IDEMPOTENCY_REPLAYED,
						description = "False for first execution; true for an identical replay.",
						schema = @Schema(type = "boolean"), example = "false"),
				content = @Content(
						mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishMutationResponse.class),
						examples = @ExampleObject(name = "createdWish", value = """
								{"wish":{"id":"22222222-2222-2222-2222-222222222222",
								"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
								"purpose":"Graduation trip","targetAmount":500000,"amount":0,
								"startDate":"2026-09-01","targetDate":"2027-02-28",
								"state":"IN_PROGRESS","visibility":"PRIVATE",
								"balanceAdjustmentInProgress":false,
								"createdAt":"2026-08-17T02:30:00Z","updatedAt":"2026-08-17T02:30:00Z",
								"completedAt":null,"closedAt":null,"actualDurationSeconds":null,"version":0},"eventId":null}
								"""))),
		@ApiResponse(
				responseCode = "400",
				description = "MALFORMED_REQUEST: malformed UUID or JSON, unsupported field, wrong "
						+ "type, missing required body field, or Idempotency-Key longer than 200 "
						+ "characters. IDEMPOTENCY_KEY_REQUIRED: Idempotency-Key is absent or blank.",
				content = @Content(
						mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "malformedRequest", value = MALFORMED_EXAMPLE),
							@ExampleObject(name = "idempotencyKeyRequired",
									value = IDEMPOTENCY_REQUIRED_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "401",
				description = "AUTH_REQUIRED: Bearer credential is absent, blank, malformed, or unknown.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE,
						description = "Bearer authentication challenge.", example = "Bearer"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "authRequired", value = AUTH_REQUIRED_EXAMPLE))),
		@ApiResponse(
				responseCode = "403",
				description = "FORBIDDEN: authenticated synthetic principal is not a student.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "forbidden", value = FORBIDDEN_EXAMPLE))),
		@ApiResponse(
				responseCode = "404",
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND: account is absent, closed, or not "
						+ "owned in the principal academy.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(
							name = "accountNotFound", value = ACCOUNT_NOT_FOUND_EXAMPLE))),
		@ApiResponse(
				responseCode = "409",
				description = "BALANCE_MISMATCH_LOCKED: an open mismatch rejects creation before "
						+ "a new Wish is persisted. IDEMPOTENCY_KEY_REUSED: the key belongs to a different "
						+ "operation, target, or request fingerprint.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "balanceMismatchLocked",
									value = BALANCE_MISMATCH_EXAMPLE),
							@ExampleObject(name = "idempotencyKeyReused",
									value = IDEMPOTENCY_REUSED_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "415",
				description = "UNSUPPORTED_MEDIA_TYPE: Content-Type is not application/json.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(
							name = "unsupportedMediaType", value = UNSUPPORTED_MEDIA_TYPE_EXAMPLE))),
		@ApiResponse(
				responseCode = "422",
				description = "INVALID_AMOUNT: targetAmount is non-positive or exceeds the "
						+ "JavaScript-safe integer range. INVALID_PURPOSE: purpose violates "
						+ "normalization, character, or length rules. INVALID_DATE_RANGE: startDate "
						+ "is after targetDate.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "invalidAmount", value = INVALID_AMOUNT_EXAMPLE),
							@ExampleObject(name = "invalidPurpose", value = INVALID_PURPOSE_EXAMPLE),
							@ExampleObject(name = "invalidDateRange", value = INVALID_DATE_RANGE_EXAMPLE)
						}))
	})
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<WishMutationResponse> create(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			@Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
					description = "Required nonblank idempotency key of at most 200 characters.",
					required = true,
					schema = @Schema(type = "string", minLength = 1, maxLength = 200))
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "Create fields only; unknown fields are rejected.",
					required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = CreateWishRequest.class)))
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		requireOnly(body, Set.of("purpose", "targetAmount", "startDate", "targetDate"));
		String purpose = requiredString(body, "purpose");
		long targetAmount = requiredLong(body, "targetAmount");
		LocalDate startDate = nullableDate(body, "startDate");
		LocalDate targetDate = nullableDate(body, "targetDate");
		CurrentPrincipal principal = principal(request);
		return mutation(wishes.create(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, idempotencyKey, purpose, targetAmount, startDate, targetDate));
	}

	@Operation(
			operationId = "getWish",
			summary = "Get an owned Wish",
			description = "Returns one owned, non-tombstoned Wish. Ownership failures and "
					+ "tombstones remain hidden through 404 responses.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "The current optimistic Wish snapshot, including amounts, lifecycle "
						+ "state, visibility, timestamps, and version.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishSnapshot.class),
						examples = @ExampleObject(name = "currentWish", value = """
								{"id":"22222222-2222-2222-2222-222222222222",
								"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
								"purpose":"Graduation trip","targetAmount":500000,"amount":125000,
								"startDate":"2026-09-01","targetDate":"2027-02-28",
								"state":"IN_PROGRESS","visibility":"PRIVATE",
								"balanceAdjustmentInProgress":false,
								"createdAt":"2026-08-17T02:30:00Z","updatedAt":"2026-08-17T02:30:00Z",
								"completedAt":null,"closedAt":null,"actualDurationSeconds":null,"version":1}
								"""))),
		@ApiResponse(
				responseCode = "400",
				description = "MALFORMED_REQUEST: malformed account UUID or Wish UUID.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "malformedRequest", value = MALFORMED_EXAMPLE))),
		@ApiResponse(
				responseCode = "401",
				description = "AUTH_REQUIRED: Bearer credential is absent, blank, malformed, or unknown.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE,
						description = "Bearer authentication challenge.", example = "Bearer"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "authRequired", value = AUTH_REQUIRED_EXAMPLE))),
		@ApiResponse(
				responseCode = "403",
				description = "FORBIDDEN: authenticated synthetic principal is not a student.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "forbidden", value = FORBIDDEN_EXAMPLE))),
		@ApiResponse(
				responseCode = "404",
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND: account is absent, closed, or not "
						+ "owned in the principal academy. WISH_NOT_FOUND: Wish is absent, tombstoned, "
						+ "or outside the account.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "accountNotFound", value = ACCOUNT_NOT_FOUND_EXAMPLE),
							@ExampleObject(name = "wishNotFound", value = WISH_NOT_FOUND_EXAMPLE)
						}))
	})
	@GetMapping("/{wishId}")
	public WishSnapshot get(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			@Parameter(description = "Required Wish UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID wishId,
			HttpServletRequest request) {
		CurrentPrincipal principal = principal(request);
		return wishes.get(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, wishId);
	}

	@Operation(
			operationId = "patchWish",
			summary = "Edit a Wish",
			description = "Applies one optimistic atomic merge patch. Omitted mutable fields are "
					+ "preserved; startDate or targetDate null clears that date; both dates are applied "
					+ "and validated as one final pair; completed Wishes and abandoned Wishes may only "
					+ "change visibility; an abandoned Wish stays unshared; an open balance mismatch "
					+ "blocks every edit.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "The post-edit Wish snapshot and null eventId. PATCH does not accept "
						+ "an Idempotency-Key and Idempotency-Replayed is always false.",
				headers = @Header(name = IDEMPOTENCY_REPLAYED,
						description = "Always false because PATCH is not idempotency-keyed.",
						schema = @Schema(type = "boolean"), example = "false"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishMutationResponse.class),
						examples = @ExampleObject(name = "editedWish", value = """
								{"wish":{"id":"22222222-2222-2222-2222-222222222222",
								"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
								"purpose":"Graduation trip","targetAmount":600000,"amount":125000,
								"startDate":null,"targetDate":null,
								"state":"IN_PROGRESS","visibility":"FRIENDS",
								"balanceAdjustmentInProgress":false,
								"createdAt":"2026-08-17T02:30:00Z","updatedAt":"2026-08-18T02:30:00Z",
								"completedAt":null,"closedAt":null,"actualDurationSeconds":null,"version":2},"eventId":null}
								"""))),
		@ApiResponse(
				responseCode = "400",
				description = "MALFORMED_REQUEST: malformed UUID or JSON, unsupported field, wrong "
						+ "type, absent expectedVersion, or no mutable field.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "malformedRequest", value = MALFORMED_EXAMPLE))),
		@ApiResponse(
				responseCode = "401",
				description = "AUTH_REQUIRED: Bearer credential is absent, blank, malformed, or unknown.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE,
						description = "Bearer authentication challenge.", example = "Bearer"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "authRequired", value = AUTH_REQUIRED_EXAMPLE))),
		@ApiResponse(
				responseCode = "403",
				description = "FORBIDDEN: authenticated synthetic principal is not a student.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "forbidden", value = FORBIDDEN_EXAMPLE))),
		@ApiResponse(
				responseCode = "404",
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND: account is absent, closed, or not "
						+ "owned in the principal academy. WISH_NOT_FOUND: Wish is absent, tombstoned, "
						+ "or outside the account.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "accountNotFound", value = ACCOUNT_NOT_FOUND_EXAMPLE),
							@ExampleObject(name = "wishNotFound", value = WISH_NOT_FOUND_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "409",
				description = "VERSION_CONFLICT: expectedVersion is stale. INVALID_STATE_TRANSITION: "
						+ "current Wish state rejects the requested edit. BALANCE_MISMATCH_LOCKED: an "
						+ "open mismatch rejects the requested edit.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "versionConflict", value = VERSION_CONFLICT_EXAMPLE),
							@ExampleObject(name = "invalidStateTransition",
									value = INVALID_TRANSITION_EXAMPLE),
							@ExampleObject(name = "balanceMismatchLocked",
									value = BALANCE_MISMATCH_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "415",
				description = "UNSUPPORTED_MEDIA_TYPE: Content-Type is not "
						+ "application/merge-patch+json.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(
							name = "unsupportedMediaType", value = UNSUPPORTED_MEDIA_TYPE_EXAMPLE))),
		@ApiResponse(
				responseCode = "422",
				description = "INVALID_AMOUNT: targetAmount violates positive, JavaScript-safe, or "
						+ "current-amount constraints. INVALID_PURPOSE: purpose violates normalization, "
						+ "character, or length rules. INVALID_DATE_RANGE: the final startDate is after "
						+ "the final targetDate. INVALID_VERSION: expectedVersion is negative.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "invalidAmount", value = INVALID_AMOUNT_EXAMPLE),
							@ExampleObject(name = "invalidPurpose", value = INVALID_PURPOSE_EXAMPLE),
							@ExampleObject(name = "invalidDateRange", value = INVALID_DATE_RANGE_EXAMPLE),
							@ExampleObject(name = "invalidVersion", value = INVALID_VERSION_EXAMPLE)
						}))
	})
	@PatchMapping(path = "/{wishId}", consumes = "application/merge-patch+json")
	public ResponseEntity<WishMutationResponse> patch(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			@Parameter(description = "Required Wish UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID wishId,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "Atomic merge patch. expectedVersion and at least one mutable field "
						+ "are required; omitted fields are preserved, null clears either date, and the "
						+ "final startDate/targetDate pair is validated atomically.",
					required = true,
					content = @Content(mediaType = "application/merge-patch+json",
							schema = @Schema(implementation = PatchWishRequest.class)))
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		Set<String> mutable = Set.of(
				"purpose", "targetAmount", "startDate", "targetDate", "visibility");
		requireOnly(body, Set.of(
				"expectedVersion", "purpose", "targetAmount", "startDate", "targetDate", "visibility"));
		if (body.keySet().stream().noneMatch(mutable::contains)) {
			throw malformed("At least one mutable Wish field is required.", null);
		}
		long expectedVersion = requiredVersion(body, "expectedVersion");
		String purpose = body.containsKey("purpose") ? requiredString(body, "purpose") : null;
		KrwAmount targetAmount = body.containsKey("targetAmount")
				? positiveAmount(requiredLong(body, "targetAmount"))
				: null;
		boolean startDatePresent = body.containsKey("startDate");
		LocalDate startDate = nullableDate(body, "startDate");
		boolean targetDatePresent = body.containsKey("targetDate");
		LocalDate targetDate = nullableDate(body, "targetDate");
		WishVisibility visibility = body.containsKey("visibility")
				? visibility(body.get("visibility"))
				: null;
		if (purpose != null) {
			purpose = normalizedPurpose(purpose);
		}
		CurrentPrincipal principal = principal(request);
		return mutation(wishes.patch(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, wishId, expectedVersion,
				new WishPatch(purpose, targetAmount, startDatePresent, startDate,
						targetDatePresent, targetDate, visibility)));
	}

	@Operation(
			operationId = "deleteWish",
			summary = "Delete a Wish",
			description = "Optimistically tombstones a nondeleted Wish, returns any allocated "
					+ "amount through existing ledger behavior, removes its shared-card projection, "
					+ "returns the final mutation snapshot, and hides subsequent reads.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "The final tombstoned mutation snapshot. EventId is nullable. "
						+ "Idempotency-Replayed is false first and true on identical replay.",
				headers = @Header(name = IDEMPOTENCY_REPLAYED,
						description = "False for first execution; true for an identical replay.",
						schema = @Schema(type = "boolean"), example = "false"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishMutationResponse.class),
						examples = @ExampleObject(name = "deletedWish", value = """
								{"wish":{"id":"22222222-2222-2222-2222-222222222222",
								"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
								"purpose":"Graduation trip","targetAmount":500000,"amount":0,
								"startDate":"2026-09-01","targetDate":"2027-02-28",
								"state":"ABANDONED","visibility":"PRIVATE",
								"balanceAdjustmentInProgress":false,
								"createdAt":"2026-08-17T02:30:00Z","updatedAt":"2026-08-18T02:30:00Z",
								"completedAt":null,"closedAt":"2026-08-18T02:30:00Z",
								"actualDurationSeconds":null,"version":2},
								"eventId":null}
								"""))),
		@ApiResponse(
				responseCode = "400",
				description = "MALFORMED_REQUEST: malformed UUID or If-Match, or Idempotency-Key "
						+ "longer than 200 characters. EXPECTED_VERSION_REQUIRED: If-Match is absent or "
						+ "blank. IDEMPOTENCY_KEY_REQUIRED: Idempotency-Key is absent or blank.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "malformedRequest", value = MALFORMED_EXAMPLE),
							@ExampleObject(name = "expectedVersionRequired",
									value = EXPECTED_VERSION_REQUIRED_EXAMPLE),
							@ExampleObject(name = "idempotencyKeyRequired",
									value = IDEMPOTENCY_REQUIRED_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "401",
				description = "AUTH_REQUIRED: Bearer credential is absent, blank, malformed, or unknown.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE,
						description = "Bearer authentication challenge.", example = "Bearer"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "authRequired", value = AUTH_REQUIRED_EXAMPLE))),
		@ApiResponse(
				responseCode = "403",
				description = "FORBIDDEN: authenticated synthetic principal is not a student.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "forbidden", value = FORBIDDEN_EXAMPLE))),
		@ApiResponse(
				responseCode = "404",
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND: account is absent, closed, or not "
						+ "owned in the principal academy. WISH_NOT_FOUND: Wish is absent, tombstoned, "
						+ "or outside the account.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "accountNotFound", value = ACCOUNT_NOT_FOUND_EXAMPLE),
							@ExampleObject(name = "wishNotFound", value = WISH_NOT_FOUND_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "409",
				description = "VERSION_CONFLICT: If-Match version is stale. "
						+ "IDEMPOTENCY_KEY_REUSED: the key belongs to a different operation, target, "
						+ "or request fingerprint.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "versionConflict", value = VERSION_CONFLICT_EXAMPLE),
							@ExampleObject(name = "idempotencyKeyReused",
									value = IDEMPOTENCY_REUSED_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "422",
				description = "INVALID_VERSION: If-Match is negative.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(
							name = "invalidVersion", value = INVALID_VERSION_EXAMPLE)))
	})
	@DeleteMapping("/{wishId}")
	public ResponseEntity<WishMutationResponse> delete(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			@Parameter(description = "Required Wish UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID wishId,
			@Parameter(name = HttpHeaders.IF_MATCH, in = ParameterIn.HEADER,
					description = "Required plain non-negative integer version; quoted entity-tag "
						+ "syntax is not accepted.", required = true,
					schema = @Schema(type = "integer", format = "int64", minimum = "0"))
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
					description = "Required nonblank idempotency key of at most 200 characters.",
					required = true,
					schema = @Schema(type = "string", minLength = 1, maxLength = 200))
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			HttpServletRequest request) {
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.EXPECTED_VERSION_REQUIRED,
					"If-Match is required.",
					HttpHeaders.IF_MATCH);
		}
		long expectedVersion;
		try {
			expectedVersion = Long.parseLong(ifMatch);
		} catch (NumberFormatException exception) {
			throw malformed("If-Match must be an integer.", HttpHeaders.IF_MATCH);
		}
		if (expectedVersion < 0) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_VERSION,
					"If-Match must be non-negative.",
					HttpHeaders.IF_MATCH);
		}
		CurrentPrincipal principal = principal(request);
		return mutation(wishes.delete(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, wishId, idempotencyKey, expectedVersion));
	}

	@Operation(
			operationId = "completeWish",
			summary = "Complete a funded Wish",
			description = "Completes only an AMOUNT_REACHED Wish, returns its allocation through "
					+ "a completion ledger event, sets amount to zero and completedAt, and synchronizes "
					+ "the completion-card projection.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "A COMPLETED Wish with amount zero, completedAt, and the completion "
						+ "ledger eventId. Idempotency-Replayed is false first and true on replay.",
				headers = @Header(name = IDEMPOTENCY_REPLAYED,
						description = "False for first execution; true for an identical replay.",
						schema = @Schema(type = "boolean"), example = "false"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishMutationResponse.class),
						examples = @ExampleObject(name = "completedWish", value = """
								{"wish":{"id":"22222222-2222-2222-2222-222222222222",
								"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
								"purpose":"Graduation trip","targetAmount":500000,"amount":0,
								"startDate":"2026-09-01","targetDate":"2027-02-28",
								"state":"COMPLETED","visibility":"PRIVATE",
								"balanceAdjustmentInProgress":false,
								"createdAt":"2026-08-17T02:30:00Z","updatedAt":"2026-09-01T09:00:00Z",
								"completedAt":"2026-09-01T09:00:00Z","closedAt":"2026-09-01T09:00:00Z","actualDurationSeconds":1328400,
								"version":2},"eventId":"33333333-3333-3333-3333-333333333333"}
								"""))),
		@ApiResponse(
				responseCode = "400",
				description = "MALFORMED_REQUEST: malformed UUID or JSON, unsupported field, wrong or "
						+ "absent expectedVersion, or Idempotency-Key longer than 200 characters. "
						+ "IDEMPOTENCY_KEY_REQUIRED: Idempotency-Key is absent or blank.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "malformedRequest", value = MALFORMED_EXAMPLE),
							@ExampleObject(name = "idempotencyKeyRequired",
									value = IDEMPOTENCY_REQUIRED_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "401",
				description = "AUTH_REQUIRED: Bearer credential is absent, blank, malformed, or unknown.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE,
						description = "Bearer authentication challenge.", example = "Bearer"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "authRequired", value = AUTH_REQUIRED_EXAMPLE))),
		@ApiResponse(
				responseCode = "403",
				description = "FORBIDDEN: authenticated synthetic principal is not a student.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "forbidden", value = FORBIDDEN_EXAMPLE))),
		@ApiResponse(
				responseCode = "404",
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND: account is absent, closed, or not "
						+ "owned in the principal academy. WISH_NOT_FOUND: Wish is absent, tombstoned, "
						+ "or outside the account.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "accountNotFound", value = ACCOUNT_NOT_FOUND_EXAMPLE),
							@ExampleObject(name = "wishNotFound", value = WISH_NOT_FOUND_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "409",
				description = "VERSION_CONFLICT: expectedVersion is stale. INVALID_STATE_TRANSITION: "
						+ "Wish is not AMOUNT_REACHED or is already terminal. IDEMPOTENCY_KEY_REUSED: "
						+ "the key belongs to a different operation, target, or request fingerprint.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "versionConflict", value = VERSION_CONFLICT_EXAMPLE),
							@ExampleObject(name = "invalidStateTransition",
									value = INVALID_TRANSITION_EXAMPLE),
							@ExampleObject(name = "idempotencyKeyReused",
									value = IDEMPOTENCY_REUSED_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "415",
				description = "UNSUPPORTED_MEDIA_TYPE: Content-Type is not application/json.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(
							name = "unsupportedMediaType", value = UNSUPPORTED_MEDIA_TYPE_EXAMPLE))),
		@ApiResponse(
				responseCode = "422",
				description = "INVALID_VERSION: expectedVersion is negative.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(
							name = "invalidVersion", value = INVALID_VERSION_EXAMPLE)))
	})
	@PostMapping(path = "/{wishId}/completion", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<WishMutationResponse> complete(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			@Parameter(description = "Required Wish UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID wishId,
			@Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
					description = "Required nonblank idempotency key of at most 200 characters.",
					required = true,
					schema = @Schema(type = "string", minLength = 1, maxLength = 200))
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "Version command; unknown fields are rejected.", required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = VersionCommandRequest.class)))
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		long expectedVersion = versionCommand(body);
		CurrentPrincipal principal = principal(request);
		return mutation(wishes.complete(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, wishId, idempotencyKey, expectedVersion));
	}

	@Operation(
			operationId = "abandonWish",
			summary = "Abandon a Wish",
			description = "Abandons only an active Wish, returns any nonzero allocation through "
					+ "an abandonment ledger event, sets amount to zero, forces PRIVATE visibility, "
					+ "and removes its shared-card projection.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(
				responseCode = "200",
				description = "An ABANDONED private Wish with amount zero. EventId is nullable. "
						+ "Idempotency-Replayed is false first and true on identical replay.",
				headers = @Header(name = IDEMPOTENCY_REPLAYED,
						description = "False for first execution; true for an identical replay.",
						schema = @Schema(type = "boolean"), example = "false"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishMutationResponse.class),
						examples = @ExampleObject(name = "abandonedWish", value = """
								{"wish":{"id":"22222222-2222-2222-2222-222222222222",
								"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
								"purpose":"Graduation trip","targetAmount":500000,"amount":0,
								"startDate":"2026-09-01","targetDate":"2027-02-28",
								"state":"ABANDONED","visibility":"PRIVATE",
								"balanceAdjustmentInProgress":false,
								"createdAt":"2026-08-17T02:30:00Z","updatedAt":"2026-08-18T02:30:00Z",
								"completedAt":null,"closedAt":"2026-08-18T02:30:00Z",
								"actualDurationSeconds":null,"version":2},"eventId":null}
								"""))),
		@ApiResponse(
				responseCode = "400",
				description = "MALFORMED_REQUEST: malformed UUID or JSON, unsupported field, wrong or "
						+ "absent expectedVersion, or Idempotency-Key longer than 200 characters. "
						+ "IDEMPOTENCY_KEY_REQUIRED: Idempotency-Key is absent or blank.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "malformedRequest", value = MALFORMED_EXAMPLE),
							@ExampleObject(name = "idempotencyKeyRequired",
									value = IDEMPOTENCY_REQUIRED_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "401",
				description = "AUTH_REQUIRED: Bearer credential is absent, blank, malformed, or unknown.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE,
						description = "Bearer authentication challenge.", example = "Bearer"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "authRequired", value = AUTH_REQUIRED_EXAMPLE))),
		@ApiResponse(
				responseCode = "403",
				description = "FORBIDDEN: authenticated synthetic principal is not a student.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "forbidden", value = FORBIDDEN_EXAMPLE))),
		@ApiResponse(
				responseCode = "404",
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND: account is absent, closed, or not "
						+ "owned in the principal academy. WISH_NOT_FOUND: Wish is absent, tombstoned, "
						+ "or outside the account.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "accountNotFound", value = ACCOUNT_NOT_FOUND_EXAMPLE),
							@ExampleObject(name = "wishNotFound", value = WISH_NOT_FOUND_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "409",
				description = "VERSION_CONFLICT: expectedVersion is stale. INVALID_STATE_TRANSITION: "
						+ "Wish is already terminal. IDEMPOTENCY_KEY_REUSED: the key belongs to a "
						+ "different operation, target, or request fingerprint.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
							@ExampleObject(name = "versionConflict", value = VERSION_CONFLICT_EXAMPLE),
							@ExampleObject(name = "invalidStateTransition",
									value = INVALID_TRANSITION_EXAMPLE),
							@ExampleObject(name = "idempotencyKeyReused",
									value = IDEMPOTENCY_REUSED_EXAMPLE)
						})),
		@ApiResponse(
				responseCode = "415",
				description = "UNSUPPORTED_MEDIA_TYPE: Content-Type is not application/json.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(
							name = "unsupportedMediaType", value = UNSUPPORTED_MEDIA_TYPE_EXAMPLE))),
		@ApiResponse(
				responseCode = "422",
				description = "INVALID_VERSION: expectedVersion is negative.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(
							name = "invalidVersion", value = INVALID_VERSION_EXAMPLE)))
	})
	@PostMapping(path = "/{wishId}/abandonment", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<WishMutationResponse> abandon(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			@Parameter(description = "Required Wish UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID wishId,
			@Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
					description = "Required nonblank idempotency key of at most 200 characters.",
					required = true,
					schema = @Schema(type = "string", minLength = 1, maxLength = 200))
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "Version command; unknown fields are rejected.", required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = VersionCommandRequest.class)))
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		long expectedVersion = versionCommand(body);
		CurrentPrincipal principal = principal(request);
		return mutation(wishes.abandon(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, wishId, idempotencyKey, expectedVersion));
	}

	private static long versionCommand(Map<String, Object> body) {
		requireOnly(body, Set.of("expectedVersion"));
		return requiredVersion(body, "expectedVersion");
	}

	private static ResponseEntity<WishMutationResponse> mutation(MutationOutcome outcome) {
		return ResponseEntity.status(outcome.httpStatus())
				.header(IDEMPOTENCY_REPLAYED, Boolean.toString(outcome.replayed()))
				.body(new WishMutationResponse(outcome.wish(), outcome.eventId()));
	}

	private static CurrentPrincipal principal(HttpServletRequest request) {
		Object value = request.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE);
		if (value instanceof CurrentPrincipal principal
				&& principal.role() == CurrentPrincipal.Role.STUDENT) {
			return principal;
		}
		throw new WishLifecycleException(
				WishLifecycleException.Code.AUTH_REQUIRED,
				"A known Bearer token is required.");
	}

	private static void requireOnly(Map<String, Object> body, Set<String> allowed) {
		if (body == null || !allowed.containsAll(body.keySet())) {
			throw malformed("Request contains an unsupported or malformed field.", null);
		}
	}

	private static String requiredString(Map<String, Object> body, String field) {
		Object value = body.get(field);
		if (!(value instanceof String text)) {
			throw malformed(field + " must be a string.", field);
		}
		return text;
	}

	private static long requiredVersion(Map<String, Object> body, String field) {
		long version = requiredLong(body, field);
		if (version < 0) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_VERSION,
					field + " must be non-negative.",
					field);
		}
		return version;
	}

	private static long requiredLong(Map<String, Object> body, String field) {
		if (!body.containsKey(field)) {
			throw malformed(field + " is required.", field);
		}
		Object value = body.get(field);
		try {
			if (value instanceof Byte || value instanceof Short
					|| value instanceof Integer || value instanceof Long) {
				return ((Number) value).longValue();
			}
			if (value instanceof BigInteger integer) {
				return integer.longValueExact();
			}
			if (value instanceof BigDecimal decimal) {
				return decimal.longValueExact();
			}
		} catch (ArithmeticException exception) {
			throw malformed(field + " must be an integer in range.", field);
		}
		throw malformed(field + " must be an integer.", field);
	}

	private static LocalDate nullableDate(Map<String, Object> body, String field) {
		if (!body.containsKey(field) || body.get(field) == null) {
			return null;
		}
		Object value = body.get(field);
		if (!(value instanceof String text)) {
			throw malformed(field + " must be an ISO calendar date or null.", field);
		}
		try {
			return LocalDate.parse(text);
		} catch (DateTimeParseException exception) {
			throw malformed(field + " must be an ISO calendar date.", field);
		}
	}

	private static WishVisibility visibility(Object value) {
		if (!(value instanceof String text)) {
			throw malformed("visibility must be a string.", "visibility");
		}
		try {
			return WishVisibility.valueOf(text);
		} catch (IllegalArgumentException exception) {
			throw malformed("visibility is invalid.", "visibility");
		}
	}

	private static KrwAmount positiveAmount(long amount) {
		try {
			return KrwAmount.positive(amount);
		} catch (IllegalArgumentException exception) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_AMOUNT,
					"targetAmount must be a positive JavaScript-safe integer.",
					"targetAmount");
		}
	}

	private static String normalizedPurpose(String purpose) {
		try {
			return Wish.normalizePurpose(purpose);
		} catch (IllegalArgumentException exception) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_PURPOSE,
					exception.getMessage(),
					"purpose");
		}
	}

	private static WishLifecycleException malformed(String message, String field) {
		return new WishLifecycleException(
				WishLifecycleException.Code.MALFORMED_REQUEST, message, field);
	}

	@Schema(
			name = "WishMutationResult",
			description = "The Wish state produced by a mutation and its optional ledger event.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
			example = """
					{"wish":{"id":"22222222-2222-2222-2222-222222222222",
					"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
					"purpose":"Graduation trip","targetAmount":500000,"amount":0,
					"startDate":"2026-09-01","targetDate":"2027-02-28",
					"state":"IN_PROGRESS","visibility":"PRIVATE",
					"balanceAdjustmentInProgress":false,
					"createdAt":"2026-08-17T02:30:00Z","updatedAt":"2026-08-17T02:30:00Z",
					"completedAt":null,"closedAt":null,"actualDurationSeconds":null,"version":0},"eventId":null}
					""")
	public record WishMutationResponse(
			@Schema(description = "Authoritative Wish snapshot after the mutation, or the original snapshot "
					+ "returned by an identical idempotent replay.",
					requiredMode = Schema.RequiredMode.REQUIRED) WishSnapshot wish,
			@Schema(description = "UUID of the immutable ledger event created by the mutation; null when "
					+ "the mutation moves no funds and therefore creates no ledger event.",
					format = "uuid", nullable = true,
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "33333333-3333-3333-3333-333333333333") UUID eventId) {
	}

	@Schema(
			name = "CreateWishRequest",
			description = "Create-Wish fields. Unknown fields are rejected.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
			requiredProperties = {"purpose", "targetAmount"},
			example = "{\"purpose\":\"Graduation trip\",\"targetAmount\":500000,"
					+ "\"startDate\":\"2026-09-01\",\"targetDate\":\"2027-02-28\"}")
	public record CreateWishRequest(
			@Schema(description = "Required purpose using normalization to NFC after trimming boundary "
					+ "Unicode space separators. Control, format, line-separator, and "
					+ "paragraph-separator characters are forbidden; normalized length is 1..200 "
					+ "Unicode code points.", minLength = 1, maxLength = 200,
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "Graduation trip") String purpose,
			@Schema(description = "Required target amount in integer Korean won.",
					minimum = "1", maximum = "9007199254740991",
					requiredMode = Schema.RequiredMode.REQUIRED,
					example = "500000") Long targetAmount,
			@Schema(description = "Optional nullable user-selected plan start date; omission or null "
					+ "stores null, and it must not be after a non-null targetDate.",
					format = "date", nullable = true,
					example = "2026-09-01") LocalDate startDate,
			@Schema(description = "Optional nullable ISO calendar date; null means no target date.",
					format = "date", nullable = true,
					example = "2027-02-28") LocalDate targetDate) {
	}

	@Schema(
			name = "PatchWishRequest",
			description = "Atomic merge patch. expectedVersion and at least one of purpose, "
					+ "targetAmount, startDate, targetDate, or visibility are required. Omission "
					+ "preserves a field; null clears either date; the final pair is validated "
					+ "atomically. Unknown fields are rejected.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
			requiredProperties = {"expectedVersion"},
			example = "{\"expectedVersion\":1,\"startDate\":null,\"visibility\":\"FRIENDS\"}")
	public record PatchWishRequest(
			@Schema(description = "Required non-negative optimistic version.", minimum = "0",
					requiredMode = Schema.RequiredMode.REQUIRED, example = "1") Long expectedVersion,
			@Schema(description = "Optional non-null purpose using the create normalization and "
					+ "1..200 Unicode code-point rules.", minLength = 1, maxLength = 200,
					example = "Graduation trip") String purpose,
			@Schema(description = "Optional non-null target amount in integer Korean won; it must "
					+ "not be lower than the currently allocated amount.", minimum = "1",
					maximum = "9007199254740991", example = "600000") Long targetAmount,
			@Schema(description = "Optional nullable plan start date; omission preserves and null "
					+ "clears it. It is validated with the final targetDate.", format = "date",
					nullable = true, example = "2026-09-01") LocalDate startDate,
			@Schema(description = "Optional nullable ISO calendar date; omission preserves and null "
					+ "clears it.", format = "date", nullable = true,
					example = "2027-02-28") LocalDate targetDate,
			@Schema(description = "Optional non-null sharing visibility.",
					allowableValues = {"PRIVATE", "FRIENDS", "ACADEMY"},
					example = "FRIENDS") WishVisibility visibility) {
	}

	@Schema(
			name = "VersionCommandRequest",
			description = "Optimistic version command. Unknown fields are rejected.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
			requiredProperties = {"expectedVersion"},
			example = "{\"expectedVersion\":1}")
	public record VersionCommandRequest(
			@Schema(description = "Required non-negative optimistic version.", minimum = "0",
					requiredMode = Schema.RequiredMode.REQUIRED, example = "1") Long expectedVersion) {
	}
}
