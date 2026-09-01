package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.e2e.SeedFixtureCatalog;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

@Import(WishPhotoApiIT.PhotoTestConfiguration.class)
@TestPropertySource(properties = {
	"crabit.wish-photo.enabled=true",
	"crabit.wish-photo.cleanup-delay-ms=3600000"
})
class WishPhotoMutationReplayIT extends WishApiIntegrationSupport {

	private static final String TRANSFERS =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/transfers";

	@Autowired
	private WishPhotoApiIT.BlockingWishPhotoStorage storage;

	@Test
	void depositReplayUsesTheCapturedPhotoAndKeepsItsReceiptAfterDeliveryFailure()
			throws Exception {
		String photoId = upload("deposit-photo", Color.ORANGE);
		String wishId = createWithPhoto("deposit-photo-wish", "Deposit Photo", 10_000, photoId);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		String request = "{\"amount\":1000,\"expectedVersion\":0}";
		String path = WISHES_PATH + "/" + wishId + "/deposits";

		asOwner(post(path)
				.header("Idempotency-Key", "deposit-photo-mutation")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.photo.id").value(photoId));

		storage.failSignedUrls(true);
		asOwner(post(path)
				.header("Idempotency-Key", "deposit-photo-mutation")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().doesNotExist("Idempotency-Replayed"))
				.andExpect(jsonPath("$.error.code").value("PHOTO_DELIVERY_UNAVAILABLE"))
				.andExpect(jsonPath("$.wish").doesNotExist());
		assertThat(ownerMutationReceipts()).contains("ACTIVE_PHOTO", photoId)
				.doesNotContain("PHOTO_REVOKED");

		storage.failSignedUrls(false);
		asOwner(post(path)
				.header("Idempotency-Key", "deposit-photo-mutation")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(jsonPath("$.wish.photo.id").value(photoId))
				.andExpect(jsonPath("$.wish.version").value(1));
	}

	@Test
	void transferReplayFailsAsAWholeWhenEitherCapturedPhotoIsRedacted() throws Exception {
		String sourcePhotoId = upload("transfer-source-photo", Color.BLUE);
		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("""
						{"expectedVersion":0,"photoId":"%s"}
						""".formatted(sourcePhotoId)))
				.andExpect(status().isOk());
		String destinationPhotoId = upload("transfer-destination-photo", Color.GREEN);
		String destinationWishId = createWithPhoto(
				"transfer-photo-wish", "Transfer Photo", 100_000, destinationPhotoId);
		String request = """
				{"sourceWishId":"%s","destinationWishId":"%s","amount":1000,
				"sourceExpectedVersion":1,"destinationExpectedVersion":0}
				""".formatted(LAPTOP_WISH_ID, destinationWishId);
		asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "transfer-photo-mutation")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceWish.photo.id").value(sourcePhotoId))
				.andExpect(jsonPath("$.destinationWish.photo.id").value(destinationPhotoId));

		String replacementId = upload("transfer-destination-replacement", Color.MAGENTA);
		asOwner(patch(WISHES_PATH + "/" + destinationWishId)
				.contentType("application/merge-patch+json")
				.content("""
						{"expectedVersion":1,"photoId":"%s"}
						""".formatted(replacementId)))
				.andExpect(status().isOk());
		storage.resetSignedUrlCalls();

		asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "transfer-photo-mutation")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isConflict())
				.andExpect(header().doesNotExist("Idempotency-Replayed"))
				.andExpect(jsonPath("$.error.code").value("WISH_PHOTO_EXPIRED"))
				.andExpect(jsonPath("$.sourceWish").doesNotExist())
				.andExpect(jsonPath("$.destinationWish").doesNotExist());
		assertThat(storage.signedUrlCalls()).isZero();
		String receipt = mutationReceipt("transfer-photo-mutation");
		assertThat(receipt).contains("ACTIVE_PHOTO", sourcePhotoId, "PHOTO_REVOKED")
				.doesNotContain(destinationPhotoId);
	}

	@Test
	void withdrawalCompletionAndAbandonmentReplayFailAfterTheirPhotoIsRedacted()
			throws Exception {
		String withdrawalPhotoId = upload("withdrawal-photo", Color.CYAN);
		asOwner(patch(WISHES_PATH + "/" + CAMP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("""
						{"expectedVersion":0,"photoId":"%s"}
						""".formatted(withdrawalPhotoId)))
				.andExpect(status().isOk());
		String withdrawalRequest = "{\"amount\":1,\"expectedVersion\":1}";
		String withdrawalPath = WISHES_PATH + "/" + CAMP_WISH_ID + "/withdrawals";
		asOwner(post(withdrawalPath)
				.header("Idempotency-Key", "withdrawal-photo-mutation")
				.contentType(MediaType.APPLICATION_JSON)
				.content(withdrawalRequest))
				.andExpect(status().isOk());
		asOwner(patch(WISHES_PATH + "/" + CAMP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":2,\"photoId\":null}"))
				.andExpect(status().isOk());
		assertExpiredReplay(post(withdrawalPath)
				.header("Idempotency-Key", "withdrawal-photo-mutation")
				.contentType(MediaType.APPLICATION_JSON)
				.content(withdrawalRequest));

		String completionPhotoId = upload("completion-photo", Color.PINK);
		String completionWishId = createWithPhoto(
				"completion-photo-wish", "Completion Photo", 1_000, completionPhotoId);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		asOwner(post(WISHES_PATH + "/" + completionWishId + "/deposits")
				.header("Idempotency-Key", "completion-funding")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":1000,\"expectedVersion\":0}"))
				.andExpect(status().isOk());
		String completionRequest = "{\"expectedVersion\":1}";
		String completionPath = WISHES_PATH + "/" + completionWishId + "/completion";
		asOwner(post(completionPath)
				.header("Idempotency-Key", "completion-photo-mutation")
				.contentType(MediaType.APPLICATION_JSON)
				.content(completionRequest))
				.andExpect(status().isOk());
		asOwner(delete(WISHES_PATH + "/" + completionWishId)
				.header(HttpHeaders.IF_MATCH, "2")
				.header("Idempotency-Key", "delete-completed-photo-wish"))
				.andExpect(status().isOk());
		assertExpiredReplay(post(completionPath)
				.header("Idempotency-Key", "completion-photo-mutation")
				.contentType(MediaType.APPLICATION_JSON)
				.content(completionRequest));

		String abandonmentPhotoId = upload("abandonment-photo", Color.YELLOW);
		String abandonmentWishId = createWithPhoto(
				"abandonment-photo-wish", "Abandonment Photo", 1_000, abandonmentPhotoId);
		String abandonmentRequest = "{\"expectedVersion\":0}";
		String abandonmentPath = WISHES_PATH + "/" + abandonmentWishId + "/abandonment";
		asOwner(post(abandonmentPath)
				.header("Idempotency-Key", "abandonment-photo-mutation")
				.contentType(MediaType.APPLICATION_JSON)
				.content(abandonmentRequest))
				.andExpect(status().isOk());
		asOwner(delete(WISHES_PATH + "/" + abandonmentWishId)
				.header(HttpHeaders.IF_MATCH, "1")
				.header("Idempotency-Key", "delete-abandoned-photo-wish"))
				.andExpect(status().isOk());
		assertExpiredReplay(post(abandonmentPath)
				.header("Idempotency-Key", "abandonment-photo-mutation")
				.contentType(MediaType.APPLICATION_JSON)
				.content(abandonmentRequest));
	}

	@Test
	void legacyPhotoLessReceiptIsRewrittenAsNoPhotoOnReplay() throws Exception {
		String request = "{\"purpose\":\"Legacy Replay\",\"targetAmount\":1000}";
		asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "legacy-photo-less")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isCreated());
		jdbc.update("""
				UPDATE student
				SET wish_idempotency_records = jsonb_set(
					wish_idempotency_records,
					ARRAY['legacy-photo-less'],
					(wish_idempotency_records -> 'legacy-photo-less') - 'photoReplayState',
					false)
				WHERE id = ?
				""", SeedFixtureCatalog.OWNER_ID);

		asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "legacy-photo-less")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(jsonPath("$.wish.photo").isEmpty());
		assertThat(mutationReceipt("legacy-photo-less")).contains("NO_PHOTO")
				.doesNotContain("ACTIVE_PHOTO", "PHOTO_REVOKED");
	}

	private String upload(String key, Color color) throws Exception {
		String body = mockMvc.perform(multipart("/v1/wish-photos")
				.file(new MockMultipartFile("photo", key + ".jpg", "image/jpeg", jpeg(color)))
				.header("Idempotency-Key", key)
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + SeedFixtureCatalog.OWNER_TOKEN))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return json(body, "$.id");
	}

	private String createWithPhoto(
			String key, String purpose, long targetAmount, String photoId) throws Exception {
		String body = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"purpose":"%s","targetAmount":%d,"photoId":"%s"}
						""".formatted(purpose, targetAmount, photoId)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return json(body, "$.wish.id");
	}

	private void assertExpiredReplay(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
			throws Exception {
		asOwner(request)
				.andExpect(status().isConflict())
				.andExpect(header().doesNotExist("Idempotency-Replayed"))
				.andExpect(jsonPath("$.error.code").value("WISH_PHOTO_EXPIRED"));
	}

	private String ownerMutationReceipts() {
		return jdbc.queryForObject(
				"SELECT wish_idempotency_records::text FROM student WHERE id = ?",
				String.class, SeedFixtureCatalog.OWNER_ID);
	}

	private String mutationReceipt(String key) {
		return jdbc.queryForObject(
				"SELECT (wish_idempotency_records -> ?)::text FROM student WHERE id = ?",
				String.class, key, SeedFixtureCatalog.OWNER_ID);
	}

	private static byte[] jpeg(Color color) throws Exception {
		BufferedImage image = new BufferedImage(1080, 1080, BufferedImage.TYPE_INT_RGB);
		var graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, 1080, 1080);
		graphics.dispose();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(image, "jpeg", output);
		return output.toByteArray();
	}
}
