package com.crabit.backend.wishphoto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class WishPhotoDomainTest {
	private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

	@Test
	void pendingPhotoAttachesOnceAndAttachedPhotoCannotBeCancelled() {
		WishPhoto photo = WishPhoto.pending(UUID.randomUUID(), "a".repeat(64), NOW);
		UUID wishId = UUID.randomUUID();
		photo.attach(wishId, NOW.plusSeconds(1));
		assertThat(photo.state()).isEqualTo(WishPhotoState.ATTACHED);
		assertThat(photo.attachedWishId()).isEqualTo(wishId);
		assertThatThrownBy(() -> photo.requestDeletion(NOW.plusSeconds(2)))
				.isInstanceOf(WishPhotoException.class)
				.extracting(value -> ((WishPhotoException) value).code())
				.isEqualTo(WishPhotoException.Code.WISH_PHOTO_ALREADY_ATTACHED);
	}

	@Test
	void expiredPendingPhotoCannotAttachButCanEnterCleanup() {
		WishPhoto photo = WishPhoto.pending(UUID.randomUUID(), "b".repeat(64), NOW);
		assertThatThrownBy(() -> photo.attach(UUID.randomUUID(), NOW.plusSeconds(24 * 60 * 60)))
				.isInstanceOf(WishPhotoException.class)
				.extracting(value -> ((WishPhotoException) value).code())
				.isEqualTo(WishPhotoException.Code.WISH_PHOTO_EXPIRED);
		photo.requestDeletion(NOW.plusSeconds(24 * 60 * 60));
		assertThat(photo.state()).isEqualTo(WishPhotoState.DELETE_PENDING);
	}

	@Test
	void processorRequiresExactJpegAndProducesThreeDeterministicSizes() throws Exception {
		WishPhotoProcessor processor = new WishPhotoProcessor();
		var variants = processor.process(jpeg(1080), "image/jpeg");
		assertThat(variants).containsOnlyKeys(WishPhotoStorage.Variant.values());
		for (byte[] bytes : variants.values()) assertThat(ImageIO.read(new java.io.ByteArrayInputStream(bytes))).isNotNull();
		assertThatThrownBy(() -> processor.process(jpeg(720), "image/jpeg"))
				.isInstanceOf(WishPhotoException.class)
				.extracting(value -> ((WishPhotoException) value).code())
				.isEqualTo(WishPhotoException.Code.INVALID_PHOTO);
	}

	@Test
	void processorRejectsExcessiveJpegMetadataBeforeRasterDecode() throws Exception {
		WishPhotoProcessor processor = new WishPhotoProcessor();
		byte[] oversized = jpeg(16);
		setJpegDimensions(oversized, 65_535, 65_535);

		assertThatThrownBy(() -> processor.process(oversized, "image/jpeg"))
				.isInstanceOf(WishPhotoException.class)
				.extracting(value -> ((WishPhotoException) value).code())
				.isEqualTo(WishPhotoException.Code.INVALID_PHOTO);
	}

	private static void setJpegDimensions(byte[] jpeg, int width, int height) {
		for (int index = 2; index < jpeg.length - 8; index++) {
			if ((jpeg[index] & 0xff) != 0xff) continue;
			int marker = jpeg[index + 1] & 0xff;
			if (marker == 0xc0 || marker == 0xc1 || marker == 0xc2) {
				jpeg[index + 5] = (byte) (height >>> 8);
				jpeg[index + 6] = (byte) height;
				jpeg[index + 7] = (byte) (width >>> 8);
				jpeg[index + 8] = (byte) width;
				return;
			}
		}
		throw new AssertionError("JPEG start-of-frame marker not found");
	}

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
}
