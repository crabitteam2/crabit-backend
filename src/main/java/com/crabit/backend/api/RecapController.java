package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SYNTHETIC_BEARER;

import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.recap.RecapException;
import com.crabit.backend.recap.RecapQueryService;
import com.crabit.backend.wish.WishLifecycleException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/card-balance-accounts/{cardBalanceAccountId}/recaps")
public class RecapController {
	private final RecapQueryService recaps;
	public RecapController(RecapQueryService recaps) { this.recaps = recaps; }
	@Operation(operationId = "getWeeklyRecap", security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@GetMapping("/weekly")
	public RecapQueryService.Response weekly(@PathVariable UUID cardBalanceAccountId,
			@RequestParam MultiValueMap<String,String> query, HttpServletRequest request) {
		validateQuery(query, "weekStart"); CurrentPrincipal p = student(request);
		return recaps.weekly(p.subjectId(), p.academyId(), cardBalanceAccountId, query.getFirst("weekStart"));
	}
	@Operation(operationId = "getMonthlyRecap", security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@GetMapping("/monthly")
	public RecapQueryService.Response monthly(@PathVariable UUID cardBalanceAccountId,
			@RequestParam MultiValueMap<String,String> query, HttpServletRequest request) {
		validateQuery(query, "month"); CurrentPrincipal p = student(request);
		return recaps.monthly(p.subjectId(), p.academyId(), cardBalanceAccountId, query.getFirst("month"));
	}
	private static void validateQuery(MultiValueMap<String,String> query, String allowed) {
		if (!Set.of(allowed).containsAll(query.keySet()) || query.getOrDefault(allowed, java.util.List.of()).size() > 1)
			throw new RecapException(RecapException.Code.MALFORMED_REQUEST, "The request is malformed.");
	}
	private static CurrentPrincipal student(HttpServletRequest request) {
		Object value = request.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE);
		if (!(value instanceof CurrentPrincipal p)) throw new WishLifecycleException(WishLifecycleException.Code.AUTH_REQUIRED, "A known Bearer token is required.");
		if (p.role() != CurrentPrincipal.Role.STUDENT) throw new WishLifecycleException(WishLifecycleException.Code.FORBIDDEN, "The authenticated principal is not a student.");
		return p;
	}
}
