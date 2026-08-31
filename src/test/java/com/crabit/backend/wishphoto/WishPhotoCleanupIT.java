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

	private static byte[] jpeg() throws Exception {
		BufferedImage image = new BufferedImage(1080, 1080, BufferedImage.TYPE_INT_RGB);
		var graphics = image.createGraphics();
		graphics.setColor(Color.ORANGE);
		graphics.fillRect(0, 0, 1080, 1080);
		graphics.dispose();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(image, "jpeg", output);
		return output.toByteArray();
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
			return new WishPhotoView.Variants("small", "medium", "large");
		}

		void reset() {
			objects.clear();
			failPutAfterWrite = false;
			failDelete = false;
		}
	}
}
