package com.crabit.backend.api;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.balance.CardBalanceSyncResult;
import com.crabit.backend.balance.CardBalanceSyncService;
import com.crabit.backend.e2e.SeedPrincipal;
import com.crabit.backend.wish.BalanceBreakdown;
import com.crabit.backend.wish.BalanceLookupMethod;
import com.crabit.backend.wish.BalanceObservation;
import com.crabit.backend.wish.KrwAmount;
import com.crabit.backend.wish.Wish;
import com.crabit.backend.wish.WishRepository;
import com.crabit.backend.wish.WishState;
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
public class CardBalanceRefreshController {

	private static final List<WishState> ACTIVE_STATES =
			List.of(WishState.IN_PROGRESS, WishState.AMOUNT_REACHED);

	private final CardBalanceAccountRepository accounts;
	private final WishRepository wishes;
	private final CardBalanceSyncService sync;

	public CardBalanceRefreshController(
			CardBalanceAccountRepository accounts,
			WishRepository wishes,
			CardBalanceSyncService sync) {
		this.accounts = accounts;
		this.wishes = wishes;
		this.sync = sync;
	}

	@PostMapping
	public ResponseEntity<?> refresh(
			@PathVariable UUID cardBalanceAccountId,
			HttpServletRequest request) {
		Object authenticated = request.getAttribute(SeedPrincipal.REQUEST_ATTRIBUTE);
		if (!(authenticated instanceof SeedPrincipal principal)) {
			return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED",
					"A known Bearer token is required.", false);
		}
		if (principal.role() != SeedPrincipal.Role.STUDENT) {
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
		BalanceObservation observation =
				((CardBalanceSyncResult.Success) result).observation();
		CardBalanceAccount account = owned.orElseThrow();
		KrwAmount activeTotal = wishes
				.findByAccountIdAndDeletedAtIsNullAndStateIn(cardBalanceAccountId, ACTIVE_STATES)
				.stream()
				.map(Wish::amount)
				.reduce(KrwAmount.zero(), KrwAmount::plus);
		BalanceBreakdown balance = BalanceBreakdown.calculate(
				observation.actualCardBalance(), activeTotal);
		KnownCardBalanceAccount accountResponse = new KnownCardBalanceAccount(
				account.id(), account.academyId(), "KNOWN",
				balance.actualCardBalance().won(), balance.ledgerAvailable().won(),
				balance.displayAvailable().won(), balance.unresolvedShortage().won(),
				"SUCCESS", observation.observedAt());
		return ResponseEntity.ok(new BalanceRefreshResult(
				observation.id(), observation.lookupMethod().name(),
				observation.observedAt(), accountResponse));
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

	public record BalanceRefreshResult(
			UUID observationId,
			String lookupMethod,
			Instant observedAt,
			KnownCardBalanceAccount account) {
	}

	public record KnownCardBalanceAccount(
			UUID cardBalanceAccountId,
			UUID academyId,
			String balanceKnowledge,
			long actualCardBalance,
			long ledgerAvailableBalance,
			long displayAvailableBalance,
			long unresolvedShortage,
			String lastRefreshStatus,
			Instant lastRefreshedAt) {
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
