package com.crabit.backend.relationship;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "friend_request", check = {
		@CheckConstraint(name = "ck_friend_request_distinct_students", constraint = "sender_id <> receiver_id"),
		@CheckConstraint(name = "ck_friend_request_processed", constraint = "(status = 'PENDING' and processed_at is null) or (status <> 'PENDING' and processed_at is not null)")
})
public class FriendRequest {

	@Id
	private UUID id;

	@Column(name = "academy_id", nullable = false, updatable = false)
	private UUID academyId;

	@Column(name = "sender_id", nullable = false, updatable = false)
	private UUID senderId;

	@Column(name = "receiver_id", nullable = false, updatable = false)
	private UUID receiverId;

	@Column(name = "student_low_id", nullable = false, updatable = false)
	private UUID studentLowId;

	@Column(name = "student_high_id", nullable = false, updatable = false)
	private UUID studentHighId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private FriendRequestStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "processed_at")
	private Instant processedAt;

	protected FriendRequest() {
	}

	public FriendRequest(UUID academyId, UUID senderId, UUID receiverId, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.academyId = Objects.requireNonNull(academyId, "academyId");
		this.senderId = Objects.requireNonNull(senderId, "senderId");
		this.receiverId = Objects.requireNonNull(receiverId, "receiverId");
		if (senderId.equals(receiverId)) {
			throw new IllegalArgumentException("A student cannot send a friend request to themselves");
		}
		if (senderId.toString().compareTo(receiverId.toString()) < 0) {
			this.studentLowId = senderId;
			this.studentHighId = receiverId;
		} else {
			this.studentLowId = receiverId;
			this.studentHighId = senderId;
		}
		this.status = FriendRequestStatus.PENDING;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
	}

	public void accept(Instant when) { process(FriendRequestStatus.ACCEPTED, when); }
	public void reject(Instant when) { process(FriendRequestStatus.REJECTED, when); }
	public void cancel(Instant when) { process(FriendRequestStatus.CANCELED, when); }

	private void process(FriendRequestStatus next, Instant when) {
		if (status != FriendRequestStatus.PENDING) {
			throw new IllegalStateException("Friend request is no longer pending");
		}
		Instant processed = Objects.requireNonNull(when, "when");
		if (processed.isBefore(createdAt)) {
			throw new IllegalArgumentException("Processed time cannot precede request creation");
		}
		status = next;
		processedAt = processed;
	}

	public UUID id() { return id; }
	public UUID academyId() { return academyId; }
	public UUID senderId() { return senderId; }
	public UUID receiverId() { return receiverId; }
	public UUID studentLowId() { return studentLowId; }
	public UUID studentHighId() { return studentHighId; }
	public FriendRequestStatus status() { return status; }
	public Instant createdAt() { return createdAt; }
	public Instant processedAt() { return processedAt; }
	public boolean isPending() { return status == FriendRequestStatus.PENDING; }
}
