package com.crabit.backend.wish;

import com.crabit.backend.wishphoto.WishPhotoView;
import java.time.Duration;

/** Shared completion display semantics. Call only after current visibility authorization. */
public final class SharedCardCompletionMapper {
	private SharedCardCompletionMapper() {}

	public static SharedCardProjection.Completion project(SharedCardQueryRepository.Row row, WishPhotoView photo) {
		if (row.kind() != SharedCardKind.COMPLETION || row.completedAt() == null) {
			throw new IllegalStateException("Completion Shared Card requires completedAt");
		}
		long duration = Math.max(0L, Duration.between(row.createdAt(), row.completedAt()).getSeconds());
		return new SharedCardProjection.Completion(row.sharedCardId(), "COMPLETION", row.ownerNickname(),
				row.ownerId(), row.startDate(), row.purpose(), row.targetAmount(), 100, row.targetDate(),
				row.createdAt(), row.completedAt(), duration, photo, row.contentUpdatedAt());
	}
}
