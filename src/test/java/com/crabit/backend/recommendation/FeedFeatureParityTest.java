package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FeedFeatureParityTest {
    @Test
    void productionPayloadMatchesAuditableArtifactByteForByte() throws Exception {
        byte[] artifact = Files.readAllBytes(Path.of("api/recommendation/feed-classifier-v1.json"));
        assertThat(FeedClassifierV1.bytes()).isEqualTo(artifact);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact)))
                .isEqualTo(FeedClassifierV1.ARTIFACT_SHA256);
    }

    @Test
    void localFeaturesMatchPinnedPythonOracle() throws Exception {
        var json = new ObjectMapper();
        var classifier = new FeedCategoryClassifier(json);
        try (var stream = getClass().getResourceAsStream("/recommendation/feed-feature-oracle.json")) {
            var oracle = json.readTree(stream.readAllBytes());
            assertThat(classifier.version()).isEqualTo(oracle.get("classifier_version").stringValue());
            for (var test : oracle.get("categories"))
                assertThat(classifier.classify(test.get("title").stringValue()))
                        .as("category for %s", test.get("title"))
                        .isEqualTo(test.get("category").stringValue());
            for (var test : oracle.get("similarities"))
                assertThat(FeedTitleSimilarity.ratio(test.get("left").stringValue(), test.get("right").stringValue()))
                        .as("SequenceMatcher for %s", test)
                        .isEqualTo(test.get("ratio").doubleValue());
        }
    }

    @Test
    void preparationConsumesTheSameBudgetAndExactDeadlineIsExpired() {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 10);
        var deadline = new FeedRankingDeadline(now::get, Duration.ofMillis(500));
        now.addAndGet(300_000_000);
        assertThat(deadline.remainingNanos()).isEqualTo(200_000_000);
        now.addAndGet(200_000_000);
        assertThat(deadline.expired()).isTrue();
    }
}
