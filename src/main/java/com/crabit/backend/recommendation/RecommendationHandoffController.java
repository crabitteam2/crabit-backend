package com.crabit.backend.recommendation;

import io.swagger.v3.oas.annotations.Hidden;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

@Hidden
@RestController
@RequestMapping("/internal/v1/recommendation-handoffs")
@ConditionalOnProperty(name = "crabit.recommendation.handoff.enabled", havingValue = "true")
final class RecommendationHandoffController {

	private final RecommendationHandoffService handoffs;

	RecommendationHandoffController(
			RecommendationHandoffService handoffs, ObjectMapper objectMapper) {
		this.handoffs = handoffs;
	}

	@PostMapping
	ResponseEntity<Void> create(HttpServletRequest request) {
		try {
			byte[] body = request.getInputStream().readNBytes(RecommendationRequest.MAX_BYTES + 1);
			return create(body, request);
		} catch (java.io.IOException ex) {
			throw RecommendationHandoffException.malformed();
		}
	}

	ResponseEntity<Void> create(byte[] body, HttpServletRequest request) {
		if (request.getQueryString() != null || !isApplicationJson(request.getContentType()))
			throw RecommendationHandoffException.malformed();
		RecommendationRequest parsed = RecommendationRequest.parse(body);
		if (parsed.period() == null && parsed.interest() == null)
			handoffs.deliver(parsed.handoffId(), parsed.accountId());
		else handoffs.deliver(parsed);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}

	private static boolean isApplicationJson(String contentType) {
		if (contentType == null) {
			return false;
		}
		try {
			MediaType mediaType = MediaType.parseMediaType(contentType);
			return "application".equalsIgnoreCase(mediaType.getType())
					&& "json".equalsIgnoreCase(mediaType.getSubtype());
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}
}
