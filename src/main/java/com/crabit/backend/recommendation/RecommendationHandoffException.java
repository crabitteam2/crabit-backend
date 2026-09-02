package com.crabit.backend.recommendation;

import org.springframework.http.HttpStatus;

public final class RecommendationHandoffException extends RuntimeException {

	public enum Code {
		MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, false),
		CARD_BALANCE_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, false),
		RECOMMENDATION_DATA_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT, false),
		RECOMMENDATION_QUERY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),
		RECOMMENDATION_RECEIVER_REJECTED(HttpStatus.BAD_GATEWAY, false),
		RECOMMENDATION_RECEIVER_UNAVAILABLE(HttpStatus.GATEWAY_TIMEOUT, true);

		private final HttpStatus status;
		private final boolean retryable;

		Code(HttpStatus status, boolean retryable) {
			this.status = status;
			this.retryable = retryable;
		}

		public HttpStatus status() {
			return status;
		}

		public boolean retryable() {
			return retryable;
		}
	}

	private final Code code;

	RecommendationHandoffException(Code code, String message) {
		super(message);
		this.code = code;
	}

	public Code code() {
		return code;
	}

	static RecommendationHandoffException malformed() {
		return new RecommendationHandoffException(
				Code.MALFORMED_REQUEST, "The request is malformed.");
	}

	static RecommendationHandoffException accountNotFound() {
		return new RecommendationHandoffException(
				Code.CARD_BALANCE_ACCOUNT_NOT_FOUND, "Card Balance Account not found.");
	}

	static RecommendationHandoffException incomplete() {
		return new RecommendationHandoffException(
				Code.RECOMMENDATION_DATA_INCOMPLETE, "Recommendation data is incomplete.");
	}

	static RecommendationHandoffException queryUnavailable() {
		return new RecommendationHandoffException(
				Code.RECOMMENDATION_QUERY_UNAVAILABLE, "Recommendation query is unavailable.");
	}

	static RecommendationHandoffException receiverRejected() {
		return new RecommendationHandoffException(
				Code.RECOMMENDATION_RECEIVER_REJECTED,
				"Recommendation receiver rejected the handoff.");
	}

	static RecommendationHandoffException receiverUnavailable() {
		return new RecommendationHandoffException(
				Code.RECOMMENDATION_RECEIVER_UNAVAILABLE,
				"Recommendation receiver is unavailable.");
	}
}
