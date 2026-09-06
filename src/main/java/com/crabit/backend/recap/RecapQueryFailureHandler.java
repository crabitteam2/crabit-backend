package com.crabit.backend.recap;

import com.crabit.backend.api.RecapController;
import com.crabit.backend.api.WishApiExceptionHandler.ApiError;
import com.crabit.backend.api.WishApiExceptionHandler.ErrorEnvelope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Includes transaction acquisition/commit failures outside the query service method body. */
@RestControllerAdvice(assignableTypes = RecapController.class)
public final class RecapQueryFailureHandler {
	@ExceptionHandler({DataAccessException.class, TransactionException.class})
	public ResponseEntity<ErrorEnvelope> unavailable(RuntimeException exception) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
				.body(new ErrorEnvelope(new ApiError("RECAP_QUERY_UNAVAILABLE", "Recaps are temporarily unavailable.",
						true, UUID.randomUUID().toString(), List.of(), Map.of())));
	}
}
