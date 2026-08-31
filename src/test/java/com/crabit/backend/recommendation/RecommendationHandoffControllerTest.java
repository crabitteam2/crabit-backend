package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class RecommendationHandoffControllerTest {

	private static final UUID HANDOFF_ID =
			UUID.fromString("00000000-0000-0000-0000-000000009001");
	private static final UUID ACCOUNT_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000301");

	private final RecommendationHandoffService handoffs = mock(RecommendationHandoffService.class);
	private final RecommendationHandoffController controller =
			new RecommendationHandoffController(handoffs, new ObjectMapper());

	@Test
	void acceptsOnlyTheExactTwoFieldJsonRequestAndReturnsNoContent() {
		MockHttpServletRequest request = request("application/json; charset=utf-8");

		assertThat(controller.create(bytes("""
				{"handoff_id":"%s","card_balance_account_id":"%s"}
				""".formatted(HANDOFF_ID, ACCOUNT_ID)), request).getStatusCode().value())
				.isEqualTo(204);
		verify(handoffs).deliver(HANDOFF_ID, ACCOUNT_ID);
		verifyNoMoreInteractions(handoffs);
	}

	@Test
	void rejectsMalformedMissingExtraNonUuidAndUnsupportedMediaRequests() {
		List<InvalidRequest> invalid = List.of(
				new InvalidRequest(null, "application/json"),
				new InvalidRequest(bytes(""), "application/json"),
				new InvalidRequest(bytes("{"), "application/json"),
				new InvalidRequest(bytes("[]"), "application/json"),
				new InvalidRequest(bytes("""
						{"handoff_id":"%s"}
						""".formatted(HANDOFF_ID)), "application/json"),
				new InvalidRequest(bytes("""
						{"handoff_id":"%s","card_balance_account_id":"%s","viewer_id":"%s"}
						""".formatted(HANDOFF_ID, ACCOUNT_ID, UUID.randomUUID())), "application/json"),
				new InvalidRequest(bytes("""
						{"handoff_id":"not-a-uuid","card_balance_account_id":"%s"}
						""".formatted(ACCOUNT_ID)), "application/json"),
				new InvalidRequest(bytes("""
						{"handoff_id":"%s","card_balance_account_id":"%s"}
						""".formatted(HANDOFF_ID, ACCOUNT_ID)), null),
				new InvalidRequest(bytes("""
						{"handoff_id":"%s","card_balance_account_id":"%s"}
						""".formatted(HANDOFF_ID, ACCOUNT_ID)), MediaType.TEXT_PLAIN_VALUE));

		for (InvalidRequest candidate : invalid) {
			assertThatThrownBy(() -> controller.create(
					candidate.body(), request(candidate.contentType())))
					.as(String.valueOf(candidate))
					.isInstanceOf(RecommendationHandoffException.class)
					.extracting(exception -> ((RecommendationHandoffException) exception).code())
					.isEqualTo(RecommendationHandoffException.Code.MALFORMED_REQUEST);
		}

		MockHttpServletRequest query = request("application/json");
		query.setQueryString("viewer=forbidden");
		assertThatThrownBy(() -> controller.create(bytes("""
				{"handoff_id":"%s","card_balance_account_id":"%s"}
				""".formatted(HANDOFF_ID, ACCOUNT_ID)), query))
				.isInstanceOf(RecommendationHandoffException.class);
	}

	private static MockHttpServletRequest request(String contentType) {
		MockHttpServletRequest request = new MockHttpServletRequest(
				"POST", "/internal/v1/recommendation-handoffs");
		if (contentType != null) {
			request.setContentType(contentType);
		}
		return request;
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private record InvalidRequest(byte[] body, String contentType) {
	}
}
