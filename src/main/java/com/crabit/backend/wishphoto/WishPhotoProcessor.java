package com.crabit.backend.wishphoto;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
final class WishPhotoProcessor {
	static final int MAX_BYTES = 5 * 1024 * 1024;

	Map<WishPhotoStorage.Variant, byte[]> process(byte[] source, String contentType) {
		if (source.length > MAX_BYTES) throw new WishPhotoException(
				WishPhotoException.Code.PHOTO_TOO_LARGE, "Wish photo exceeds 5 MiB.");
		if (!"image/jpeg".equalsIgnoreCase(contentType)) throw new WishPhotoException(
				WishPhotoException.Code.UNSUPPORTED_PHOTO_TYPE, "Wish photo must be JPEG.");
		if (source.length < 4 || (source[0] & 0xff) != 0xff || (source[1] & 0xff) != 0xd8
				|| (source[source.length - 2] & 0xff) != 0xff
				|| (source[source.length - 1] & 0xff) != 0xd9) {
			throw new WishPhotoException(WishPhotoException.Code.UNSUPPORTED_PHOTO_TYPE,
					"Wish photo bytes must be JPEG.");
		}
		try {
			BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
			if (decoded == null) throw invalid();
			if (decoded.getWidth() != 1080 || decoded.getHeight() != 1080) throw invalid();
			Map<WishPhotoStorage.Variant, byte[]> variants = new EnumMap<>(WishPhotoStorage.Variant.class);
			variants.put(WishPhotoStorage.Variant.LARGE, encode(resize(decoded, 1080)));
			variants.put(WishPhotoStorage.Variant.MEDIUM, encode(resize(decoded, 720)));
			variants.put(WishPhotoStorage.Variant.SMALL, encode(resize(decoded, 360)));
			return variants;
		} catch (IOException exception) {
			throw invalid();
		}
	}

	private static BufferedImage resize(BufferedImage source, int size) {
		BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = result.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.drawImage(source, 0, 0, size, size, null);
		} finally { graphics.dispose(); }
		return result;
	}

	private static byte[] encode(BufferedImage image) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "jpeg", output)) throw invalid();
		return output.toByteArray();
	}

	private static WishPhotoException invalid() {
		return new WishPhotoException(WishPhotoException.Code.INVALID_PHOTO,
				"Wish photo must be a decodable 1080x1080 JPEG.");
	}
}
