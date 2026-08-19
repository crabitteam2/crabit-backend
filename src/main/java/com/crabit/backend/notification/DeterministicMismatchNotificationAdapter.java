package com.crabit.backend.notification;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
public final class DeterministicMismatchNotificationAdapter
		implements MismatchNotificationPublisher {

	private final Map<UUID, Queue<DeliveryResult>> scripts = new LinkedHashMap<>();
	private final Map<UUID, MismatchNotification> deliveries = new LinkedHashMap<>();

	public synchronized void enqueueFailure(UUID adjustmentCaseId) {
		enqueue(adjustmentCaseId, DeliveryResult.FAILURE);
	}

	public synchronized void enqueueSuccess(UUID adjustmentCaseId) {
		enqueue(adjustmentCaseId, DeliveryResult.SUCCESS);
	}

	public synchronized List<MismatchNotification> deliveries() {
		return List.copyOf(deliveries.values());
	}

	public synchronized void clear() {
		scripts.clear();
		deliveries.clear();
	}

	@Override
	public synchronized void publish(MismatchNotification notification) {
		MismatchNotification requested = Objects.requireNonNull(notification, "notification");
		MismatchNotification existing = deliveries.get(requested.idempotencyKey());
		if (existing != null) {
			if (!existing.equals(requested)) {
				throw new IllegalStateException(
						"Notification idempotency key was reused with different content");
			}
			return;
		}
		Queue<DeliveryResult> outcomes = scripts.get(requested.idempotencyKey());
		DeliveryResult outcome = outcomes == null ? DeliveryResult.SUCCESS : outcomes.poll();
		if (outcomes != null && outcomes.isEmpty()) {
			scripts.remove(requested.idempotencyKey());
		}
		if (outcome == DeliveryResult.FAILURE) {
			throw new MismatchNotificationDeliveryException(
					"Deterministic mismatch notification delivery failed");
		}
		deliveries.put(requested.idempotencyKey(), requested);
	}

	private void enqueue(UUID adjustmentCaseId, DeliveryResult outcome) {
		scripts.computeIfAbsent(
				Objects.requireNonNull(adjustmentCaseId, "adjustmentCaseId"),
				ignored -> new ArrayDeque<>())
				.add(Objects.requireNonNull(outcome, "outcome"));
	}

	private enum DeliveryResult {
		SUCCESS,
		FAILURE
	}
}
