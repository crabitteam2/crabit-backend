package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SYNTHETIC_BEARER;

import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.wish.RepresentativeWishService;
import com.crabit.backend.wish.WishLifecycleException;
import com.crabit.backend.wish.WishSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@RequestMapping("/v1/card-balance-accounts/{cardBalanceAccountId}/representative-wish")
@Tag(name = "Wishes")
public class RepresentativeWishController {

	private final RepresentativeWishService representativeWishes;

	public RepresentativeWishController(RepresentativeWishService representativeWishes) {
		this.representativeWishes = representativeWishes;
	}

	@Operation(
			operationId = "getRepresentativeWish",
			summary = "Get the current representative Wish",
			description = "Validates the authenticated student and active owned same-academy Card "
					+ "Balance Account before resolving the current selection. Returns the selected "
					+ "nondeleted IN_PROGRESS or AMOUNT_REACHED Wish directly, or 204 when the valid "
					+ "account has no representative. The read remains available during an OPEN Balance "
					+ "Adjustment Case, performs no external balance lookup, and mutates no persistent state.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Current representative Wish.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishSnapshot.class),
						examples = @ExampleObject(name = "representative-during-balance-mismatch",
								value = """
										{"id":"22222222-2222-2222-2222-222222222222",
										"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
										"purpose":"비상금","targetAmount":500000,"amount":200000,
										"targetDate":null,"state":"IN_PROGRESS","visibility":"PRIVATE",
										"balanceAdjustmentInProgress":true,
										"createdAt":"2026-08-18T01:00:00Z",
										"updatedAt":"2026-08-18T01:00:00Z","completedAt":null,
										"actualDurationSeconds":null,"version":0}
										"""))),
		@ApiResponse(responseCode = "204",
				description = "The valid account currently has no representative Wish.",
				content = @Content),
		@ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST.",
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
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND. An absent, closed, non-owned, or "
						+ "cross-academy account is hidden.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@GetMapping
	public ResponseEntity<WishSnapshot> get(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			HttpServletRequest request) {
		CurrentPrincipal principal = principal(request);
		return representativeWishes.get(
				principal.subjectId(), principal.academyId(), cardBalanceAccountId)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@Operation(
			operationId = "selectRepresentativeWish",
			summary = "Select the representative Wish",
			description = "Atomically replaces the account's prior representative with the named "
					+ "same-account Active Wish. Selecting the current representative is a 200 no-op "
					+ "that preserves Wish updatedAt and version. Selection remains available during "
					+ "an OPEN Balance Adjustment Case and creates no ledger event, notification, "
					+ "selection history, or Wish mutation. Concurrent selections serialize through "
					+ "the account-first lock.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Selected representative Wish.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishSnapshot.class),
						examples = @ExampleObject(name = "atomic-selection", value = """
								{"id":"341ab749-bbab-4b08-9334-0e4b12347b48",
								"cardBalanceAccountId":"11111111-1111-1111-1111-111111111111",
								"purpose":"새 노트북","targetAmount":1500000,"amount":1500000,
								"targetDate":"2026-12-31","state":"AMOUNT_REACHED",
								"visibility":"PRIVATE","balanceAdjustmentInProgress":false,
								"createdAt":"2026-08-16T02:10:00Z",
								"updatedAt":"2026-08-20T03:00:00Z","completedAt":null,
								"actualDurationSeconds":null,"version":3}
								"""))),
		@ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST.",
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
				description = "INVALID_STATE_TRANSITION for a same-account terminal Wish.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "415",
				description = "UNSUPPORTED_MEDIA_TYPE. Content-Type must be application/json.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public WishSnapshot select(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "Closed representative-selection request.", required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = RepresentativeWishSelectionRequest.class)))
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		UUID wishId = requiredWishId(body);
		CurrentPrincipal principal = principal(request);
		return representativeWishes.select(
				principal.subjectId(), principal.academyId(), cardBalanceAccountId, wishId);
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

	private static UUID requiredWishId(Map<String, Object> body) {
		if (body == null || !Set.of("wishId").equals(body.keySet())) {
			throw malformed("wishId is required and unknown fields are not supported.");
		}
		Object value = body.get("wishId");
		if (!(value instanceof String text)) {
			throw malformed("wishId must be a UUID string.");
		}
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException exception) {
			throw malformed("wishId must be a UUID string.");
		}
	}

	private static WishLifecycleException malformed(String message) {
		return new WishLifecycleException(
				WishLifecycleException.Code.MALFORMED_REQUEST, message, "wishId");
	}

	@Schema(
			name = "RepresentativeWishSelectionRequest",
			description = "Select one nondeleted Active Wish from this Card Balance Account.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
			requiredProperties = "wishId",
			example = "{\"wishId\":\"341ab749-bbab-4b08-9334-0e4b12347b48\"}")
	public record RepresentativeWishSelectionRequest(
			@Schema(ref = "#/components/schemas/Uuid",
					description = "UUID of the Wish to select.",
					requiredMode = Schema.RequiredMode.REQUIRED)
			UUID wishId) {
	}
}
