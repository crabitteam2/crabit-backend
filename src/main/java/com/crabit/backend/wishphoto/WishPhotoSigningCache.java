package com.crabit.backend.wishphoto;

import com.crabit.backend.wishphoto.googlecloud.GoogleCloudPhotoRequestBudget;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Stores delivery capabilities only. Callers must revalidate current access and photo state. */
final class WishPhotoSigningCache {
	private static final Duration REUSE_MARGIN = Duration.ofSeconds(30);
	private final WishPhotoStorage storage;
	private final Clock clock;
	private final int capacity;
	private final int maxInFlight;
	private final Map<Key, WishPhotoView> completed = new LinkedHashMap<>(16, 0.75f, true);
	private final Map<Key, CompletableFuture<WishPhotoView>> inFlight = new HashMap<>();

	WishPhotoSigningCache(WishPhotoStorage storage, Clock clock) { this(storage, clock, 1024, 128); }
	WishPhotoSigningCache(WishPhotoStorage storage, Clock clock, int capacity, int maxInFlight) {
		if (capacity < 1 || maxInFlight < 1) throw new IllegalArgumentException();
		this.storage = storage;
		this.clock = clock;
		this.capacity = capacity;
		this.maxInFlight = maxInFlight;
	}

	WishPhotoView view(UUID photoId, String objectPrefix) {
		return view(photoId, objectPrefix, GoogleCloudPhotoRequestBudget.signingRemainingNanos());
	}

	WishPhotoView view(UUID photoId, String objectPrefix, LongSupplier remainingNanos) {
		Key key = new Key(photoId, objectPrefix);
		CompletableFuture<WishPhotoView> pending;
		boolean owner;
		synchronized (this) {
			WishPhotoView cached = completed.get(key);
			if (cached != null && reusable(cached)) {
				if (remainingNanos.getAsLong() <= 0) throw unavailable();
				return cached;
			}
			completed.remove(key);
			pending = inFlight.get(key);
			owner = pending == null;
			if (owner) {
				if (inFlight.size() >= maxInFlight) throw unavailable();
				pending = new CompletableFuture<>();
				inFlight.put(key, pending);
			}
		}
		if (owner) {
			try {
				if (remainingNanos.getAsLong() <= 0) throw unavailable();
				var window = new WishPhotoStorage.SigningWindow(clock.instant());
				var variants = storage.signedUrls(objectPrefix, window);
				WishPhotoView result = new WishPhotoView(photoId, variants, window.expiresAt());
				if (!reusable(result) || remainingNanos.getAsLong() <= 0) throw unavailable();
				synchronized (this) {
					completed.put(key, result);
					while (completed.size() > capacity) completed.remove(completed.keySet().iterator().next());
				}
				pending.complete(result);
			} catch (RuntimeException failure) {
				pending.completeExceptionally(failure);
			} catch (Error failure) {
				pending.completeExceptionally(failure);
				throw failure;
			} finally {
				synchronized (this) { inFlight.remove(key, pending); }
			}
		}
		try {
			long budget = remainingNanos.getAsLong();
			if (budget <= 0) throw unavailable();
			WishPhotoView result = pending.get(budget, TimeUnit.NANOSECONDS);
			// Waiting can outlive the reuse margin, even when the producer checked it.
			if (!reusable(result)) {
				synchronized (this) { completed.remove(key, result); }
				throw unavailable();
			}
			if (remainingNanos.getAsLong() <= 0) throw unavailable();
			return result;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw unavailable();
		} catch (ExecutionException | TimeoutException exception) {
			// Never cancel a producer because an individual waiter exhausted its budget.
			throw unavailable();
		}
	}

	private boolean reusable(WishPhotoView value) {
		var variants = value.variants();
		return variants != null && variants.small() != null && !variants.small().isBlank()
				&& variants.medium() != null && !variants.medium().isBlank()
				&& variants.large() != null && !variants.large().isBlank()
				&& !clock.instant().plus(REUSE_MARGIN).isAfter(value.expiresAt());
	}

	private static WishPhotoException unavailable() {
		return new WishPhotoException(WishPhotoException.Code.PHOTO_DELIVERY_UNAVAILABLE,
				"Wish photo delivery is unavailable.");
	}
	private record Key(UUID photoId, String objectPrefix) {}
}
