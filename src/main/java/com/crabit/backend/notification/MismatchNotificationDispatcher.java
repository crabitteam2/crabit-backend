package com.crabit.backend.notification;

import com.crabit.backend.wish.MismatchNotificationOutbox;
import com.crabit.backend.wish.MismatchNotificationOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MismatchNotificationDispatcher {

	private static final int BATCH_SIZE = 100;

	private final MismatchNotificationOutboxRepository outbox;
	private final MismatchNotificationPublisher publisher;
	private final Clock clock;

	public MismatchNotificationDispatcher(
			MismatchNotificationOutboxRepository outbox,
			MismatchNotificationPublisher publisher,
			Clock clock) {
		this.outbox = outbox;
		this.publisher = publisher;
		this.clock = clock;
	}

	@Transactional
	public int dispatchPending() {
		List<MismatchNotificationOutbox> pending =
				outbox.lockUnpublished(PageRequest.of(0, BATCH_SIZE));
		for (MismatchNotificationOutbox entry : pending) {
			publisher.publish(MismatchNotification.forAdjustmentCase(
					entry.adjustmentCaseId(), entry.accountId()));
			Instant publishedAt = clock.instant();
			entry.markPublished(publishedAt);
		}
		return pending.size();
	}
}
