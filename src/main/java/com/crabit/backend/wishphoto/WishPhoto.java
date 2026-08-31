package com.crabit.backend.wishphoto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "wish_photo")
public class WishPhoto {
	@Id private UUID id;
	@Column(name = "owner_student_id", nullable = false, updatable = false) private UUID ownerStudentId;
	@Column(name = "attached_wish_id") private UUID attachedWishId;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private WishPhotoState state;
	@Column(name = "content_digest", nullable = false, length = 64, updatable = false) private String contentDigest;
	@Column(name = "object_prefix", nullable = false, length = 300, updatable = false) private String objectPrefix;
	@Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
	@Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
	@Column(name = "delete_requested_at") private Instant deleteRequestedAt;
	@Version private long version;

	protected WishPhoto() {}

	private WishPhoto(UUID id, UUID ownerStudentId, String contentDigest, String objectPrefix,
			Instant createdAt, Instant expiresAt) {
		this.id = Objects.requireNonNull(id);
		this.ownerStudentId = Objects.requireNonNull(ownerStudentId);
		this.contentDigest = Objects.requireNonNull(contentDigest);
		this.objectPrefix = Objects.requireNonNull(objectPrefix);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.expiresAt = Objects.requireNonNull(expiresAt);
		this.state = WishPhotoState.PENDING;
	}

	public static WishPhoto pending(UUID ownerStudentId, String digest, Instant now) {
		UUID id = UUID.randomUUID();
		return new WishPhoto(id, ownerStudentId, digest, "wish-photos/" + ownerStudentId + "/" + id,
				now, now.plusSeconds(24 * 60 * 60));
	}

	public void attach(UUID wishId, Instant now) {
		if (state != WishPhotoState.PENDING || !now.isBefore(expiresAt)) {
			throw new WishPhotoException(state == WishPhotoState.ATTACHED
					? WishPhotoException.Code.WISH_PHOTO_ALREADY_ATTACHED
					: WishPhotoException.Code.WISH_PHOTO_EXPIRED, "Wish photo is not attachable.");
		}
		attachedWishId = Objects.requireNonNull(wishId);
		state = WishPhotoState.ATTACHED;
	}

	public void requestDeletion(Instant now) {
		if (state == WishPhotoState.ATTACHED) {
			throw new WishPhotoException(WishPhotoException.Code.WISH_PHOTO_ALREADY_ATTACHED,
					"An attached Wish photo cannot be cancelled.");
		}
		if (state == WishPhotoState.DELETE_PENDING) return;
		state = WishPhotoState.DELETE_PENDING;
		deleteRequestedAt = Objects.requireNonNull(now);
	}

	public void detach(Instant now) {
		if (state != WishPhotoState.ATTACHED) throw new IllegalStateException("Photo is not attached");
		state = WishPhotoState.DELETE_PENDING;
		attachedWishId = null;
		deleteRequestedAt = Objects.requireNonNull(now);
	}

	public UUID id() { return id; }
	public UUID ownerStudentId() { return ownerStudentId; }
	public UUID attachedWishId() { return attachedWishId; }
	public WishPhotoState state() { return state; }
	public String contentDigest() { return contentDigest; }
	public String objectPrefix() { return objectPrefix; }
	public Instant expiresAt() { return expiresAt; }
}
