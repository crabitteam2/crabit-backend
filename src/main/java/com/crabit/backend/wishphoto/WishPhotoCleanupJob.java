package com.crabit.backend.wishphoto;

import java.time.Clock;
import com.crabit.backend.wishphoto.googlecloud.WishPhotoClock;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class WishPhotoCleanupJob {
	private final JdbcTemplate jdbc;
	private final WishPhotoService photos;
	private final Clock clock;

	WishPhotoCleanupJob(JdbcTemplate jdbc, WishPhotoService photos, WishPhotoClock photoClock) {
		this.jdbc = jdbc; this.photos = photos; this.clock = photoClock.value();
	}

	@Scheduled(fixedDelayString = "${crabit.wish-photo.cleanup-delay-ms:30000}",
			initialDelayString = "${crabit.wish-photo.cleanup-delay-ms:30000}")
	void cleanOne() {
		java.time.Instant instant = clock.instant();
		Timestamp now = Timestamp.from(instant);
		jdbc.update("DELETE FROM wish_photo_upload_receipt "
				+ "WHERE (outcome ->> 'retainUntil')::timestamptz <= ?", now);
		photos.expireOnePending(instant);
		WishPhotoService.CleanupWork work = photos.prepareOneCleanup(instant);
		if (work == null) return;
		try {
			photos.deleteCleanupObject(work);
		} catch (RuntimeException exception) {
			photos.deferCleanup(work, clock.instant());
			return;
		}
		photos.completeCleanup(work, clock.instant());
	}
}
