package com.crabit.backend.wishphoto;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public interface WishPhotoStorage {
	void put(String objectPrefix, Map<Variant, byte[]> variants);
	void delete(String objectPrefix);
	WishPhotoView.Variants signedUrls(String objectPrefix, Duration validity);
	default WishPhotoView.Variants signedUrls(String objectPrefix, SigningWindow window) {
		return signedUrls(objectPrefix, Duration.ofSeconds(300));
	}
	record SigningWindow(Instant issuedAt) {
		public SigningWindow { issuedAt = issuedAt.truncatedTo(ChronoUnit.SECONDS); }
		public Instant expiresAt() { return issuedAt.plusSeconds(300); }
	}
	enum Variant { SMALL, MEDIUM, LARGE }
}
