package com.crabit.backend.wishphoto.googlecloud;

import com.crabit.backend.wishphoto.*;
import com.google.api.core.ApiClock;
import com.google.auth.ServiceAccountSigner;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class GoogleCloudWishPhotoStorage implements WishPhotoStorage {
	private static final Pattern PREFIX = Pattern.compile("wish-photos/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
	private final Storage storage;
	private final String bucket;
	private final String project;
	private final ServiceAccountSigner signer;
	private final Clock clock;
	public GoogleCloudWishPhotoStorage(Storage storage, String bucket, String project,
			ServiceAccountSigner signer, Clock clock) {
		this.storage = storage; this.bucket = bucket; this.project = project; this.signer = signer; this.clock = clock;
	}
	@Override public void put(String prefix, Map<Variant, byte[]> variants) {
		try {
			validatePrefix(prefix);
			if (variants == null || !variants.keySet().equals(Set.of(Variant.values()))
					|| variants.values().stream().anyMatch(bytes -> bytes == null || bytes.length == 0 || bytes.length > 5242880)) throw new IllegalArgumentException();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
			for (Variant variant : Variant.values()) {
				requireBudget(deadline);
				storage.create(blob(prefix, variant).setContentType("image/jpeg").setContentDisposition("inline")
						.setCacheControl("private, max-age=300, no-transform").build(), variants.get(variant), Storage.BlobTargetOption.doesNotExist());
			}
			requireBudget(deadline);
		} catch (RuntimeException exception) { throw processing(); }
	}
	@Override public void delete(String prefix) {
		try {
			validatePrefix(prefix);
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
			for (Variant variant : Variant.values()) {
				requireBudget(deadline); storage.delete(blob(prefix, variant).build().getBlobId());
			}
			requireBudget(deadline);
		} catch (RuntimeException exception) { throw processing(); }
	}
	@Override public WishPhotoView.Variants signedUrls(String prefix, Duration validity) {
		if (!Duration.ofSeconds(300).equals(validity)) throw delivery();
		return signedUrls(prefix, new SigningWindow(clock.instant()));
	}
	@Override public WishPhotoView.Variants signedUrls(String prefix, SigningWindow window) {
		try {
			validatePrefix(prefix);
			if (window.issuedAt().isAfter(clock.instant()) || !clock.instant().isBefore(window.expiresAt())) throw delivery();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
			// Only signature construction uses the fixed reference. Network/deadline clocks stay live.
			ApiClock signingClock = new ApiClock() {
				@Override public long millisTime() { return window.issuedAt().toEpochMilli(); }
				@Override public long nanoTime() { return System.nanoTime(); }
			};
			try (Storage signing = StorageOptions.newBuilder().setProjectId(project).setCredentials(NoCredentials.getInstance())
					.setClock(signingClock).build().getService()) {
			String[] urls = new String[3];
			for (Variant variant : Variant.values()) {
				requireBudget(deadline);
				urls[variant.ordinal()] = signing.signUrl(blob(prefix, variant).build(), 300, TimeUnit.SECONDS,
						Storage.SignUrlOption.withV4Signature(), Storage.SignUrlOption.httpMethod(HttpMethod.GET),
						Storage.SignUrlOption.signWith(signer)).toString();
			}
			requireBudget(deadline);
			if (!clock.instant().isBefore(window.expiresAt())) throw delivery();
			return new WishPhotoView.Variants(urls[0], urls[1], urls[2]);
			}
		} catch (Exception exception) { throw delivery(); }
	}
	private BlobInfo.Builder blob(String prefix, Variant variant) {
		return BlobInfo.newBuilder(bucket, prefix + "/" + variant.name().toLowerCase(java.util.Locale.ROOT) + ".jpg");
	}
	private static void validatePrefix(String prefix) {
		if (prefix == null || !PREFIX.matcher(prefix).matches()) throw new IllegalArgumentException();
	}
	private static void requireBudget(long deadline) {
		if (System.nanoTime() >= deadline) throw new IllegalStateException();
	}
	private static WishPhotoException processing() { return new WishPhotoException(WishPhotoException.Code.PHOTO_PROCESSING_UNAVAILABLE, "Wish photo storage is unavailable."); }
	private static WishPhotoException delivery() { return new WishPhotoException(WishPhotoException.Code.PHOTO_DELIVERY_UNAVAILABLE, "Wish photo delivery is unavailable."); }
}
