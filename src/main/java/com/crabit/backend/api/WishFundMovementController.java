package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SEED_BEARER;
import static com.crabit.backend.config.SwaggerUiConfiguration.WISH_TAG;

import com.crabit.backend.e2e.SeedPrincipal;
import com.crabit.backend.wish.WishFundMovementService;
import com.crabit.backend.wish.WishFundMovementService.MutationOutcome;
import com.crabit.backend.wish.WishFundMovementService.TransferOutcome;
import com.crabit.backend.wish.WishLifecycleException;
import com.crabit.backend.wish.WishSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
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
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/card-balance-accounts/{cardBalanceAccountId}")
@Tag(name = WISH_TAG)
public class WishFundMovementController {

	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
	private static final String IDEMPOTENCY_REPLAYED = "Idempotency-Replayed";
	private static final String ERROR_EXAMPLE = """
			{"error":{"code":"VERSION_CONFLICT","message":"The supplied Wish version is stale.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[],"details":{}}}
			""";
	private static final String MUTATION_EXAMPLE = """
			{"wish":{"id":"22222222-2222-2222-2222-222222222222",
			"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
			"purpose":"Graduation trip","targetAmount":500000,"amount":500000,
			"targetDate":"2027-02-28","state":"AMOUNT_REACHED","visibility":"PRIVATE",
			"createdAt":"2026-08-17T02:30:00Z","updatedAt":"2026-08-18T02:30:00Z",
			"completedAt":null,"actualDurationSeconds":null,"version":1},
			"eventId":"33333333-3333-3333-3333-333333333333"}
			""";
	private static final String TRANSFER_EXAMPLE = """
			{"sourceWish":{"id":"22222222-2222-2222-2222-222222222222",
			"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
			"purpose":"Laptop","targetAmount":500000,"amount":100000,"targetDate":null,
			"state":"IN_PROGRESS","visibility":"PRIVATE","createdAt":"2026-08-17T02:30:00Z",
			"updatedAt":"2026-08-18T02:30:00Z","completedAt":null,
			"actualDurationSeconds":null,"version":1},
			"destinationWish":{"id":"44444444-4444-4444-4444-444444444444",
			"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
			"purpose":"Trip","targetAmount":300000,"amount":300000,"targetDate":null,
			"state":"AMOUNT_REACHED","visibility":"PRIVATE",
			"createdAt":"2026-08-17T02:30:00Z","updatedAt":"2026-08-18T02:30:00Z",
			"completedAt":null,"actualDurationSeconds":null,"version":1},
			"eventId":"33333333-3333-3333-3333-333333333333",
			"occurredAt":"2026-08-18T02:30:00Z"}
			""";

	private final WishFundMovementService movements;

	public WishFundMovementController(WishFundMovementService movements) {
		this.movements = movements;
	}

