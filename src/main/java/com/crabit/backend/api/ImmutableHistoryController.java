package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SYNTHETIC_BEARER;
import static com.crabit.backend.config.SwaggerUiConfiguration.WISH_TAG;

import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.wish.ImmutableHistoryModels.AccountFundMovementPage;
import com.crabit.backend.wish.ImmutableHistoryModels.CardBalanceChangePage;
import com.crabit.backend.wish.ImmutableHistoryModels.WishFundMovementPage;
import com.crabit.backend.wish.ImmutableHistoryQueryService;
import com.crabit.backend.wish.WishLifecycleException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/card-balance-accounts/{cardBalanceAccountId}")
public class ImmutableHistoryController {

	private static final String CARD_ACCOUNT_TAG = "Card Balance Accounts";
	private static final String CARD_DESCRIPTION = """
			Returns only successful observations that produced a nonzero CARD_BALANCE_CHANGE ledger event.
			Failed observations and successful zero-delta observations remain persisted operational facts but
			are not money-history items. Results are ordered by occurredAt DESC then eventId DESC. The opaque
			cursor is bound to this operation, account, ordering version, and final tuple; malformed or
			mismatched cursors return 400 without a partial page. Continuation is strictly below that tuple,
			so eventId stabilizes equal timestamps and later events sorting before the boundary do not alter
			the continuation. Any valid limit may be used with a valid cursor. Authorization and ownership are
			re-evaluated on every request, and no cacheability guarantee is introduced.
			""";
	private static final String ACCOUNT_DESCRIPTION = """
			Returns one item per immutable ledger event, including external card changes and every Wish
			movement. A Wish transfer is one account item even though it has two Wish effects. Results are
			ordered by occurredAt DESC then eventId DESC. The opaque cursor is bound to this operation,
			account, ordering version, and final tuple; malformed or mismatched cursors return 400 without a
			partial page. Continuation is strictly below that tuple, so eventId stabilizes equal timestamps
			and later events sorting before the boundary do not alter the continuation. Any valid limit may
			be used with a valid cursor. Corrections are new compensating events and never edit or delete an
			earlier event. Authorization and ownership are re-evaluated on every request, and no cacheability
			guarantee is introduced.
			""";
	private static final String WISH_DESCRIPTION = """
			Returns immutable ledger Wish effects for the requested owned Wish only; external card changes
			never appear. An owned tombstoned Wish remains readable here even though ordinary Wish detail
			returns 404. Results are ordered by occurredAt DESC then eventId DESC. The opaque cursor is bound
			to this operation, account, Wish, ordering version, and final tuple; malformed or mismatched
			cursors return 400 without a partial page. Continuation is strictly below that tuple, so eventId
			stabilizes equal timestamps and later events sorting before the boundary do not alter the
			continuation. Any valid limit may be used with a valid cursor. Authorization and ownership are
			re-evaluated on every request, and no cacheability guarantee is introduced.
			""";

	private final ImmutableHistoryQueryService histories;

	public ImmutableHistoryController(ImmutableHistoryQueryService histories) {
		this.histories = histories;
	}

	@Operation(
			operationId = "listCardBalanceChanges",
			tags = CARD_ACCOUNT_TAG,
			summary = "List immutable nonzero card-balance changes",
			description = CARD_DESCRIPTION,
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Immutable nonzero card-balance event history.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = CardBalanceChangePage.class))),
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
		@ApiResponse(responseCode = "404", description = "CARD_BALANCE_ACCOUNT_NOT_FOUND.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@GetMapping("/card-balance-changes")
	public CardBalanceChangePage cardBalanceChanges(
			@PathVariable UUID cardBalanceAccountId,
			@Parameter(name = "cursor", in = ParameterIn.QUERY,
					description = "Opaque URL-safe continuation cursor returned by this exact history resource.")
			@RequestParam(required = false) String cursor,
			@Parameter(name = "limit", in = ParameterIn.QUERY,
					description = "Maximum number of history items.",
					schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
			@RequestParam(defaultValue = "20") int limit,
			HttpServletRequest request) {
		CurrentPrincipal principal = principal(request);
		return histories.cardBalanceChanges(
				principal.subjectId(), principal.academyId(), cardBalanceAccountId, cursor, limit);
	}

	@Operation(
			operationId = "listAccountFundMovements",
			tags = CARD_ACCOUNT_TAG,
			summary = "List all immutable account-level fund movements",
			description = ACCOUNT_DESCRIPTION,
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Immutable account-level money event history.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = AccountFundMovementPage.class))),
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
		@ApiResponse(responseCode = "404", description = "CARD_BALANCE_ACCOUNT_NOT_FOUND.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@GetMapping("/fund-movements")
	public AccountFundMovementPage accountFundMovements(
			@PathVariable UUID cardBalanceAccountId,
			@RequestParam(required = false) String cursor,
			@Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
			@RequestParam(defaultValue = "20") int limit,
			HttpServletRequest request) {
		CurrentPrincipal principal = principal(request);
		return histories.accountFundMovements(
				principal.subjectId(), principal.academyId(), cardBalanceAccountId, cursor, limit);
	}

	@Operation(
			operationId = "listWishFundMovements",
			tags = WISH_TAG,
			summary = "List immutable fund movements projected for one Wish",
			description = WISH_DESCRIPTION,
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Immutable Wish-specific money effects.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishFundMovementPage.class))),
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
				description = "CARD_BALANCE_ACCOUNT_NOT_FOUND or WISH_NOT_FOUND; owned tombstones return 200.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@GetMapping("/wishes/{wishId}/fund-movements")
	public WishFundMovementPage wishFundMovements(
			@PathVariable UUID cardBalanceAccountId,
			@PathVariable UUID wishId,
			@RequestParam(required = false) String cursor,
			@Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
			@RequestParam(defaultValue = "20") int limit,
			HttpServletRequest request) {
		CurrentPrincipal principal = principal(request);
		return histories.wishFundMovements(
				principal.subjectId(), principal.academyId(), cardBalanceAccountId,
				wishId, cursor, limit);
	}

	private static CurrentPrincipal principal(HttpServletRequest request) {
		Object value = request.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE);
		if (!(value instanceof CurrentPrincipal principal)) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.AUTH_REQUIRED,
					"A known Bearer token is required.");
		}
		if (principal.role() != CurrentPrincipal.Role.STUDENT) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.FORBIDDEN,
					"Only student principals may read personal history.");
		}
		return principal;
	}
}
