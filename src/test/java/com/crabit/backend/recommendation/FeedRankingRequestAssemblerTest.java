package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import com.crabit.backend.wish.SharedCardQueryRepository;
import com.crabit.backend.wish.WishState;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;

class FeedRankingRequestAssemblerTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T15:00:00Z"); // Jan 2 Seoul

    @Test
    void preservesPythonAmountBucketBoundaries() {
        long[] values = {0, 9_999, 10_000, 29_999, 30_000, 49_999,
                50_000, 99_999, 100_000, 299_999, 300_000};
        int[] expected = {0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5};
        for (int index = 0; index < values.length; index++)
            assertThat(FeedRankingRequestAssembler.amountBucket(values[index]))
                    .as("amount %s", values[index]).isEqualTo(expected[index]);
    }

    @Test
    void preservesPythonDurationBucketBoundariesAndSeparateNoDeadline() {
        assertThat(bucket(29)).isZero();
        assertThat(bucket(30)).isEqualTo(1);
        assertThat(bucket(89)).isEqualTo(1);
        assertThat(bucket(90)).isEqualTo(2);
        assertThat(bucket(179)).isEqualTo(2);
        assertThat(bucket(180)).isEqualTo(3);
        assertThat(FeedRankingRequestAssembler.deadlineBucket(CREATED, null)).isEqualTo(4);
    }

    private static int bucket(int days) {
        return FeedRankingRequestAssembler.deadlineBucket(CREATED, LocalDate.of(2026, 1, 2).plusDays(days));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reusesSameAuthorMonthWithinRequestButKeepsCompletedMonthsAndLaterRequestsFresh() {
        var jdbc=mock(JdbcTemplate.class);
        var cards=mock(SharedCardQueryRepository.class);
        var metrics=mock(FeedMonthMetricsService.class);
        var visits=mock(FeedVisitSignals.class);
        var classifier=mock(FeedCategoryClassifier.class);
        UUID viewer=UUID.randomUUID(),viewerAccount=UUID.randomUUID(),author=UUID.randomUUID(),account=UUID.randomUUID(),academy=UUID.randomUUID();
        Instant asOf=Instant.parse("2026-07-18T03:00:00Z");
        doReturn(List.of(viewerAccount)).when(jdbc).query(anyString(),any(RowMapper.class),eq(viewer),eq(academy));
        doReturn(List.of()).when(jdbc).query(anyString(),any(RowMapper.class),eq(viewerAccount));
        when(visits.at(viewer,academy,asOf)).thenReturn(new FeedVisitSignals.Signals(Set.of(),Set.of()));
        when(classifier.classify("축구공")).thenReturn("스포츠");
        when(classifier.version()).thenReturn("classifier");
        var first=row(author,account,null);var second=row(author,account,null);
        var completed=row(author,account,Instant.parse("2026-06-20T03:00:00Z"));
        when(cards.findVisibleRecommendationCandidates(viewer,academy,100)).thenReturn(List.of(first,second,completed));
        when(metrics.build(eq(viewerAccount),eq(viewer),eq(academy),any(),eq(asOf))).thenReturn(Map.of("viewer",true));
        when(metrics.build(account,author,academy,YearMonth.of(2026,6),asOf)).thenReturn(Map.of("month","2026-06","value",1),Map.of("month","2026-06","value",2));
        when(metrics.build(account,author,academy,YearMonth.of(2026,5),asOf)).thenReturn(Map.of("month","2026-05"));
        var assembler=new FeedRankingRequestAssembler(jdbc,cards,metrics,visits,classifier);
        var request=assembler.assemble(UUID.randomUUID(),UUID.randomUUID(),viewer,academy,asOf,new FeedRankingDeadline(System::nanoTime,Duration.ofMinutes(1)));
        assertThat(request.candidates()).extracting(c->c.get("author_previous_month")).containsExactly(
            Map.of("month","2026-06","value",1),Map.of("month","2026-06","value",1),Map.of("month","2026-05"));
        verify(metrics).build(account,author,academy,YearMonth.of(2026,6),asOf);
        verify(classifier).classify("축구공");
        var refreshed=assembler.assemble(UUID.randomUUID(),UUID.randomUUID(),viewer,academy,asOf,new FeedRankingDeadline(System::nanoTime,Duration.ofMinutes(1)));
        assertThat(refreshed.candidates().getFirst().get("author_previous_month")).isEqualTo(Map.of("month","2026-06","value",2));
        verify(metrics,times(2)).build(account,author,academy,YearMonth.of(2026,6),asOf);
        verify(metrics,times(2)).build(account,author,academy,YearMonth.of(2026,5),asOf);
    }

    private static SharedCardQueryRepository.Row row(UUID author,UUID account,Instant completedAt) {
        var row=mock(SharedCardQueryRepository.Row.class);
        when(row.sharedCardId()).thenReturn(UUID.randomUUID());when(row.ownerId()).thenReturn(author);
        when(row.accountId()).thenReturn(account);when(row.purpose()).thenReturn("축구공");
        when(row.state()).thenReturn(WishState.IN_PROGRESS);when(row.createdAt()).thenReturn(CREATED);
        when(row.contentUpdatedAt()).thenReturn(CREATED);when(row.completedAt()).thenReturn(completedAt);
        return row;
    }
}