	@Operation(
			operationId = "depositToWish",
			summary = "Deposit Card Balance Account funds into one Wish",
			description = "Performs and commits a PRE_DEPOSIT balance lookup before atomically "
					+ "allocating funds. Provider failure or a rejected allocation leaves Wish money "
					+ "and allocation-ledger facts unchanged.",
			security = @SecurityRequirement(name = SEED_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200",
				description = "The authoritative post-deposit Wish and its immutable ledger event.",
				headers = @Header(name = IDEMPOTENCY_REPLAYED,
					description = "False on first execution; true on an identical replay.",
					schema = @Schema(type = "boolean"), example = "false"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishController.WishMutationResponse.class),
					examples = @ExampleObject(name = "wishDeposit", value = MUTATION_EXAMPLE))),
		@ApiResponse(responseCode = "400",
				description = "MALFORMED_REQUEST or IDEMPOTENCY_KEY_REQUIRED.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
					examples = @ExampleObject(name = "malformedRequest", value = ERROR_EXAMPLE))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE,
					description = "Bearer authentication challenge.", example = "Bearer"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "404",
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND or WISH_NOT_FOUND.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "409",
				description = "VERSION_CONFLICT, INVALID_STATE_TRANSITION, "
					+ "BALANCE_MISMATCH_LOCKED, INSUFFICIENT_AVAILABLE_BALANCE, "
					+ "TARGET_AMOUNT_EXCEEDED, or IDEMPOTENCY_KEY_REUSED.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "422",
				description = "INVALID_AMOUNT or INVALID_VERSION.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "503",
				description = "BALANCE_SYNC_FAILED: retryable external balance lookup failure.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
					examples = @ExampleObject(name = "balanceSyncFailed", value = """
						{"error":{"code":"BALANCE_SYNC_FAILED",
						"message":"Card balance could not be refreshed.","retryable":true,
						"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
						"fieldErrors":[],"details":{}}}
						""")))
	})
	@PostMapping(path = "/wishes/{wishId}/deposits", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<WishController.WishMutationResponse> deposit(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			@Parameter(description = "Required target Wish UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID wishId,
			@Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
					description = "Required nonblank idempotency key of at most 200 characters.",
					required = true,
					schema = @Schema(type = "string", minLength = 1, maxLength = 200))
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "Positive amount and non-negative optimistic version.", required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishAmountCommand.class)))
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		AmountCommand command = amountCommand(body);
		SeedPrincipal principal = principal(request);
		return mutation(movements.deposit(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, wishId, idempotencyKey,
				command.amount(), command.expectedVersion()));
	}

	@Operation(
			operationId = "withdrawFromWish",
			summary = "Withdraw funds from one Wish",
			description = "Atomically returns a positive amount from an active Wish to account "
					+ "availability, recalculates its state, and persists one immutable ledger fact.",
			security = @SecurityRequirement(name = SEED_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200",
				description = "The authoritative post-withdrawal Wish and its immutable ledger event.",
				headers = @Header(name = IDEMPOTENCY_REPLAYED,
					description = "False on first execution; true on an identical replay.",
					schema = @Schema(type = "boolean"), example = "false"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishController.WishMutationResponse.class),
					examples = @ExampleObject(name = "wishWithdrawal", value = MUTATION_EXAMPLE))),
		@ApiResponse(responseCode = "400",
				description = "MALFORMED_REQUEST or IDEMPOTENCY_KEY_REQUIRED.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE,
					description = "Bearer authentication challenge.", example = "Bearer"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "404",
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND or WISH_NOT_FOUND.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "409",
				description = "VERSION_CONFLICT, INVALID_STATE_TRANSITION, "
					+ "INSUFFICIENT_WISH_AMOUNT, or IDEMPOTENCY_KEY_REUSED.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "422", description = "INVALID_AMOUNT or INVALID_VERSION.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@PostMapping(path = "/wishes/{wishId}/withdrawals", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<WishController.WishMutationResponse> withdraw(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			@Parameter(description = "Required target Wish UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID wishId,
			@Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
					description = "Required nonblank idempotency key of at most 200 characters.",
					required = true,
					schema = @Schema(type = "string", minLength = 1, maxLength = 200))
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "Positive amount and non-negative optimistic version.", required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishAmountCommand.class)))
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		AmountCommand command = amountCommand(body);
		SeedPrincipal principal = principal(request);
		return mutation(movements.withdraw(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, wishId, idempotencyKey,
				command.amount(), command.expectedVersion()));
	}

	@Operation(
			operationId = "transferWishFunds",
			summary = "Atomically transfer funds between two Wishes in one account",
			description = "Locks the account and both active Wishes in deterministic UUID order, "
					+ "moves funds without changing account availability, and persists one ledger event "
					+ "with balanced source and destination effects.",
			security = @SecurityRequirement(name = SEED_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200",
				description = "Both authoritative Wish snapshots and the single shared transfer event.",
				headers = @Header(name = IDEMPOTENCY_REPLAYED,
					description = "False on first execution; true on an identical replay.",
					schema = @Schema(type = "boolean"), example = "false"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishTransferResult.class),
					examples = @ExampleObject(name = "wishTransfer", value = TRANSFER_EXAMPLE))),
		@ApiResponse(responseCode = "400",
				description = "MALFORMED_REQUEST or IDEMPOTENCY_KEY_REQUIRED.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE,
					description = "Bearer authentication challenge.", example = "Bearer"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "404",
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND or WISH_NOT_FOUND.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "409",
				description = "VERSION_CONFLICT, INVALID_STATE_TRANSITION, "
					+ "CROSS_ACCOUNT_TRANSFER_FORBIDDEN, INSUFFICIENT_WISH_AMOUNT, "
					+ "TARGET_AMOUNT_EXCEEDED, BALANCE_MISMATCH_LOCKED, or IDEMPOTENCY_KEY_REUSED.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "422", description = "INVALID_AMOUNT or INVALID_VERSION.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@PostMapping(path = "/transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<WishTransferResult> transfer(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			@Parameter(name = IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
					description = "Required nonblank idempotency key of at most 200 characters.",
					required = true,
					schema = @Schema(type = "string", minLength = 1, maxLength = 200))
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "Two Wish IDs, amount, and both non-negative optimistic versions.",
					required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishTransferRequest.class)))
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		requireOnly(body, Set.of("sourceWishId", "destinationWishId", "amount",
				"sourceExpectedVersion", "destinationExpectedVersion"));
		UUID sourceWishId = requiredUuid(body, "sourceWishId");
		UUID destinationWishId = requiredUuid(body, "destinationWishId");
		long amount = requiredLong(body, "amount");
		long sourceExpectedVersion = requiredLong(body, "sourceExpectedVersion");
		long destinationExpectedVersion = requiredLong(body, "destinationExpectedVersion");
		SeedPrincipal principal = principal(request);
		TransferOutcome outcome = movements.transfer(
				principal.subjectId(), principal.academyId(), cardBalanceAccountId,
				idempotencyKey, sourceWishId, destinationWishId, amount,
				sourceExpectedVersion, destinationExpectedVersion);
		return ResponseEntity.status(outcome.httpStatus())
				.header(IDEMPOTENCY_REPLAYED, Boolean.toString(outcome.replayed()))
				.body(new WishTransferResult(outcome.sourceWish(), outcome.destinationWish(),
						outcome.eventId(), outcome.occurredAt()));
	}

	private static ResponseEntity<WishController.WishMutationResponse> mutation(
			MutationOutcome outcome) {
		return ResponseEntity.status(outcome.httpStatus())
				.header(IDEMPOTENCY_REPLAYED, Boolean.toString(outcome.replayed()))
				.body(new WishController.WishMutationResponse(outcome.wish(), outcome.eventId()));
	}

	private static AmountCommand amountCommand(Map<String, Object> body) {
		requireOnly(body, Set.of("amount", "expectedVersion"));
		return new AmountCommand(requiredLong(body, "amount"),
				requiredLong(body, "expectedVersion"));
	}

	private static SeedPrincipal principal(HttpServletRequest request) {
		Object value = request.getAttribute(SeedPrincipal.REQUEST_ATTRIBUTE);
		if (value instanceof SeedPrincipal principal
				&& principal.role() == SeedPrincipal.Role.STUDENT) {
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

	private static UUID requiredUuid(Map<String, Object> body, String field) {
		Object value = body.get(field);
		if (!(value instanceof String text)) {
			throw malformed(field + " must be a UUID string.", field);
		}
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException exception) {
			throw malformed(field + " must be a UUID string.", field);
		}
	}

	private static long requiredLong(Map<String, Object> body, String field) {
		if (body == null || !body.containsKey(field)) {
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

	private static WishLifecycleException malformed(String message, String field) {
		return new WishLifecycleException(
				WishLifecycleException.Code.MALFORMED_REQUEST, message, field);
	}

	private record AmountCommand(long amount, long expectedVersion) {
	}

	@Schema(
			name = "WishAmountCommand",
			description = "Positive movement amount and optimistic Wish version. Unknown fields are rejected.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
			requiredProperties = {"amount", "expectedVersion"},
			example = "{\"amount\":100000,\"expectedVersion\":0}")
	public record WishAmountCommand(
			@Schema(description = "Positive integer Korean won to deposit or withdraw.",
					minimum = "1", maximum = "9007199254740991",
					requiredMode = Schema.RequiredMode.REQUIRED, example = "100000") Long amount,
			@Schema(description = "Non-negative optimistic version of the target Wish.",
					minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED,
					example = "0") Long expectedVersion) {
	}

	@Schema(
			name = "WishTransferRequest",
			description = "Atomic same-account Wish transfer command. Unknown fields are rejected.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
			requiredProperties = {"sourceWishId", "destinationWishId", "amount",
					"sourceExpectedVersion", "destinationExpectedVersion"},
			example = "{\"sourceWishId\":\"22222222-2222-2222-2222-222222222222\","
					+ "\"destinationWishId\":\"44444444-4444-4444-4444-444444444444\","
					+ "\"amount\":100000,\"sourceExpectedVersion\":0,"
					+ "\"destinationExpectedVersion\":0}")
	public record WishTransferRequest(
			@Schema(description = "UUID of the active source Wish.", format = "uuid",
					requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceWishId,
			@Schema(description = "UUID of the distinct active destination Wish.", format = "uuid",
					requiredMode = Schema.RequiredMode.REQUIRED) UUID destinationWishId,
			@Schema(description = "Positive integer Korean won moved between the Wishes.",
					minimum = "1", maximum = "9007199254740991",
					requiredMode = Schema.RequiredMode.REQUIRED) Long amount,
			@Schema(description = "Non-negative optimistic version of the source Wish.",
					minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
			Long sourceExpectedVersion,
			@Schema(description = "Non-negative optimistic version of the destination Wish.",
					minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
			Long destinationExpectedVersion) {
	}

	@Schema(
			name = "WishTransferResult",
			description = "Both authoritative Wish snapshots and their one immutable transfer event.",
			example = TRANSFER_EXAMPLE)
	public record WishTransferResult(
			@Schema(description = "Authoritative source Wish snapshot after the atomic transfer.",
					requiredMode = Schema.RequiredMode.REQUIRED) WishSnapshot sourceWish,
			@Schema(description = "Authoritative destination Wish snapshot after the atomic transfer.",
					requiredMode = Schema.RequiredMode.REQUIRED) WishSnapshot destinationWish,
			@Schema(description = "UUID of the single immutable event containing both transfer effects.",
					format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID eventId,
			@Schema(description = "RFC 3339 UTC Z instant shared by both transfer effects.",
					format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED) Instant occurredAt) {
	}
}
