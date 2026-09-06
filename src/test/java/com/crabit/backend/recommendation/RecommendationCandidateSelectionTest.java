package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.crabit.backend.recommendation.RecommendationSnapshotRepository.AccountRow;
import com.crabit.backend.wish.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class RecommendationCandidateSelectionTest {
	static final Instant AT = Instant.parse("2026-09-02T12:00:00Z");
	static final UUID ACCOUNT = new UUID(0, 1),
			VIEWER = new UUID(0, 2),
			ACADEMY = new UUID(0, 3),
			OWN_WISH = new UUID(0, 4);
	static final AccountRow OWNER =
			new AccountRow(
					ACCOUNT, VIEWER, ACADEMY, AT.minusSeconds(10000000), "viewer", 10, "academy");

	@Test
	void reservesOverlappingGroupsAndBackfillsSparseGroupsToOneHundred() {
		var latest = rows(10, 101);
		var completed = rows(12, 3);
		var interest = rows(13, 2);
		var repository = repository(latest, completed, interest);
		var request = request(AT.minusSeconds(1), interest);
		var result = RecommendationCandidateSelection.select(repository, request, OWNER, AT);
		assertThat(result.rows())
				.hasSize(100)
				.extracting(SharedCardQueryRepository.Row::sharedCardId)
				.doesNotHaveDuplicates();
		assertThat(result.selection().get("selected_counts"))
				.isEqualTo(Map.of("latest", 97, "recently_completed", 1, "interest", 2));
		assertThat(result.truncated()).isTrue();
		assertThat(result.rows().subList(98, 100))
				.extracting(SharedCardQueryRepository.Row::sharedCardId)
				.containsExactly(interest.get(0).sharedCardId(), interest.get(1).sharedCardId());
	}

	@Test
	void staleEvidenceDoesNotQueryClassificationsAndExactlyThirtyDaysIsUsable() {
		var interested = rows(20, 1);
		var repository = repository(rows(10, 5), List.of(), interested);
		var stale =
				RecommendationCandidateSelection.select(
						repository,
						request(AT.minus(Duration.ofDays(30)).minusNanos(1), interested),
						OWNER,
						AT);
		assertThat(stale.interest().get("status")).isEqualTo("stale");
		verify(repository, never()).findOwnTitles(any(), any());
		var boundary =
				RecommendationCandidateSelection.select(
						repository, request(AT.minus(Duration.ofDays(30)), interested), OWNER, AT);
		assertThat(boundary.interest().get("status")).isEqualTo("used");
		assertThat(boundary.rows()).hasSize(6);
	}

	@Test
	void emptyCandidateUniverseAndMissingClassificationsRemainNormalEmptyResults() {
		var repository = repository(List.of(), List.of(), List.of());
		var result =
				RecommendationCandidateSelection.select(
						repository,
						new RecommendationRequest(new UUID(0, 10), ACCOUNT, null, null),
						OWNER,
						AT);
		assertThat(result.rows()).isEmpty();
		assertThat(result.truncated()).isFalse();
		assertThat(result.interest().get("status")).isEqualTo("absent");
	}

	private static RecommendationSnapshotRepository repository(
			List<SharedCardQueryRepository.Row> latest,
			List<SharedCardQueryRepository.Row> completed,
			List<SharedCardQueryRepository.Row> interested) {
		var repository = mock(RecommendationSnapshotRepository.class);
		when(repository.findCandidates(VIEWER, ACADEMY, 101)).thenReturn(latest);
		when(repository.findCompletedCandidates(eq(VIEWER), eq(ACADEMY), any(), any(), eq(101)))
				.thenReturn(completed);
		when(repository.findOwnTitles(eq(ACCOUNT), any())).thenReturn(Map.of(OWN_WISH, "books"));
		var titles = new HashMap<UUID, String>();
		for (var row : interested) titles.put(row.wishId(), row.purpose());
		when(repository.findVisibleTitles(eq(VIEWER), eq(ACADEMY), any())).thenReturn(titles);
		when(repository.findInterestCandidates(eq(VIEWER), eq(ACADEMY), any(), eq(101)))
				.thenReturn(interested);
		return repository;
	}

	private static RecommendationRequest request(
			Instant time, List<SharedCardQueryRepository.Row> interested) {
		var classifications = new ArrayList<RecommendationRequest.Classification>();
		classifications.add(
				new RecommendationRequest.Classification(
						OWN_WISH, List.of("books"), hash("books")));
		for (var row : interested)
			classifications.add(
					new RecommendationRequest.Classification(
							row.wishId(), List.of("books"), hash(row.purpose())));
		return new RecommendationRequest(
				new UUID(0, 10),
				ACCOUNT,
				null,
				new RecommendationRequest.InterestContext(
						"v1", "v1", time, List.of("books"), classifications));
	}

	private static String hash(String value) {
		try {
			return HexFormat.of()
					.formatHex(
							java.security.MessageDigest.getInstance("SHA-256")
									.digest(
											value.getBytes(
													java.nio.charset.StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new AssertionError(ex);
		}
	}

	private static List<SharedCardQueryRepository.Row> rows(int start, int count) {
		var rows = new ArrayList<SharedCardQueryRepository.Row>();
		for (int i = start; i < start + count; i++)
			rows.add(
					new SharedCardQueryRepository.Row(
							new UUID(0, 1000 + i),
							SharedCardKind.PROGRESS,
							new UUID(0, 50),
							"owner",
							10,
							new UUID(0, 51),
							ACADEMY,
							AT.minusSeconds(100000),
							null,
							new UUID(0, 2000 + i),
							"books",
							1000,
							100,
							WishState.IN_PROGRESS,
							null,
							null,
							AT.minusSeconds(10000),
							null,
							null,
							AT.minusSeconds(i),
							false));
		return List.copyOf(rows);
	}
}
