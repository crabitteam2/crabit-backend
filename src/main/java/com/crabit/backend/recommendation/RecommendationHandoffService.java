package com.crabit.backend.recommendation;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
		name = "crabit.recommendation.handoff.enabled", havingValue = "true")
final class RecommendationHandoffService {

	private static final Logger LOGGER =
			LoggerFactory.getLogger(RecommendationHandoffService.class);

	private final RecommendationSnapshotService snapshots;
	private final RecommendationReceiverClient receiver;
	private final MeterRegistry meterRegistry;

	RecommendationHandoffService(
			RecommendationSnapshotService snapshots,
			RecommendationReceiverClient receiver,
			ObjectProvider<MeterRegistry> meterRegistries) {
		this.snapshots = snapshots;
		this.receiver = receiver;
		this.meterRegistry = meterRegistries.getIfAvailable();
	}

	void deliver(UUID handoffId, UUID accountId) {
		long startedAt = System.nanoTime();
		RecommendationSnapshotService.SnapshotResult snapshot = null;
		String outcome = "SUCCESS";
		try {
			snapshot = snapshots.assemble(handoffId, accountId);
			receiver.send(snapshot.payload());
		}
		catch (RecommendationHandoffException exception) {
			outcome = exception.code().name();
			throw exception;
		}
		finally {
			record(handoffId, outcome, snapshot, startedAt);
		}
	}

	private void record(
			UUID handoffId,
			String outcome,
			RecommendationSnapshotService.SnapshotResult snapshot,
			long startedAt) {
		int viewerWishCount = snapshot == null ? 0 : snapshot.viewerWishCount();
		int candidateCount = snapshot == null ? 0 : snapshot.candidateCount();
		boolean viewerTruncated = snapshot != null && snapshot.viewerWishesTruncated();
		boolean candidatesTruncated = snapshot != null && snapshot.candidatesTruncated();
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
		LOGGER.info(
				"recommendation_handoff handoff_id={} outcome={} viewer_wish_count={} "
						+ "candidate_count={} viewer_wishes_truncated={} candidates_truncated={} "
						+ "elapsed_ms={}",
				handoffId, outcome, viewerWishCount, candidateCount,
				viewerTruncated, candidatesTruncated, elapsedMillis);
		if (meterRegistry != null) {
			meterRegistry.counter(
					"crabit.recommendation.handoff", "outcome", outcome).increment();
		}
	}
}
