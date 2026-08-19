package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SYNTHETIC_BEARER;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.api.CardBalanceAccountProjectionService.KnownCardBalanceAccount;
import com.crabit.backend.api.CardBalanceAccountProjectionService.SuccessfulRefreshProjection;
import com.crabit.backend.balance.CardBalanceSyncResult;
import com.crabit.backend.balance.CardBalanceSyncService;
import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.wish.BalanceLookupMethod;
import com.crabit.backend.wish.BalanceObservation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/card-balance-accounts/{cardBalanceAccountId}/balance-refreshes")
@Tag(name = "Card Balance Accounts")
public class CardBalanceRefreshController {

	private final CardBalanceAccountRepository accounts;
	private final CardBalanceSyncService sync;
	private final CardBalanceAccountProjectionService projections;

	public CardBalanceRefreshController(
			CardBalanceAccountRepository accounts,
			CardBalanceSyncService sync,
			CardBalanceAccountProjectionService projections) {
		this.accounts = accounts;
		this.sync = sync;
		this.projections = projections;
	}

	@Operation(
			operationId = "refreshCardBalance",
			summary = "Refresh the current card balance",
			description = "Bodyless USER_REQUESTED lookup. This operation is deliberately not "
					+ "idempotency-keyed and remains allowed while a Balance Adjustment Case is "
					+ "OPEN; the response reports the resulting current adjustment flag.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200",
				description = "A successful current balance observation.",
				content = @Content(schema = @Schema(implementation = BalanceRefreshResult.class))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED.",
				content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN.",
				content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
		@ApiResponse(responseCode = "404", description = "CARD_BALANCE_ACCOUNT_NOT_FOUND.",
				content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
		@ApiResponse(responseCode = "503", description = "BALANCE_SYNC_FAILED.",
				content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
	})
	@PostMapping
	public ResponseEntity<?> refresh(
			@Parameter(description = "Required Card Balance Account UUID.", required = true,
					schema = @Schema(type = "string", format = "uuid"))
			@PathVariable UUID cardBalanceAccountId,
			HttpServletRequest request) {
		Object authenticated = request.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE);
		if (!(authenticated instanceof CurrentPrincipal principal)) {
			return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED",
					"A known Bearer token is required.", false);
		}
		if (principal.role() != CurrentPrincipal.Role.STUDENT) {
			return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
					"The authenticated principal is not a student.", false);
		}

		Optional<CardBalanceAccount> owned = accounts.findByIdAndStudentId(
				cardBalanceAccountId, principal.subjectId());
		if (owned.isEmpty() || !owned.orElseThrow().isActive()) {
			return error(HttpStatus.NOT_FOUND, "CARD_BALANCE_ACCOUNT_NOT_FOUND",
					"Card Balance Account not found.", false);
		}

		CardBalanceSyncResult result = sync.refresh(
				cardBalanceAccountId, BalanceLookupMethod.USER_REQUESTED);
		if (result instanceof CardBalanceSyncResult.Failure) {
			return error(HttpStatus.SERVICE_UNAVAILABLE, CardBalanceSyncService.FAILURE_CODE,
					"Card balance could not be refreshed.", true);
		}
		BalanceObservation requestedObservation =
				((CardBalanceSyncResult.Success) result).observation();
		CardBalanceAccount account = owned.orElseThrow();
		SuccessfulRefreshProjection projection =
				projections.projectSuccessful(account, requestedObservation);
		BalanceObservation responseObservation = projection.observation();
		return ResponseEntity.ok(new BalanceRefreshResult(
				responseObservation.id(), responseObservation.lookupMethod().name(),
				responseObservation.observedAt(), projection.account()));
	}

	private static ResponseEntity<ErrorEnvelope> error(
			HttpStatus status, String code, String message, boolean retryable) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
		if (status == HttpStatus.UNAUTHORIZED) {
			response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
		return response.body(new ErrorEnvelope(
				new ErrorBody(code, message, retryable, UUID.randomUUID().toString(),
						List.of(), Map.of())));
	}

	@Schema(name = "BalanceRefreshResult")
	public record BalanceRefreshResult(
			UUID observationId,
			String lookupMethod,
			Instant observedAt,
			KnownCardBalanceAccount account) {
	}

	public record ErrorEnvelope(ErrorBody error) {
	}

	public record ErrorBody(
			String code,
			String message,
			boolean retryable,
			String traceId,
			List<Object> fieldErrors,
			Map<String, Object> details) {
	}
}
