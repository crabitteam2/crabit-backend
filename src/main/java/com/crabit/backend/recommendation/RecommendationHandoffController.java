package com.crabit.backend.recommendation;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Hidden
@RestController
@RequestMapping("/internal/v1/recommendation-handoffs")
@ConditionalOnProperty(
		name = "crabit.recommendation.handoff.enabled", havingValue = "true")
final class RecommendationHandoffController {

	private static final Set<String> REQUEST_FIELDS =
			Set.of("handoff_id", "card_balance_account_id");
	private static final Pattern UUID_PATTERN = Pattern.compile(
			"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private final RecommendationHandoffService handoffs;
	private final ObjectMapper objectMapper;

	RecommendationHandoffController(
			RecommendationHandoffService handoffs, ObjectMapper objectMapper) {
		this.handoffs = handoffs;
		this.objectMapper = objectMapper;
	}

	@PostMapping
	ResponseEntity<Void> create(
			@RequestBody(required = false) byte[] body, HttpServletRequest request) {
		if (request.getQueryString() != null
				|| !isApplicationJson(request.getContentType())
				|| body == null || body.length == 0) {
			throw RecommendationHandoffException.malformed();
		}
		JsonNode root;
		try {
			root = objectMapper.readTree(body);
		}
		catch (JacksonException exception) {
			throw RecommendationHandoffException.malformed();
		}
		if (root == null || !root.isObject()
				|| !Set.copyOf(root.propertyNames()).equals(REQUEST_FIELDS)) {
			throw RecommendationHandoffException.malformed();
		}
		UUID handoffId = uuid(root.get("handoff_id"));
		UUID accountId = uuid(root.get("card_balance_account_id"));
		handoffs.deliver(handoffId, accountId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}

	private static UUID uuid(JsonNode node) {
		if (node == null || !node.isTextual()
				|| !UUID_PATTERN.matcher(node.textValue()).matches()) {
			throw RecommendationHandoffException.malformed();
		}
		try {
			UUID value = UUID.fromString(node.textValue());
			if (!value.toString().equals(node.textValue().toLowerCase(Locale.ROOT))) {
				throw RecommendationHandoffException.malformed();
			}
			return value;
		}
		catch (IllegalArgumentException exception) {
			throw RecommendationHandoffException.malformed();
		}
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
}
