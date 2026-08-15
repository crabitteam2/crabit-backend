package com.crabit.backend.wish;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "mismatch_notification_outbox", uniqueConstraints = {
		@UniqueConstraint(name = "uk_mismatch_notification_case", columnNames = "adjustment_case_id")
})
public class MismatchNotificationOutbox {

	@Id
	private UUID id;

	@Column(name = "adjustment_case_id", nullable = false, updatable = false)
	private UUID adjustmentCaseId;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "adjustment_case_id", nullable = false, insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_mismatch_notification_case"))
	private BalanceAdjustmentCase adjustmentCase;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	protected MismatchNotificationOutbox() {
	}

	public MismatchNotificationOutbox(UUID adjustmentCaseId, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.adjustmentCaseId = Objects.requireNonNull(adjustmentCaseId, "adjustmentCaseId");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
	}

	public void markPublished(Instant when) {
		if (publishedAt != null) {
			throw new IllegalStateException("Mismatch notification was already published");
		}
		publishedAt = Objects.requireNonNull(when, "when");
	}
}
