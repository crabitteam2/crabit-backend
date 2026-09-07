package com.crabit.backend.recommendation;

import com.crabit.backend.wish.SharedCardQueryRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Builds the privacy-minimal, bounded wire request before any ranking HTTP call. */
@Service
public class FeedRankingRequestAssembler {
    private final JdbcTemplate jdbc;
    private final SharedCardQueryRepository cards;
    private final FeedMonthMetricsService metrics;
    private final FeedVisitSignals visits;
    private final FeedCategoryClassifier classifier;

    public FeedRankingRequestAssembler(JdbcTemplate jdbc, SharedCardQueryRepository cards,
            FeedMonthMetricsService metrics, FeedVisitSignals visits,
            FeedCategoryClassifier classifier) {
        this.jdbc = jdbc;
        this.cards = cards;
        this.metrics = metrics;
        this.visits = visits;
        this.classifier = classifier;
    }

    /** Must be invoked under the caller-owned 500 ms deadline, outside an HTTP-spanning transaction. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FeedRankingModels.Request assemble(UUID requestId, UUID contextId, UUID viewerId,
            UUID academyId, Instant recommendationAt, FeedRankingDeadline deadline) {
        if (deadline.expired()) throw new DeadlineExceeded();
        UUID viewerAccount = account(viewerId, academyId);
        YearMonth viewerMonth = FeedMonthMetricsService.previousMonth(recommendationAt);
        Map<String, Object> viewerMetrics = metrics.build(
                viewerAccount, viewerId, academyId, viewerMonth, recommendationAt);
        FeedVisitSignals.Signals signals = visits.at(viewerId, academyId, recommendationAt);
        Representative representative = representative(viewerAccount);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (SharedCardQueryRepository.Row row
                : cards.findVisibleRecommendationCandidates(viewerId, academyId, 100)) {
            if (deadline.expired()) throw new DeadlineExceeded();
            String category = classifier.classify(row.purpose());
            YearMonth authorMonth = row.completedAt() == null
                    ? viewerMonth : FeedMonthMetricsService.previousMonth(row.completedAt());
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("card_id", row.sharedCardId());
            candidate.put("author_id", row.ownerId());
            candidate.put("state", row.completedAt() == null ? row.state().name() : "COMPLETED");
            candidate.put("created_at", row.createdAt());
            candidate.put("target_date", row.targetDate());
            candidate.put("closed_at", row.completedAt());
            candidate.put("content_updated_at", row.contentUpdatedAt());
            candidate.put("category_id", category);
            candidate.put("basic_similarity", basicSimilarity(representative, category,
                    row.targetAmount(), row.createdAt(), row.targetDate()));
            candidate.put("title_similarity", representative == null ? 0.0
                    : FeedTitleSimilarity.ratio(representative.title(), row.purpose()));
            candidate.put("visited_author_before", signals.authors().contains(row.ownerId()));
            candidate.put("visited_category_before", signals.categories().contains(category));
            candidate.put("author_previous_month", metrics.build(row.accountId(), row.ownerId(),
                    academyId, authorMonth, recommendationAt));
            candidates.add(candidate);
        }
        return new FeedRankingModels.Request(1, requestId, contextId, viewerId, academyId,
                recommendationAt, "Asia/Seoul", "feed-features-v1", classifier.version(),
                viewerMetrics, candidates);
    }

    private UUID account(UUID student, UUID academy) {
        List<UUID> ids = jdbc.query("SELECT id FROM card_balance_account WHERE student_id=?"
                        + " AND academy_id=? AND closed_at IS NULL", (rs, ignored) ->
                        rs.getObject(1, UUID.class), student, academy);
        if (ids.size() != 1) throw new IllegalStateException("Viewer requires one open card account");
        return ids.getFirst();
    }

    private Representative representative(UUID account) {
        List<Representative> explicit = jdbc.query("""
                SELECT w.purpose,w.target_amount,w.created_at,w.target_date
                FROM representative_wish_selection r JOIN wish w ON w.id=r.wish_id
                WHERE r.account_id=? AND w.deleted_at IS NULL
                  AND w.state IN ('IN_PROGRESS','AMOUNT_REACHED')
                """, (rs, ignored) -> new Representative(rs.getString(1),
                classifier.classify(rs.getString(1)), rs.getLong(2),
                rs.getTimestamp(3).toInstant(), rs.getObject(4, java.time.LocalDate.class)),
                account);
        if (!explicit.isEmpty()) return explicit.getFirst();
        return jdbc.query("""
                SELECT purpose,target_amount,created_at,target_date FROM wish
                WHERE account_id=? AND deleted_at IS NULL AND state='IN_PROGRESS'
                ORDER BY created_at,id LIMIT 1
                """, (rs, ignored) -> new Representative(rs.getString(1),
                classifier.classify(rs.getString(1)), rs.getLong(2),
                rs.getTimestamp(3).toInstant(), rs.getObject(4, java.time.LocalDate.class)),
                account).stream().findFirst().orElse(null);
    }

    private static double basicSimilarity(Representative representative, String category,
            long amount, Instant createdAt, java.time.LocalDate targetDate) {
        if (representative == null) return 0.0;
        int matches = 0;
        if (representative.category().equals(category)) matches++;
        if (amountBucket(representative.amount()) == amountBucket(amount)) matches++;
        if (deadlineBucket(representative.createdAt(), representative.targetDate())
                == deadlineBucket(createdAt, targetDate)) matches++;
        return matches / 3.0;
    }

    static int amountBucket(long amount) {
        if (amount < 10_000) return 0;
        if (amount < 30_000) return 1;
        if (amount < 50_000) return 2;
        if (amount < 100_000) return 3;
        if (amount < 300_000) return 4;
        return 5;
    }

    static int deadlineBucket(Instant createdAt, java.time.LocalDate targetDate) {
        if (targetDate == null) return 4;
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                createdAt.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate(), targetDate);
        if (days < 30) return 0;
        if (days < 90) return 1;
        if (days < 180) return 2;
        return 3;
    }

    private record Representative(String title, String category, long amount,
            Instant createdAt, java.time.LocalDate targetDate) {}

    public static final class DeadlineExceeded extends RuntimeException {}
}
