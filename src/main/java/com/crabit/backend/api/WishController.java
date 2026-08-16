package com.crabit.backend.api;

import com.crabit.backend.e2e.SeedPrincipal;
import com.crabit.backend.wish.KrwAmount;
import com.crabit.backend.wish.Wish;
import com.crabit.backend.wish.WishLifecycleException;
import com.crabit.backend.wish.WishLifecycleService;
import com.crabit.backend.wish.WishLifecycleService.MutationOutcome;
import com.crabit.backend.wish.WishPatch;
import com.crabit.backend.wish.WishSnapshot;
import com.crabit.backend.wish.WishState;
import com.crabit.backend.wish.WishVisibility;
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
public class WishController {

	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
	private static final String IDEMPOTENCY_REPLAYED = "Idempotency-Replayed";

	private final WishLifecycleService wishes;

	public WishController(WishLifecycleService wishes) {
		this.wishes = wishes;
	}

	@GetMapping
	public WishLifecycleService.WishPage list(
			@PathVariable UUID cardBalanceAccountId,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit,
			@RequestParam(required = false) List<WishState> state,
			HttpServletRequest request) {
		SeedPrincipal principal = principal(request);
		Set<WishState> states = state == null ? Set.of() : new HashSet<>(state);
		if (state != null && states.size() != state.size()) {
			throw malformed("state must not contain duplicate values.", "state");
		}
		return wishes.list(principal.subjectId(), principal.academyId(), cardBalanceAccountId,
				cursor, limit, states);
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<WishMutationResponse> create(
			@PathVariable UUID cardBalanceAccountId,
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		requireOnly(body, Set.of("purpose", "targetAmount", "targetDate"));
		String purpose = requiredString(body, "purpose");
		long targetAmount = requiredLong(body, "targetAmount");
		LocalDate targetDate = nullableDate(body, "targetDate");
		SeedPrincipal principal = principal(request);
		return mutation(wishes.create(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, idempotencyKey, purpose, targetAmount, targetDate));
	}

	@GetMapping("/{wishId}")
	public WishSnapshot get(
			@PathVariable UUID cardBalanceAccountId,
			@PathVariable UUID wishId,
			HttpServletRequest request) {
		SeedPrincipal principal = principal(request);
		return wishes.get(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, wishId);
	}

	@PatchMapping(path = "/{wishId}", consumes = "application/merge-patch+json")
	public ResponseEntity<WishMutationResponse> patch(
			@PathVariable UUID cardBalanceAccountId,
			@PathVariable UUID wishId,
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		Set<String> mutable = Set.of("purpose", "targetAmount", "targetDate", "visibility");
		requireOnly(body, Set.of(
				"expectedVersion", "purpose", "targetAmount", "targetDate", "visibility"));
		if (body.keySet().stream().noneMatch(mutable::contains)) {
			throw malformed("At least one mutable Wish field is required.", null);
		}
		long expectedVersion = requiredVersion(body, "expectedVersion");
		String purpose = body.containsKey("purpose") ? requiredString(body, "purpose") : null;
		KrwAmount targetAmount = body.containsKey("targetAmount")
				? positiveAmount(requiredLong(body, "targetAmount"))
				: null;
		boolean targetDatePresent = body.containsKey("targetDate");
		LocalDate targetDate = nullableDate(body, "targetDate");
		WishVisibility visibility = body.containsKey("visibility")
				? visibility(body.get("visibility"))
				: null;
		if (purpose != null) {
			purpose = normalizedPurpose(purpose);
		}
		SeedPrincipal principal = principal(request);
		return mutation(wishes.patch(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, wishId, expectedVersion,
				new WishPatch(purpose, targetAmount, targetDatePresent, targetDate, visibility)));
	}

	@DeleteMapping("/{wishId}")
	public ResponseEntity<WishMutationResponse> delete(
			@PathVariable UUID cardBalanceAccountId,
			@PathVariable UUID wishId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
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
		SeedPrincipal principal = principal(request);
		return mutation(wishes.delete(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, wishId, idempotencyKey, expectedVersion));
	}

	@PostMapping(path = "/{wishId}/completion", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<WishMutationResponse> complete(
			@PathVariable UUID cardBalanceAccountId,
			@PathVariable UUID wishId,
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		long expectedVersion = versionCommand(body);
		SeedPrincipal principal = principal(request);
		return mutation(wishes.complete(principal.subjectId(), principal.academyId(),
				cardBalanceAccountId, wishId, idempotencyKey, expectedVersion));
	}

	@PostMapping(path = "/{wishId}/abandonment", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<WishMutationResponse> abandon(
			@PathVariable UUID cardBalanceAccountId,
			@PathVariable UUID wishId,
			@RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@RequestBody Map<String, Object> body,
			HttpServletRequest request) {
		long expectedVersion = versionCommand(body);
		SeedPrincipal principal = principal(request);
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

	public record WishMutationResponse(WishSnapshot wish, UUID eventId) {
	}
}
