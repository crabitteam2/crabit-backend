package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SYNTHETIC_BEARER;

import com.crabit.backend.api.CardBalanceAccountProjectionService.AccountSnapshot;
import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.wish.WishLifecycleException;
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
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/card-balance-accounts/{cardBalanceAccountId}")
@Tag(name = "Card Balance Accounts")
public class CardBalanceAccountDetailController {

	private final CardBalanceAccountProjectionService projections;

	public CardBalanceAccountDetailController(CardBalanceAccountProjectionService projections) {
		this.projections = projections;
	}

	@Operation(
			operationId = "getCardBalanceAccount",
			summary = "Get an owned Card Balance Account",
			description = "Returns the authenticated student's active account from the current "
					+ "persisted projection. A random identifier, closed account, ownership mismatch, "
					+ "and academy mismatch are hidden as the same not-found response. This operation "
					+ "performs no external balance lookup and mutates no persistent state. UNKNOWN "
					+ "amounts remain null. After a successful lookup, a later failed attempt retains "
					+ "the latest successful amounts and lastRefreshedAt while reporting "
					+ "lastRefreshStatus FAILED.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200",
				description = "Current persisted Card Balance Account projection.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = AccountSnapshot.class),
						examples = {
							@ExampleObject(name = "unknown", value = """
									{"cardBalanceAccountId":"09551375-f69a-4e69-8587-a0d590cbf092",
									"academyId":"a12c54ad-c8a2-4102-a03b-28fe4a0daaa7",
									"balanceKnowledge":"UNKNOWN","actualCardBalance":null,
									"ledgerAvailableBalance":null,"displayAvailableBalance":null,
									"unresolvedShortage":null,"balanceAdjustmentInProgress":false,
									"lastRefreshStatus":null,"lastRefreshedAt":null}
									"""),
							@ExampleObject(name = "failed-refresh-known", value = """
									{"cardBalanceAccountId":"09551375-f69a-4e69-8587-a0d590cbf092",
									"academyId":"a12c54ad-c8a2-4102-a03b-28fe4a0daaa7",
									"balanceKnowledge":"KNOWN","actualCardBalance":250000,
									"ledgerAvailableBalance":150000,"displayAvailableBalance":150000,
									"unresolvedShortage":0,"balanceAdjustmentInProgress":false,
									"lastRefreshStatus":"FAILED",
									"lastRefreshedAt":"2026-08-16T02:00:00Z"}
									"""),
							@ExampleObject(name = "adjustment-open-known", value = """
									{"cardBalanceAccountId":"09551375-f69a-4e69-8587-a0d590cbf092",
									"academyId":"a12c54ad-c8a2-4102-a03b-28fe4a0daaa7",
									"balanceKnowledge":"KNOWN","actualCardBalance":70000,
									"ledgerAvailableBalance":-20000,"displayAvailableBalance":0,
									"unresolvedShortage":20000,"balanceAdjustmentInProgress":true,
									"lastRefreshStatus":"SUCCESS",
									"lastRefreshedAt":"2026-08-18T08:00:00Z"}
									""")
						})),
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
	public AccountSnapshot get(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			HttpServletRequest request) {
		Object authenticated = request.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE);
		if (!(authenticated instanceof CurrentPrincipal principal)) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.AUTH_REQUIRED,
					"A known Bearer token is required.");
		}
		if (principal.role() != CurrentPrincipal.Role.STUDENT) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.FORBIDDEN,
					"The authenticated principal is not a student.");
		}
		return projections.getOwned(
				principal.subjectId(), principal.academyId(), cardBalanceAccountId);
	}
}
