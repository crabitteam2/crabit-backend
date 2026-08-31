package com.crabit.backend.wishphoto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.api.WishApiIntegrationSupport;
import com.crabit.backend.e2e.SeedFixtureCatalog;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(WishPhotoCleanupIT.CleanupTestConfiguration.class)
@TestPropertySource(properties = {
		"crabit.wish-photo.enabled=true",
		"crabit.wish-photo.cleanup-delay-ms=3600000"
})
class WishPhotoCleanupIT extends WishApiIntegrationSupport {
	@Autowired
	private WishPhotoService photos;
	@Autowired
	private WishPhotoCleanupJob cleanup;
	@Autowired
	private FailingWishPhotoStorage storage;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void resetStorage() {
		storage.reset();
	}

	@Test
	void partialPutWithFailedCompensationPersistsCleanupWithoutPhotoRow() throws Exception {
		storage.failPutAfterWrite = true;
		storage.failDelete = true;

		assertThatThrownBy(() -> photos.upload(SeedFixtureCatalog.OWNER_ID,
				"partial-put-cleanup", jpeg(), "image/jpeg"))
				.isInstanceOf(WishPhotoException.class)
				.extracting(value -> ((WishPhotoException) value).code())
				.isEqualTo(WishPhotoException.Code.PHOTO_PROCESSING_UNAVAILABLE);

		assertDurableOrphanCleanup();
	}

