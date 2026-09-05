package com.crabit.backend.api;

import com.crabit.backend.history.HistoricalBalanceException;
import com.crabit.backend.history.HistoricalBalanceQueryService;
import com.crabit.backend.api.WishApiExceptionHandler.ApiError;
import com.crabit.backend.api.WishApiExceptionHandler.ErrorEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "crabit.recommendation.handoff.enabled", havingValue = "true")
@RequestMapping("/internal/v1/academies/{academyId}/students/{studentId}/card-balance-accounts/{accountId}/historical-balances")
public class HistoricalBalanceController {
    private final HistoricalBalanceQueryService history;
    public HistoricalBalanceController(HistoricalBalanceQueryService history) { this.history = history; }
    @GetMapping
    @Operation(operationId = "getHistoricalBalances")
    public Map<String, Object> get(@PathVariable String academyId, @PathVariable String studentId,
            @PathVariable String accountId, HttpServletRequest request) {
        if (!Boolean.TRUE.equals(request.getAttribute("crabit.machine-behavior-authenticated")))
            throw new HistoricalBalanceException(HistoricalBalanceException.Code.AUTH_REQUIRED);
        var parsed = HistoricalBalanceRequestParser.parse(request);
        return history.query(HistoricalBalanceRequestParser.uuid(academyId), HistoricalBalanceRequestParser.uuid(studentId),
                HistoricalBalanceRequestParser.uuid(accountId), parsed.from(), parsed.to(), parsed.granularity(), parsed.revision());
    }
    @ExceptionHandler(HistoricalBalanceException.class)
    public ResponseEntity<ErrorEnvelope> error(HistoricalBalanceException error) {
        var code = error.code();
        var response = ResponseEntity.status(code.status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (code.status == 401) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(new ErrorEnvelope(new ApiError(code.name(), code.message, code.retryable,
                UUID.randomUUID().toString(), List.of(), Map.of())));
    }
}
