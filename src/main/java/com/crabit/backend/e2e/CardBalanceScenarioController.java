package com.crabit.backend.e2e;

import com.crabit.backend.balance.CardBalanceProviderResult;
import com.crabit.backend.balance.CardBalanceScriptControl;
import com.crabit.backend.wish.KrwAmount;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@Profile("e2e")
@RequestMapping(
		path = "/e2e/card-balance-accounts/{cardBalanceAccountId}/balance-scenario",
		produces = MediaType.APPLICATION_JSON_VALUE)
public final class CardBalanceScenarioController {

	private static final Pattern UUID_PATTERN = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private static final BigInteger MAX_SAFE_BALANCE =
			BigInteger.valueOf(KrwAmount.MAX_SAFE_WON);

	private final CardBalanceScriptControl scripts;
	private final ObjectMapper objectMapper;

	public CardBalanceScenarioController(
			CardBalanceScriptControl scripts, ObjectMapper objectMapper) {
		this.scripts = scripts;
		this.objectMapper = objectMapper;
	}

	@PutMapping
	public ResponseEntity<?> replace(
			@PathVariable String cardBalanceAccountId,
			@RequestBody(required = false) byte[] body,
			HttpServletRequest request) {
		if (!isApplicationJson(request.getContentType())) {
			return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
					"Content-Type must be application/json.", null);
		}
		UUID accountId = parseAccountId(cardBalanceAccountId);
		if (accountId == null || body == null || body.length == 0) {
			return malformed();
		}

		JsonNode root;
		try {
			root = objectMapper.readTree(body);
		}
		catch (JacksonException exception) {
			return malformed();
		}
		if (root == null || !root.isObject()) {
			return malformed();
		}

		List<CardBalanceProviderResult> responses;
		try {
			responses = parseSteps(root);
		}
		catch (InvalidScenarioException exception) {
			return error(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_BALANCE_SCENARIO",
					"The balance scenario is invalid.",
					new FieldError(exception.field(), exception.getMessage()));
		}

		scripts.replace(accountId, responses);
		return ResponseEntity.ok(new ScenarioResponse(accountId, responseSteps(responses)));
	}

	@GetMapping
	public ResponseEntity<?> get(@PathVariable String cardBalanceAccountId) {
		UUID accountId = parseAccountId(cardBalanceAccountId);
		if (accountId == null) {
			return malformed();
		}
		return ResponseEntity.ok(new ScenarioResponse(
				accountId, responseSteps(scripts.remaining(accountId))));
	}

	@DeleteMapping
	public ResponseEntity<?> delete(@PathVariable String cardBalanceAccountId) {
		UUID accountId = parseAccountId(cardBalanceAccountId);
		if (accountId == null) {
			return malformed();
		}
		scripts.clear(accountId);
		return ResponseEntity.noContent().build();
	}

	private static List<CardBalanceProviderResult> parseSteps(JsonNode root) {
		unexpectedField(root, Set.of("steps"), "");
		JsonNode steps = root.get("steps");
		if (steps == null || !steps.isArray() || steps.isEmpty()) {
			throw invalid("steps", "steps must be a nonempty array.");
		}

		List<CardBalanceProviderResult> responses = new ArrayList<>(steps.size());
		for (int index = 0; index < steps.size(); index++) {
			JsonNode step = steps.get(index);
			String path = "steps[" + index + "]";
			if (step == null || !step.isObject()) {
				throw invalid(path, "Each step must be an object.");
			}
			JsonNode type = step.get("type");
			if (type == null || !type.isTextual()) {
				throw invalid(path + ".type", "type must be SUCCESS or FAILURE.");
			}
			switch (type.textValue()) {
				case "SUCCESS" -> responses.add(parseSuccess(step, path));
				case "FAILURE" -> {
					unexpectedField(step, Set.of("type"), path + ".");
					responses.add(CardBalanceProviderResult.failure());
				}
				default -> throw invalid(
						path + ".type", "type must be SUCCESS or FAILURE.");
			}
		}
		return List.copyOf(responses);
	}

	private static CardBalanceProviderResult parseSuccess(JsonNode step, String path) {
		unexpectedField(step, Set.of("type", "balance"), path + ".");
		JsonNode balance = step.get("balance");
		if (balance == null || !balance.isIntegralNumber()) {
			throw invalid(path + ".balance",
					"SUCCESS balance must be an integer from 0 through 9007199254740991.");
		}
		BigInteger value = balance.bigIntegerValue();
		if (value.signum() < 0 || value.compareTo(MAX_SAFE_BALANCE) > 0) {
			throw invalid(path + ".balance",
					"SUCCESS balance must be an integer from 0 through 9007199254740991.");
		}
		return new CardBalanceProviderResult.Success(KrwAmount.nonNegative(value.longValueExact()));
	}

	private static void unexpectedField(JsonNode object, Set<String> allowed, String prefix) {
		object.propertyNames().stream()
				.filter(property -> !allowed.contains(property))
				.sorted()
				.findFirst()
				.ifPresent(property -> {
					throw invalid(prefix + property, "Unexpected field.");
				});
	}

	private static UUID parseAccountId(String value) {
		if (value == null || !UUID_PATTERN.matcher(value).matches()) {
			return null;
		}
		UUID parsed = UUID.fromString(value);
		return parsed.toString().equals(value.toLowerCase(Locale.ROOT)) ? parsed : null;
	}

	private static boolean isApplicationJson(String contentType) {
		if (contentType == null) {
			return false;
		}
		try {
			MediaType mediaType = MediaType.parseMediaType(contentType);
			return "application".equalsIgnoreCase(mediaType.getType())
					&& "json".equalsIgnoreCase(mediaType.getSubtype());
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static List<StepResponse> responseSteps(
			List<CardBalanceProviderResult> responses) {
		return responses.stream().map(response -> {
			if (response instanceof CardBalanceProviderResult.Success success) {
				return (StepResponse) new SuccessStepResponse(
						"SUCCESS", success.balance().won());
			}
			return new FailureStepResponse("FAILURE");
		}).toList();
	}

	private static ResponseEntity<ErrorEnvelope> malformed() {
		return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
				"The request is malformed.", null);
	}

	private static ResponseEntity<ErrorEnvelope> error(
			HttpStatus status, String code, String message, FieldError fieldError) {
		List<FieldError> fieldErrors = fieldError == null ? List.of() : List.of(fieldError);
		return ResponseEntity.status(status).body(new ErrorEnvelope(new ErrorBody(
				code, message, false, UUID.randomUUID().toString(), fieldErrors, Map.of())));
	}

	private static InvalidScenarioException invalid(String field, String message) {
		return new InvalidScenarioException(field, message);
	}

	public record ScenarioResponse(UUID cardBalanceAccountId, List<StepResponse> steps) {
	}

	public sealed interface StepResponse permits SuccessStepResponse, FailureStepResponse {
	}

	public record SuccessStepResponse(String type, long balance) implements StepResponse {
	}

	public record FailureStepResponse(String type) implements StepResponse {
	}

	public record ErrorEnvelope(ErrorBody error) {
	}

	public record ErrorBody(
			String code,
			String message,
			boolean retryable,
			String traceId,
			List<FieldError> fieldErrors,
			Map<String, Object> details) {
	}

	public record FieldError(String field, String message) {
	}

	private static final class InvalidScenarioException extends IllegalArgumentException {

		private final String field;

		private InvalidScenarioException(String field, String message) {
			super(message);
			this.field = field;
		}

		private String field() {
			return field;
		}
	}
}
