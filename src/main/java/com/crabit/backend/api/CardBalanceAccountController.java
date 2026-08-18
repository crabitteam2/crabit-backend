package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SEED_BEARER;

import com.crabit.backend.api.CardBalanceAccountProjectionService.CardBalanceAccountPage;
import com.crabit.backend.e2e.SeedPrincipal;
import com.crabit.backend.wish.WishLifecycleException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me/card-balance-accounts")
@Tag(name = "Card Balance Accounts")
public class CardBalanceAccountController {

	private final CardBalanceAccountProjectionService projections;

	public CardBalanceAccountController(CardBalanceAccountProjectionService projections) {
		this.projections = projections;
	}

	@Operation(
			operationId = "listMyCardBalanceAccounts",
			summary = "List the authenticated student's Card Balance Accounts",
			description = "UNKNOWN balances remain null rather than being fabricated as zero. "
					+ "Each account also reports whether an account-scoped Balance Adjustment Case "
					+ "is currently OPEN.",
			security = @SecurityRequirement(name = SEED_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Visible Card Balance Accounts.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = CardBalanceAccountPage.class))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@GetMapping
	public CardBalanceAccountPage list(HttpServletRequest request) {
		Object authenticated = request.getAttribute(SeedPrincipal.REQUEST_ATTRIBUTE);
		if (!(authenticated instanceof SeedPrincipal principal)) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.AUTH_REQUIRED,
					"A known Bearer token is required.");
		}
		if (principal.role() != SeedPrincipal.Role.STUDENT) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.FORBIDDEN,
					"The authenticated principal is not a student.");
		}
		return projections.listOwned(principal.subjectId(), principal.academyId());
	}
}