	@Test
	void rollbackWithFailedCompensationPersistsCleanupWithoutPhotoRow() throws Exception {
		storage.failDelete = true;
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			photos.upload(SeedFixtureCatalog.OWNER_ID, "rollback-cleanup", jpegUnchecked(), "image/jpeg");
			throw new ForcedRollback();
		})).isInstanceOf(ForcedRollback.class);

		assertDurableOrphanCleanup();
	}

	@Test
	void hardCleanupRetainsARevokedReceiptAndFailsBothReplayShapesClosed() throws Exception {
		byte[] original = jpeg();
		WishPhotoService.UploadOutcome uploaded = photos.upload(SeedFixtureCatalog.OWNER_ID,
				"retained-after-cleanup", original, "image/jpeg");
		photos.cancel(SeedFixtureCatalog.OWNER_ID, uploaded.photo().id());

		cleanup.cleanOne();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM wish_photo", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT outcome ->> 'kind' "
				+ "FROM wish_photo_upload_receipt WHERE owner_student_id = ? "
				+ "AND idempotency_key = ?", String.class, SeedFixtureCatalog.OWNER_ID,
				"retained-after-cleanup")).isEqualTo("REVOKED_SUCCESS");
		assertThat(jdbc.queryForObject("SELECT photo_id FROM wish_photo_upload_receipt "
				+ "WHERE owner_student_id = ? AND idempotency_key = ?", java.util.UUID.class,
				SeedFixtureCatalog.OWNER_ID, "retained-after-cleanup"))
				.isEqualTo(uploaded.photo().id());
		assertPhotoError(WishPhotoException.Code.WISH_PHOTO_EXPIRED,
				() -> photos.upload(SeedFixtureCatalog.OWNER_ID, "retained-after-cleanup",
						original, "image/jpeg"));
		assertPhotoError(WishPhotoException.Code.IDEMPOTENCY_KEY_REUSED,
				() -> photos.upload(SeedFixtureCatalog.OWNER_ID, "retained-after-cleanup",
						jpeg(720), "image/jpeg"));
	}

	@Test
	void exactRetentionBoundaryDeletesTheReceiptAndAllowsANewUpload() throws Exception {
		byte[] original = jpeg();
		WishPhotoService.UploadOutcome first = photos.upload(SeedFixtureCatalog.OWNER_ID,
				"retention-boundary", original, "image/jpeg");
		photos.cancel(SeedFixtureCatalog.OWNER_ID, first.photo().id());
		clock.set(COMMAND_TIME.plus(Duration.ofHours(24)));

		WishPhotoService.UploadOutcome reused = photos.upload(SeedFixtureCatalog.OWNER_ID,
				"retention-boundary", original, "image/jpeg");

		assertThat(reused.replayed()).isFalse();
		assertThat(reused.photo().id()).isNotEqualTo(first.photo().id());
		assertThat(jdbc.queryForObject("SELECT outcome ->> 'kind' "
				+ "FROM wish_photo_upload_receipt WHERE owner_student_id = ? "
				+ "AND idempotency_key = ?", String.class, SeedFixtureCatalog.OWNER_ID,
				"retention-boundary")).isEqualTo("ACTIVE_SUCCESS");
		assertThat(jdbc.queryForObject("SELECT (outcome ->> 'retainUntil')::timestamptz "
				+ "FROM wish_photo_upload_receipt WHERE owner_student_id = ? "
				+ "AND idempotency_key = ?", java.sql.Timestamp.class,
				SeedFixtureCatalog.OWNER_ID, "retention-boundary").toInstant())
				.isEqualTo(COMMAND_TIME.plus(Duration.ofHours(48)));
	}

	@Test
	void pendingExpiryRevokesTheReceiptBeforeHardCleanup() throws Exception {
		byte[] original = jpeg();
		WishPhotoService.UploadOutcome uploaded = photos.upload(SeedFixtureCatalog.OWNER_ID,
				"pending-expiry", original, "image/jpeg");
		jdbc.update("UPDATE wish_photo SET expires_at = ? WHERE id = ?",
				java.sql.Timestamp.from(COMMAND_TIME.plus(Duration.ofHours(1))),
				uploaded.photo().id());
		clock.set(COMMAND_TIME.plus(Duration.ofHours(1)));

		cleanup.cleanOne();

		assertThat(jdbc.queryForObject("SELECT outcome ->> 'kind' "
				+ "FROM wish_photo_upload_receipt WHERE owner_student_id = ? "
				+ "AND idempotency_key = ?", String.class, SeedFixtureCatalog.OWNER_ID,
				"pending-expiry")).isEqualTo("REVOKED_SUCCESS");
		assertPhotoError(WishPhotoException.Code.WISH_PHOTO_EXPIRED,
				() -> photos.upload(SeedFixtureCatalog.OWNER_ID, "pending-expiry",
						original, "image/jpeg"));
		assertPhotoError(WishPhotoException.Code.IDEMPOTENCY_KEY_REUSED,
				() -> photos.upload(SeedFixtureCatalog.OWNER_ID, "pending-expiry",
						jpeg(720), "image/jpeg"));
	}

	@Test
	void missingActivePhotoIsRepairedToRevokedAndFailsReplayClosed() throws Exception {
		byte[] original = jpeg();
		WishPhotoService.UploadOutcome uploaded = photos.upload(SeedFixtureCatalog.OWNER_ID,
				"missing-active-photo", original, "image/jpeg");
		jdbc.update("DELETE FROM wish_photo WHERE id = ?", uploaded.photo().id());

		assertPhotoError(WishPhotoException.Code.WISH_PHOTO_EXPIRED,
				() -> photos.upload(SeedFixtureCatalog.OWNER_ID, "missing-active-photo",
						original, "image/jpeg"));

		assertThat(jdbc.queryForObject("SELECT outcome ->> 'kind' "
				+ "FROM wish_photo_upload_receipt WHERE owner_student_id = ? "
				+ "AND idempotency_key = ?", String.class, SeedFixtureCatalog.OWNER_ID,
				"missing-active-photo")).isEqualTo("REVOKED_SUCCESS");
	}

	@Test
	void initialDeliveryFailureLeavesNoPhotoOrReceiptAndAllowsARealRetry() throws Exception {
		byte[] original = jpeg();
		storage.failSignedUrls = true;

		assertPhotoError(WishPhotoException.Code.PHOTO_DELIVERY_UNAVAILABLE,
				() -> photos.upload(SeedFixtureCatalog.OWNER_ID, "initial-delivery-failure",
						original, "image/jpeg"));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM wish_photo", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wish_photo_upload_receipt",
				Long.class)).isZero();
		assertThat(storage.objects).isEmpty();
		storage.failSignedUrls = false;
		assertThat(photos.upload(SeedFixtureCatalog.OWNER_ID, "initial-delivery-failure",
				original, "image/jpeg").replayed()).isFalse();
	}

	@Test
	void replayDeliveryFailurePreservesTheActiveReceiptForALaterReplay() throws Exception {
		byte[] original = jpeg();
		WishPhotoService.UploadOutcome first = photos.upload(SeedFixtureCatalog.OWNER_ID,
				"replay-delivery-failure", original, "image/jpeg");
		storage.failSignedUrls = true;

		assertPhotoError(WishPhotoException.Code.PHOTO_DELIVERY_UNAVAILABLE,
				() -> photos.upload(SeedFixtureCatalog.OWNER_ID, "replay-delivery-failure",
						original, "image/jpeg"));

		assertThat(jdbc.queryForObject("SELECT outcome ->> 'kind' "
				+ "FROM wish_photo_upload_receipt WHERE owner_student_id = ? "
				+ "AND idempotency_key = ?", String.class, SeedFixtureCatalog.OWNER_ID,
				"replay-delivery-failure")).isEqualTo("ACTIVE_SUCCESS");
		storage.failSignedUrls = false;
		WishPhotoService.UploadOutcome replay = photos.upload(SeedFixtureCatalog.OWNER_ID,
				"replay-delivery-failure", original, "image/jpeg");
		assertThat(replay.replayed()).isTrue();
		assertThat(replay.photo().id()).isEqualTo(first.photo().id());
	}

	@Test
	void receiptPersistsOnlyTheFiveApprovedLogicalValues() throws Exception {
		WishPhotoService.UploadOutcome uploaded = photos.upload(SeedFixtureCatalog.OWNER_ID,
				"minimal-receipt", jpeg(), "image/jpeg");

		assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
				+ "WHERE table_schema = 'public' AND table_name = 'wish_photo_upload_receipt' "
				+ "ORDER BY ordinal_position", String.class)).containsExactly(
				"owner_student_id", "idempotency_key", "content_digest", "outcome", "photo_id");
		assertThat(jdbc.queryForList("SELECT key FROM wish_photo_upload_receipt, "
				+ "LATERAL jsonb_object_keys(outcome) AS key "
				+ "WHERE photo_id = ? ORDER BY key", String.class, uploaded.photo().id()))
				.containsExactly("kind", "retainUntil");
	}

	private void assertDurableOrphanCleanup() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wish_photo", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wish_photo_cleanup_work", Long.class))
				.isEqualTo(1L);
		String prefix = jdbc.queryForObject(
				"SELECT object_prefix FROM wish_photo_cleanup_work", String.class);
		assertThat(storage.objects).contains(prefix);

		storage.failDelete = false;
		cleanup.cleanOne();

		assertThat(storage.objects).doesNotContain(prefix);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wish_photo_cleanup_work", Long.class))
				.isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wish_photo", Long.class)).isZero();
	}

	private static byte[] jpegUnchecked() {
		try {
			return jpeg();
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static byte[] jpeg() throws Exception { return jpeg(1080); }

	private static byte[] jpeg(int size) throws Exception {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		var graphics = image.createGraphics();
		graphics.setColor(Color.ORANGE);
		graphics.fillRect(0, 0, size, size);
		graphics.dispose();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(image, "jpeg", output);
		return output.toByteArray();
	}

	private static void assertPhotoError(WishPhotoException.Code code,
			org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call)
				.isInstanceOf(WishPhotoException.class)
				.extracting(value -> ((WishPhotoException) value).code())
				.isEqualTo(code);
	}

	private static final class ForcedRollback extends RuntimeException {}

	@TestConfiguration(proxyBeanMethods = false)
	static class CleanupTestConfiguration {
		@Bean
		@Primary
		WishPhotoSafetyScanner allowAllWishPhotos() {
			return bytes -> true;
		}

		@Bean
		@Primary
		FailingWishPhotoStorage failingWishPhotoStorage() {
			return new FailingWishPhotoStorage();
		}
	}

	static final class FailingWishPhotoStorage implements WishPhotoStorage {
		private final Set<String> objects = ConcurrentHashMap.newKeySet();
		private volatile boolean failPutAfterWrite;
		private volatile boolean failDelete;
		private volatile boolean failSignedUrls;

		@Override
		public void put(String prefix, Map<Variant, byte[]> variants) {
			objects.add(prefix);
			if (failPutAfterWrite) throw new IllegalStateException("partial put");
		}

		@Override
		public void delete(String prefix) {
			if (failDelete) throw new IllegalStateException("delete unavailable");
			objects.remove(prefix);
		}

		@Override
		public WishPhotoView.Variants signedUrls(String prefix, Duration validity) {
			if (failSignedUrls) throw new IllegalStateException("signing unavailable");
			return new WishPhotoView.Variants("small", "medium", "large");
		}

		void reset() {
			objects.clear();
			failPutAfterWrite = false;
			failDelete = false;
			failSignedUrls = false;
		}
	}
}
