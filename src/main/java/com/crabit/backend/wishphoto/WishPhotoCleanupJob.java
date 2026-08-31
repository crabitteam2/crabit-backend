package com.crabit.backend.wishphoto;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class WishPhotoCleanupJob {
	private final JdbcTemplate jdbc;
	private final WishPhotoRepository photos;
	private final WishPhotoStorage storage;
	private final Clock clock;

	WishPhotoCleanupJob(JdbcTemplate jdbc, WishPhotoRepository photos,
			WishPhotoStorage storage, Clock clock) {
		this.jdbc = jdbc; this.photos = photos; this.storage = storage; this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${crabit.wish-photo.cleanup-delay-ms:30000}",
			initialDelayString = "${crabit.wish-photo.cleanup-delay-ms:30000}")
	@Transactional
	void cleanOne() {
		Timestamp now = Timestamp.from(clock.instant());
		jdbc.update("DELETE FROM wish_photo_upload_receipt "
				+ "WHERE (outcome ->> 'retainUntil')::timestamptz <= ?", now);
		jdbc.update("UPDATE wish_photo_upload_receipt receipt SET outcome = jsonb_build_object("
				+ "'kind', 'REVOKED_SUCCESS', 'retainUntil', receipt.outcome ->> 'retainUntil') "
				+ "FROM wish_photo photo WHERE receipt.photo_id = photo.id "
				+ "AND receipt.outcome ->> 'kind' = 'ACTIVE_SUCCESS' "
				+ "AND photo.state = 'PENDING' AND photo.expires_at <= ?", now);
		jdbc.update("UPDATE wish_photo SET state = 'DELETE_PENDING', delete_requested_at = ?, "
				+ "attached_wish_id = NULL WHERE state = 'PENDING' AND expires_at <= ?", now, now);
		jdbc.update("INSERT INTO wish_photo_cleanup_work(photo_id, object_prefix, requested_at, next_attempt_at) "
				+ "SELECT id, object_prefix, ?, ? FROM wish_photo WHERE state = 'DELETE_PENDING' "
				+ "ON CONFLICT (photo_id) DO NOTHING", now, now);
		var rows = jdbc.query("SELECT photo_id, object_prefix, attempt_count FROM wish_photo_cleanup_work "
				+ "WHERE next_attempt_at <= ? ORDER BY requested_at FOR UPDATE SKIP LOCKED LIMIT 1",
				(rs, row) -> new Work(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)),
				Timestamp.from(clock.instant()));
		if (rows.isEmpty()) return;
		Work work = rows.getFirst();
		try {
			jdbc.update("UPDATE wish_photo_upload_receipt SET outcome = jsonb_build_object("
					+ "'kind', 'REVOKED_SUCCESS', 'retainUntil', outcome ->> 'retainUntil') "
					+ "WHERE photo_id = ? AND outcome ->> 'kind' = 'ACTIVE_SUCCESS'", work.photoId());
			storage.delete(work.objectPrefix());
			jdbc.update("DELETE FROM wish_photo_cleanup_work WHERE photo_id = ?", work.photoId());
			photos.deleteById(work.photoId());
		} catch (RuntimeException exception) {
			long delay = Math.min(3600, 1L << Math.min(12, work.attemptCount()));
			jdbc.update("UPDATE wish_photo_cleanup_work SET attempt_count = attempt_count + 1, "
					+ "next_attempt_at = ?, last_error = ? WHERE photo_id = ?",
					Timestamp.from(clock.instant().plus(Duration.ofSeconds(delay))),
					"storage deletion failed", work.photoId());
		}
	}

	private record Work(UUID photoId, String objectPrefix, int attemptCount) {}
}
