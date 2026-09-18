package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.crabit.backend.wishphoto.WishPhotoService;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@Import(WishPhotoApiIT.PhotoTestConfiguration.class)
@TestPropertySource(properties = "crabit.wish-photo.enabled=true")
class FeedPhotoSigningCacheIT extends SharedCardApiIntegrationSupport {
	private static final String FEED = "/v1/academies/" + PRIMARY_ACADEMY_ID + "/feed-results";
	@Autowired WishPhotoService photos;
	@Autowired WishPhotoApiIT.BlockingWishPhotoStorage storage;

	@BeforeEach void prepare() {
		storage.failSignedUrls(false);
		storage.resetSignedUrlCalls();
		jdbc.update("INSERT INTO card_balance_account (id,student_id,academy_id,opened_at,balance_lookup_version,version) VALUES (?,?,?,?::timestamptz,0,0)",
				UUID.randomUUID(), FRIEND_ID, PRIMARY_ACADEMY_ID, "2026-08-01T00:00:00Z");
	}

	@Test void feedAndDetailReuseUploadCapabilityAndPreserveExpiryDuringSignerOutage() throws Exception {
		var photo = attach();
		assertThat(storage.signedUrlCalls()).isOne();
		storage.failSignedUrls(true);
		try {
			clock.set(COMMAND_TIME.plusSeconds(60));
			String first = feed();
			String second = feed();
			assertThat(photoIds(first)).contains(photo.id().toString());
			assertThat(photoIds(second)).contains(photo.id().toString());
			getAs(FRIEND_TOKEN, cardIdForWish(LAPTOP_WISH_ID)).andExpect(status().isOk())
					.andExpect(header().string("Cache-Control", "no-store"))
					.andExpect(jsonPath("$.photo.expiresAt").value(photo.expiresAt().toString()))
					.andExpect(jsonPath("$.photo.variants.small").value(photo.variants().small()));
			assertThat(storage.signedUrlCalls()).isOne();
			clock.set(COMMAND_TIME.plusSeconds(271));
			asToken(FRIEND_TOKEN, post(FEED).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.error.code").value("PHOTO_DELIVERY_UNAVAILABLE"))
					.andExpect(jsonPath("$.items").doesNotExist());
		} finally { storage.failSignedUrls(false); }
	}

	@Test void cursorReplayReusesCapabilitiesButRechecksVisibilityAndCreatesItsOwnResultContext() throws Exception {
		attach();
		var camp = photos.upload(OWNER_ID, "camp-photo", jpeg(), "image/jpeg").photo();
		asOwner(patch(WISHES_PATH + "/" + CAMP_WISH_ID).contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":0,\"photoId\":\"" + camp.id() + "\"}"))
				.andExpect(status().isOk());
		String first = feedPage(null);
		String cursor = json(first, "$.nextCursor");
		assertThat(cursor).isNotBlank();
		String second = feedPage(cursor);
		String replay = feedPage(cursor);
		assertThat(photoIds(second)).hasSize(1).isEqualTo(photoIds(replay));
		assertThat((String) json(second, "$.resultContextId")).isNotEqualTo((String) json(replay, "$.resultContextId"));
		assertThat(storage.signedUrlCalls()).isEqualTo(2);
		for (UUID wish : List.of(LAPTOP_WISH_ID, CAMP_WISH_ID)) {
			asOwner(patch(WISHES_PATH + "/" + wish).contentType("application/merge-patch+json")
					.content("{\"expectedVersion\":1,\"visibility\":\"PRIVATE\"}"))
					.andExpect(status().isOk());
		}
		assertThat(photoIds(feedPage(cursor))).isEmpty();
		assertThat(storage.signedUrlCalls()).isEqualTo(2);
	}

