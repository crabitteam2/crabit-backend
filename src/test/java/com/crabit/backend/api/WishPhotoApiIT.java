package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.wishphoto.WishPhotoSafetyScanner;
import com.crabit.backend.wishphoto.WishPhotoStorage;
import com.crabit.backend.wishphoto.WishPhotoView;
import com.crabit.backend.e2e.SeedFixtureCatalog;
import com.crabit.backend.e2e.SeedFixtureService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.context.TestPropertySource;

@Import(WishPhotoApiIT.PhotoTestConfiguration.class)
@TestPropertySource(properties = {
		"crabit.wish-photo.enabled=true",
		"crabit.wish-photo.cleanup-delay-ms=3600000"
})
class WishPhotoApiIT extends WishApiIntegrationSupport {
	@Autowired
	private SeedFixtureService fixtures;

	@Test
	void uploadsReplaysAttachesAndRemovesPrivatePhoto() throws Exception {
		byte[] bytes = jpeg(Color.ORANGE);
		String first = asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "wish.jpg", "image/jpeg", bytes))
				.header("Idempotency-Key", "photo-upload-1"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andExpect(jsonPath("$.variants.small").isString())
				.andReturn().getResponse().getContentAsString();
		String photoId = json(first, "$.id");

		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "wish.jpg", "image/jpeg", bytes))
				.header("Idempotency-Key", "photo-upload-1"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(jsonPath("$.id").value(photoId));

		String created = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "wish-with-photo")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"purpose":"Photo Wish","targetAmount":1000,"photoId":"%s"}
						""".formatted(photoId)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.wish.photo.id").value(photoId))
				.andReturn().getResponse().getContentAsString();
		String wishId = json(created, "$.wish.id");
		assertThat(jdbc.queryForObject(
				"SELECT wish_idempotency_records::text FROM student WHERE id = ?",
				String.class, SeedFixtureCatalog.OWNER_ID))
				.doesNotContain("private.test");
		asOwner(patch(WISHES_PATH + "/" + wishId)
				.contentType("application/merge-patch+json")
				.content("""
						{"expectedVersion":0,"photoId":"%s"}
						""".formatted(photoId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.version").value(0))
				.andExpect(jsonPath("$.wish.photo.id").value(photoId));

		asOwner(delete("/v1/wish-photos/{photoId}", photoId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("WISH_PHOTO_ALREADY_ATTACHED"));
		asOwner(get(WISHES_PATH + "/" + wishId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.photo.id").value(photoId));

		String replacement = asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "replacement.jpg", "image/jpeg",
						jpeg(Color.MAGENTA)))
				.header("Idempotency-Key", "photo-upload-2"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String replacementId = json(replacement, "$.id");
		clock.set(COMMAND_TIME.plusSeconds(1));
		asOwner(patch(WISHES_PATH + "/" + wishId)
				.contentType("application/merge-patch+json")
				.content("""
						{"expectedVersion":0,"photoId":"%s"}
						""".formatted(replacementId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.version").value(1))
				.andExpect(jsonPath("$.wish.photo.id").value(replacementId));
		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "wish.jpg", "image/jpeg", bytes))
				.header("Idempotency-Key", "photo-upload-1"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("WISH_PHOTO_EXPIRED"));
		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "different.jpg", "image/jpeg",
						jpeg(Color.GREEN)))
				.header("Idempotency-Key", "photo-upload-1"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
		asOwner(delete("/v1/wish-photos/{photoId}", photoId))
				.andExpect(status().isNoContent());

		clock.set(COMMAND_TIME.plusSeconds(2));
		asOwner(patch(WISHES_PATH + "/" + wishId)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":1,\"photoId\":null}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.version").value(2))
				.andExpect(jsonPath("$.wish.photo").isEmpty());
		asOwner(delete("/v1/wish-photos/{photoId}", replacementId))
				.andExpect(status().isNoContent());
		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "replacement.jpg", "image/jpeg",
						jpeg(Color.MAGENTA)))
				.header("Idempotency-Key", "photo-upload-2"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("WISH_PHOTO_EXPIRED"));
		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "different.jpg", "image/jpeg",
						jpeg(Color.YELLOW)))
				.header("Idempotency-Key", "photo-upload-2"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void cancelledReplayFailsClosedUntilTheExactRetentionBoundaryThenKeyIsReusable()
			throws Exception {
		byte[] cancelledBytes = jpeg(Color.CYAN);
		String cancelled = asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "cancelled.jpg", "image/jpeg", cancelledBytes))
				.header("Idempotency-Key", "cancelled-photo-replay"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String cancelledPhotoId = json(cancelled, "$.id");
		asOwner(delete("/v1/wish-photos/{photoId}", cancelledPhotoId))
				.andExpect(status().isNoContent());

		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "cancelled.jpg", "image/jpeg", cancelledBytes))
				.header("Idempotency-Key", "cancelled-photo-replay"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("WISH_PHOTO_EXPIRED"));
		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "different.jpg", "image/jpeg",
						jpeg(Color.GREEN)))
				.header("Idempotency-Key", "cancelled-photo-replay"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

		clock.set(COMMAND_TIME.plus(Duration.ofHours(24)));
		String reused = asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "cancelled.jpg", "image/jpeg", cancelledBytes))
				.header("Idempotency-Key", "cancelled-photo-replay"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andReturn().getResponse().getContentAsString();
		assertThat(json(reused, "$.id").toString()).isNotEqualTo(cancelledPhotoId);
	}

	@Test
	void wishDeletionRevokesTheAttachedPhotoReceiptForSameAndDifferentContent()
			throws Exception {
		byte[] original = jpeg(Color.PINK);
		String upload = asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "deleted-wish.jpg", "image/jpeg", original))
				.header("Idempotency-Key", "deleted-wish-photo"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String photoId = json(upload, "$.id");
		String created = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "wish-to-delete-with-photo")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"purpose":"Delete Photo Wish","targetAmount":1000,"photoId":"%s"}
						""".formatted(photoId)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String wishId = json(created, "$.wish.id");

		asOwner(delete(WISHES_PATH + "/" + wishId)
				.header(HttpHeaders.IF_MATCH, "0")
				.header("Idempotency-Key", "delete-wish-with-photo"))
				.andExpect(status().isOk());

		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "deleted-wish.jpg", "image/jpeg", original))
				.header("Idempotency-Key", "deleted-wish-photo"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("WISH_PHOTO_EXPIRED"));
		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "different.jpg", "image/jpeg",
						jpeg(Color.GRAY)))
				.header("Idempotency-Key", "deleted-wish-photo"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void concurrentIdenticalUploadsCreateOnePhotoAndOneReplay() throws Exception {
		byte[] bytes = jpeg(Color.LIGHT_GRAY);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<MvcResult> first = executor.submit(() -> concurrentUpload(start, bytes));
			Future<MvcResult> second = executor.submit(() -> concurrentUpload(start, bytes));
			start.countDown();

			MvcResult firstResponse = first.get(15, TimeUnit.SECONDS);
			MvcResult secondResponse = second.get(15, TimeUnit.SECONDS);
			assertThat(List.of(firstResponse.getResponse().getStatus(),
					secondResponse.getResponse().getStatus())).containsOnly(201);
			assertThat(List.of(firstResponse.getResponse().getHeader("Idempotency-Replayed"),
					secondResponse.getResponse().getHeader("Idempotency-Replayed")))
					.containsExactlyInAnyOrder("false", "true");
			assertThat(json(firstResponse.getResponse().getContentAsString(), "$.id").toString())
					.isEqualTo(json(secondResponse.getResponse().getContentAsString(), "$.id").toString());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wish_photo", Long.class)).isOne();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wish_photo_upload_receipt",
				Long.class)).isOne();
	}

	@Test
	void rejectsWrongShapeAndSameKeyWithDifferentContent() throws Exception {
		asOwner(post("/v1/wish-photos")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_PHOTO_TYPE"));
		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "small.jpg", "image/jpeg", jpeg(720, Color.BLUE)))
				.header("Idempotency-Key", "invalid-shape"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error.code").value("INVALID_PHOTO"));
		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "small.jpg", "image/jpeg", jpeg(720, Color.BLUE)))
				.header("Idempotency-Key", "invalid-shape"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error.code").value("INVALID_PHOTO"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wish_photo_processing_attempt",
				Long.class)).isEqualTo(1L);
		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "first.jpg", "image/jpeg", jpeg(Color.RED)))
				.header("Idempotency-Key", "reused-key"))
				.andExpect(status().isCreated());
		asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "second.jpg", "image/jpeg", jpeg(Color.GREEN)))
				.header("Idempotency-Key", "reused-key"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void fixtureResetClearsAllWishPhotoDatabaseState() throws Exception {
		String body = asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "wish.jpg", "image/jpeg", jpeg(Color.CYAN)))
				.header("Idempotency-Key", "photo-reset-isolation"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String photoId = json(body, "$.id");
		asOwner(delete("/v1/wish-photos/{photoId}", photoId))
				.andExpect(status().isNoContent());

		assertThat(photoTableCounts()).containsOnly(1L);

		fixtures.resetAndInitialize();

		assertThat(photoTableCounts()).containsOnly(0L);
	}

	private java.util.List<Long> photoTableCounts() {
		return java.util.List.of(
				jdbc.queryForObject("SELECT count(*) FROM wish_photo", Long.class),
				jdbc.queryForObject("SELECT count(*) FROM wish_photo_upload_receipt", Long.class),
				jdbc.queryForObject("SELECT count(*) FROM wish_photo_processing_attempt", Long.class),
				jdbc.queryForObject("SELECT count(*) FROM wish_photo_cleanup_work", Long.class));
	}

	private static byte[] jpeg(Color color) throws Exception { return jpeg(1080, color); }
	private ResultActions asOwnerPhoto(MockMultipartHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION,
				"Bearer " + SeedFixtureCatalog.OWNER_TOKEN));
	}
	private MvcResult concurrentUpload(CountDownLatch start, byte[] bytes) throws Exception {
		start.await();
		return asOwnerPhoto(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", "concurrent.jpg", "image/jpeg", bytes))
				.header("Idempotency-Key", "concurrent-photo-upload"))
				.andReturn();
	}
	private static byte[] jpeg(int size, Color color) throws Exception {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		var graphics = image.createGraphics(); graphics.setColor(color); graphics.fillRect(0, 0, size, size); graphics.dispose();
		ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "jpeg", output); return output.toByteArray();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class PhotoTestConfiguration {
		@Bean @Primary WishPhotoSafetyScanner allowAllPhotos() { return bytes -> true; }
		@Bean @Primary WishPhotoStorage inMemoryPhotoStorage() {
			return new WishPhotoStorage() {
				private final Map<String, Map<Variant, byte[]>> objects = new ConcurrentHashMap<>();
				@Override public void put(String prefix, Map<Variant, byte[]> variants) { objects.put(prefix, variants); }
				@Override public void delete(String prefix) { objects.remove(prefix); }
				@Override public WishPhotoView.Variants signedUrls(String prefix, Duration validity) {
					assertThat(objects).containsKey(prefix);
					return new WishPhotoView.Variants("https://private.test/" + prefix + "/small.jpg",
							"https://private.test/" + prefix + "/medium.jpg",
							"https://private.test/" + prefix + "/large.jpg");
				}
			};
		}
	}
}
