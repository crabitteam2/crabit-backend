package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

class RecommendationRequestTest {
	@Test
	void validatesSharedRequestFixtures() throws Exception {
		Path root = Path.of("src/test/resources/recommendation");
		var cases =
				RecommendationRequest.JSON.readTree(Files.readAllBytes(root.resolve("cases.json")));
		for (var c : cases.get("requests")) {
			byte[] bytes = Files.readAllBytes(root.resolve(c.get("file").textValue()));
			if (c.get("valid").booleanValue())
				assertThatCode(
								() ->
										RecommendationRequest.parse(bytes)
												.resolve(
														java.time.Instant.parse(
																"2026-09-01T15:00:00Z")))
						.doesNotThrowAnyException();
			else
				assertThatThrownBy(
								() ->
										RecommendationRequest.parse(bytes)
												.resolve(
														java.time.Instant.parse(
																"2026-09-01T15:00:00Z")))
						.isInstanceOf(RecommendationHandoffException.class);
		}
	}

	@Test
	void rejectsDuplicateKeysTrailingDocumentsAndOversizedInput() {
		for (String text :
				new String[] {
					"{\"handoff_id\":\"00000000-0000-0000-0000-000000000001\",\"handoff_id\":\"00000000-0000-0000-0000-000000000001\",\"card_balance_account_id\":\"00000000-0000-0000-0000-000000000002\"}",
					"{} {}",
					" ".repeat(262145)
				})
			assertThatThrownBy(
							() ->
									RecommendationRequest.parse(
											text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
					.isInstanceOf(RecommendationHandoffException.class);
	}

	@Test
	void coverageAndSafeArithmeticDoNotTreatPartialZerosAsObserved() {
		var at = java.time.Instant.parse("2026-09-01T03:00:00Z");
		assertThat(
						RecommendationPeriodSavings.coverage(
										at.minusSeconds(3600),
										at.plusSeconds(3600),
										at.minusSeconds(86400),
										at)
								.get("status"))
				.isEqualTo("partially_observed");
		assertThat(
						RecommendationPeriodSavings.coverage(
										at.plusSeconds(1),
										at.plusSeconds(3600),
										at.minusSeconds(86400),
										at)
								.get("status"))
				.isEqualTo("unobserved");
		assertThat(RecommendationPeriodSavings.safe(9007199254740991L))
				.isEqualTo(9007199254740991L);
		assertThatThrownBy(() -> RecommendationPeriodSavings.sum(9007199254740991L, 1))
				.isInstanceOf(RecommendationHandoffException.class);
	}
}