	private String feedPage(String cursor) throws Exception {
		String request = cursor == null ? "{\"limit\":1}" : "{\"limit\":1,\"cursor\":\"" + cursor + "\"}";
		return asToken(FRIEND_TOKEN, post(FEED).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	@ParameterizedTest
	@ValueSource(strings = {"private", "deleted", "closed", "owner-left", "viewer-left", "unfollow", "outgoing-block", "incoming-block"})
	void warmCacheNeverBypassesCurrentPrivacy(String revocation) throws Exception {
		var photo = attach();
		assertThat(photoIds(feed())).contains(photo.id().toString());
		String card = cardIdForWish(LAPTOP_WISH_ID);
		switch (revocation) {
			case "private" -> asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID).contentType("application/merge-patch+json")
					.content("{\"expectedVersion\":1,\"visibility\":\"PRIVATE\"}")).andExpect(status().isOk());
			case "deleted" -> asOwner(delete(WISHES_PATH + "/" + LAPTOP_WISH_ID).header("If-Match", "1")
					.header("Idempotency-Key", "delete-photo-wish")).andExpect(status().isOk());
			case "closed" -> jdbc.update("UPDATE card_balance_account SET closed_at = now() WHERE id = ?", OWNER_ACCOUNT_ID);
			case "owner-left", "viewer-left" -> jdbc.update("UPDATE academy_membership SET left_at = now() WHERE student_id = ?", revocation.equals("owner-left") ? OWNER_ID : FRIEND_ID);
			case "unfollow" -> jdbc.update("UPDATE student_follow SET ended_at = now() WHERE source_id = ? AND target_id = ?", FRIEND_ID, OWNER_ID);
			default -> jdbc.update("INSERT INTO student_block(id,blocker_id,blocked_id,blocked_at) VALUES (?,?,?,now())", UUID.randomUUID(), revocation.equals("outgoing-block") ? FRIEND_ID : OWNER_ID, revocation.equals("outgoing-block") ? OWNER_ID : FRIEND_ID);
		}
		getAs(FRIEND_TOKEN, card).andExpect(status().isNotFound());
		if (revocation.equals("viewer-left")) {
			asToken(FRIEND_TOKEN, post(FEED).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
					.andExpect(status().isNotFound());
		} else assertThat(photoIds(feed())).doesNotContain(photo.id().toString());
		assertThat(storage.signedUrlCalls()).isOne();
	}

	@Test void replacedAndDetachedPhotosDoNotReappearFromWarmCache() throws Exception {
		var old = attach();
		var replacement = photos.upload(OWNER_ID, "replacement", jpeg(), "image/jpeg").photo();
		clock.set(COMMAND_TIME.plusSeconds(1));
		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID).contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":1,\"photoId\":\"" + replacement.id() + "\"}"))
				.andExpect(status().isOk());
		assertThat(photoIds(feed())).contains(replacement.id().toString()).doesNotContain(old.id().toString());
		clock.set(COMMAND_TIME.plusSeconds(2));
		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID).contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":2,\"photoId\":null}")).andExpect(status().isOk());
		assertThat(photoIds(feed())).doesNotContain(old.id().toString(), replacement.id().toString());
		assertThat(storage.signedUrlCalls()).isEqualTo(2);
	}

	@Test void uploadReplayWithMismatchedPhotoOwnerFailsWithoutChangingAnyReceipt() throws Exception {
		byte[] source = jpeg();
		var first = photos.upload(OWNER_ID, "wrong-owner", source, "image/jpeg");
		jdbc.update("UPDATE wish_photo SET owner_student_id = ? WHERE id = ?", FRIEND_ID, first.photo().id());
		String before = jdbc.queryForObject("SELECT outcome::text FROM wish_photo_upload_receipt WHERE idempotency_key = 'wrong-owner'", String.class);
		assertThatThrownBy(() -> photos.upload(OWNER_ID, "wrong-owner", source, "image/jpeg"))
				.isInstanceOf(com.crabit.backend.wishphoto.WishPhotoException.class);
		assertThat(jdbc.queryForObject("SELECT outcome::text FROM wish_photo_upload_receipt WHERE idempotency_key = 'wrong-owner'", String.class)).isEqualTo(before);
		assertThat(storage.signedUrlCalls()).isOne();
	}

	private com.crabit.backend.wishphoto.WishPhotoView attach() throws Exception {
		var photo = photos.upload(OWNER_ID, "feed-photo", jpeg(), "image/jpeg").photo();
		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID).contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":0,\"photoId\":\"" + photo.id() + "\"}"))
				.andExpect(status().isOk());
		return photo;
	}
	private String feed() throws Exception {
		return asToken(FRIEND_TOKEN, post(FEED).contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
				.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
				.andReturn().getResponse().getContentAsString();
	}
	private List<String> photoIds(String body) { return JsonPath.read(body, "$.items[?(@.photo != null)].photo.id"); }
	private byte[] jpeg() throws Exception {
		var image = new BufferedImage(1080, 1080, BufferedImage.TYPE_INT_RGB);
		var output = new ByteArrayOutputStream(); ImageIO.write(image, "jpeg", output); return output.toByteArray();
	}
}
