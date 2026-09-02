package com.crabit.backend.wishphoto.googlecloud;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.crabit.backend.wishphoto.*;
import com.google.auth.ServiceAccountSigner;
import com.google.cloud.storage.*;
import com.google.cloud.vision.v1.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GoogleCloudWishPhotoTest {
	private static final String PREFIX = "wish-photos/11111111-1111-4111-8111-111111111111/22222222-2222-4222-8222-222222222222";
	private static final GoogleCloudPhotoSettings CONFIG = new GoogleCloudPhotoSettings("staging", "project-9ee29576-dd79-4a1c-a70", "182907578804", "crabit-wish-photo-staging-182907578804", "crabit-staging-runtime@project-9ee29576-dd79-4a1c-a70.iam.gserviceaccount.com");
	@Test void safeSearchRequiresAllThreeKnownLikelihoodsAndRejectsOnlyLikelyOrAbove() {
		for (int field = 0; field < 3; field++) for (int likelihood = 0; likelihood <= 6; likelihood++) {
			var annotation = SafeSearchAnnotation.newBuilder().setAdultValue(1).setRacyValue(1).setViolenceValue(1)
					.setMedicalValue(5).setSpoofValue(5);
			if (field == 0) annotation.setAdultValue(likelihood);
			if (field == 1) annotation.setRacyValue(likelihood);
			if (field == 2) annotation.setViolenceValue(likelihood);
			var scanner = new GoogleCloudWishPhotoSafetyScanner(request -> {
				assertThat(request.getRequestsCount()).isOne();
				assertThat(request.getRequests(0).getFeaturesList()).extracting(Feature::getType).containsExactly(Feature.Type.SAFE_SEARCH_DETECTION);
				assertThat(request.getRequests(0).getImage().getContent().toByteArray()).containsExactly(1, 2);
				assertThat(request.getRequests(0).getImage().hasSource()).isFalse();
				return BatchAnnotateImagesResponse.newBuilder().addResponses(AnnotateImageResponse.newBuilder().setSafeSearchAnnotation(annotation)).build();
			});
			if (likelihood == 0 || likelihood == 6) assertThatThrownBy(() -> scanner.allowed(new byte[]{1, 2})).isInstanceOf(WishPhotoException.class);
			else assertThat(scanner.allowed(new byte[]{1, 2})).isEqualTo(likelihood < 4);
		}
	}
	@Test void missingErrorAndMalformedSafeSearchResultsFailClosedWithoutProviderDetails() {
		for (var response : List.of(BatchAnnotateImagesResponse.getDefaultInstance(),
				BatchAnnotateImagesResponse.newBuilder().addResponses(AnnotateImageResponse.getDefaultInstance()).build(),
				BatchAnnotateImagesResponse.newBuilder().addResponses(AnnotateImageResponse.newBuilder().setError(com.google.rpc.Status.newBuilder().setCode(13).setMessage("secret"))).build())) {
			assertThatThrownBy(() -> new GoogleCloudWishPhotoSafetyScanner(request -> response).allowed(new byte[]{1}))
					.isInstanceOf(WishPhotoException.class).hasMessage("Wish photo safety screening is unavailable.");
		}
		assertThatThrownBy(() -> new GoogleCloudWishPhotoSafetyScanner(request -> { throw new IllegalStateException("secret"); }).allowed(new byte[]{1}))
				.hasMessage("Wish photo safety screening is unavailable.").hasNoCause();
	}
	@Test void exactPrivateKeysMetadataAndPayloadAreWrittenAndInvalidInputNeverCallsStorage() {
		Storage provider = mock(Storage.class);
		var adapter = adapter(provider, Clock.systemUTC(), bytes -> new byte[]{1});
		var variants = Map.of(WishPhotoStorage.Variant.SMALL, new byte[]{1}, WishPhotoStorage.Variant.MEDIUM, new byte[]{2}, WishPhotoStorage.Variant.LARGE, new byte[]{3});
		adapter.put(PREFIX, variants);
		var info = ArgumentCaptor.forClass(BlobInfo.class);
		var payload = ArgumentCaptor.forClass(byte[].class);
		verify(provider, times(3)).create(info.capture(), payload.capture(), any(Storage.BlobTargetOption.class));
		assertThat(info.getAllValues()).extracting(BlobInfo::getName).containsExactly(PREFIX + "/small.jpg", PREFIX + "/medium.jpg", PREFIX + "/large.jpg");
		for (var blob : info.getAllValues()) {
			assertThat(blob.getBucket()).isEqualTo(CONFIG.bucket());
			assertThat(blob.getContentType()).isEqualTo("image/jpeg");
			assertThat(blob.getContentDisposition()).isEqualTo("inline");
			assertThat(blob.getCacheControl()).isEqualTo("private, max-age=300, no-transform");
			assertThat(blob.getAcl()).isNull();
		}
		assertThat(payload.getAllValues()).containsExactly(new byte[]{1}, new byte[]{2}, new byte[]{3});
		clearInvocations(provider);
		assertThatThrownBy(() -> adapter.put("../wrong", variants)).isInstanceOf(WishPhotoException.class);
		assertThatThrownBy(() -> adapter.put(PREFIX, Map.of())).isInstanceOf(WishPhotoException.class);
		verifyNoInteractions(provider);
	}
	@Test void failedWritesAndDeletesAreSanitizedWhileMissingDeletesSucceed() {
		Storage provider = mock(Storage.class);
		var adapter = adapter(provider, Clock.systemUTC(), bytes -> new byte[]{1});
		adapter.delete(PREFIX);
		verify(provider, times(3)).delete(any(BlobId.class));
		when(provider.delete(any(BlobId.class))).thenThrow(new IllegalStateException("private provider detail"));
		assertThatThrownBy(() -> adapter.delete(PREFIX)).hasMessage("Wish photo storage is unavailable.").hasNoCause();
		when(provider.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption.class))).thenThrow(new IllegalStateException("private bytes"));
		assertThatThrownBy(() -> adapter.put(PREFIX, Map.of(WishPhotoStorage.Variant.SMALL,new byte[]{1},WishPhotoStorage.Variant.MEDIUM,new byte[]{2},WishPhotoStorage.Variant.LARGE,new byte[]{3})))
				.hasMessage("Wish photo storage is unavailable.").hasNoCause();
	}
	@Test void allV4VariantsShareWholeSecondReferenceAndExactDeadlineDespiteSlowSigning() {
		MutableClock clock = new MutableClock(Instant.parse("2026-09-02T06:00:00.987Z"));
		var window = new WishPhotoStorage.SigningWindow(clock.instant());
		var storage = adapter(mock(Storage.class), clock, bytes -> { clock.now.updateAndGet(now -> now.plusSeconds(1)); return new byte[]{1,2,3}; });
		var urls = storage.signedUrls(PREFIX, window);
		for (String url : List.of(urls.small(), urls.medium(), urls.large())) {
			assertThat(url).contains("X-Goog-Date=20260902T060000Z", "X-Goog-Expires=300", "X-Goog-Signature=010203", "X-Goog-Algorithm=GOOG4-RSA-SHA256");
		}
		assertThat(window.expiresAt()).isEqualTo(Instant.parse("2026-09-02T06:05:00Z"));
	}
	@Test void signingAtExpiryOrSingleVariantFailureReturnsNoPartialDelivery() {
		MutableClock clock = new MutableClock(Instant.parse("2026-09-02T06:00:00Z"));
		var window = new WishPhotoStorage.SigningWindow(clock.instant());
		var expired = adapter(mock(Storage.class), clock, bytes -> { clock.now.set(window.expiresAt()); return new byte[]{1}; });
		assertThatThrownBy(() -> expired.signedUrls(PREFIX, window)).hasMessage("Wish photo delivery is unavailable.");
		clock.now.set(window.issuedAt());
		AtomicInteger calls = new AtomicInteger();
		var denied = adapter(mock(Storage.class), clock, bytes -> { if(calls.incrementAndGet()==2) throw new IllegalStateException("secret"); return new byte[]{1}; });
		assertThatThrownBy(() -> denied.signedUrls(PREFIX, window)).hasMessage("Wish photo delivery is unavailable.").hasNoCause();
	}
	@Test void metadataIdentityTokenAndShutdownAreBoundAndMalformedResponsesFailClosed() throws Exception {
		AtomicInteger closed = new AtomicInteger();
		GoogleMetadataCredentials.Reader reader = new GoogleMetadataCredentials.Reader() {
			public String read(String path) { return switch(path) {
				case "project/project-id" -> CONFIG.projectId(); case "project/numeric-project-id" -> CONFIG.projectNumber();
				case "instance/service-accounts/default/email" -> CONFIG.serviceAccount();
				default -> "{\"access_token\":\"synthetic-token\",\"expires_in\":300}"; }; }
			public void close() { closed.incrementAndGet(); }
		};
		try(var credentials = new GoogleMetadataCredentials(CONFIG, reader)) { assertThat(credentials.refreshAccessToken().getTokenValue()).isEqualTo("synthetic-token"); }
		assertThat(closed).hasValue(1);
		assertThatThrownBy(() -> new GoogleMetadataCredentials(CONFIG, path -> "wrong identity"))
				.hasMessage("Wish photo attached identity unavailable").hasNoCause();
		assertThatThrownBy(() -> new GoogleCloudPhotoSettings("stable-demo",CONFIG.projectId(),CONFIG.projectNumber(),CONFIG.bucket(),CONFIG.serviceAccount()))
				.hasMessage("Invalid Wish photo Google Cloud environment binding");
	}
	@Test void realManagedClientsConstructAndCloseWithMetadataOnlyOfflineCredentials() throws Exception {
		AtomicInteger closed = new AtomicInteger();
		GoogleMetadataCredentials.Reader reader = new GoogleMetadataCredentials.Reader() {
			public String read(String path) { return switch (path) {
				case "project/project-id" -> CONFIG.projectId();
				case "project/numeric-project-id" -> CONFIG.projectNumber();
				case "instance/service-accounts/default/email" -> CONFIG.serviceAccount();
				default -> throw new AssertionError("Client initialization must not fetch tokens or call providers");
			}; }
			public void close() { closed.incrementAndGet(); }
		};
		try (var runtime = new GoogleCloudPhotoRuntime(CONFIG, new WishPhotoClock(Clock.systemUTC()),
				() -> new GoogleMetadataCredentials(CONFIG, reader))) {
			assertThat(runtime.storage()).isNotNull();
			assertThat(runtime.safety()).isNotNull();
		}
		assertThat(closed).hasValue(1);
	}
	private static GoogleCloudWishPhotoStorage adapter(Storage storage, Clock clock, java.util.function.Function<byte[],byte[]> sign) {
		return new GoogleCloudWishPhotoStorage(storage,CONFIG.bucket(),CONFIG.projectId(),new ServiceAccountSigner() {
			public String getAccount() { return CONFIG.serviceAccount(); }
			public byte[] sign(byte[] bytes) { return sign.apply(bytes); }
		},clock);
	}
	private static final class MutableClock extends Clock {
		final AtomicReference<Instant> now;
		MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
		public ZoneId getZone() { return ZoneOffset.UTC; }
		public Clock withZone(ZoneId zone) { return this; }
		public Instant instant() { return now.get(); }
	}
}
