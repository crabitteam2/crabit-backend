package com.crabit.backend.wishphoto;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class WishPhotoSigningCacheTest {
	private static final Instant START = Instant.parse("2026-09-18T06:00:00Z");

	@Test void preservesWholeSecondExpiryAndIncludesExactThirtySecondBoundary() {
		var fixture = new Fixture(2, 2);
		UUID id = UUID.randomUUID();
		fixture.now.set(START.plusNanos(999_000_000));
		var first = fixture.cache.view(id, "prefix");
		assertThat(first.expiresAt()).isEqualTo(START.plusSeconds(300));
		fixture.now.set(START.plusSeconds(269));
		assertThat(fixture.cache.view(id, "prefix")).isEqualTo(first);
		fixture.now.set(START.plusSeconds(270));
		assertThat(fixture.cache.view(id, "prefix")).isEqualTo(first);
		fixture.now.set(START.plusSeconds(270).plusNanos(1));
		assertThat(fixture.cache.view(id, "prefix").expiresAt()).isEqualTo(START.plusSeconds(570));
		assertThat(fixture.calls).hasValue(2);
	}

	@Test void identityPrefixAndStorageInstanceAreAllIsolated() {
		var fixture = new Fixture(5, 2);
		UUID id = UUID.randomUUID();
		fixture.cache.view(id, "one");
		fixture.cache.view(id, "two");
		fixture.cache.view(UUID.randomUUID(), "one");
		new WishPhotoSigningCache(fixture.storage, fixture.clock).view(id, "one");
		assertThat(fixture.calls).hasValue(4);
	}

	@Test void completedEntriesHaveBoundedLruCapacity() {
		var f = new Fixture(2, 2);
		UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
		f.cache.view(a, "a"); f.cache.view(b, "b"); f.cache.view(a, "a"); f.cache.view(c, "c");
		f.cache.view(a, "a");
		assertThat(f.calls).hasValue(3);
		f.cache.view(b, "b");
		assertThat(f.calls).hasValue(4);
	}

	@Test void partialFailureAndInsufficientLifetimeAreNeverCachedAndCanRecover() {
		var f = new Fixture(2, 2);
		UUID id = UUID.randomUUID();
		f.sign = (prefix, window) -> new WishPhotoView.Variants("one", null, "three");
		assertUnavailable(() -> f.cache.view(id, "a"));
		f.sign = (prefix, window) -> { throw new IllegalStateException("private provider details"); };
		assertUnavailable(() -> f.cache.view(id, "a"));
		f.sign = (prefix, window) -> { f.now.set(window.expiresAt().minusSeconds(29)); return urls(prefix); };
		assertUnavailable(() -> f.cache.view(id, "a"));
		f.sign = (prefix, window) -> urls(prefix);
		var recovered = f.cache.view(id, "a");
		assertThat(f.cache.view(id, "a")).isEqualTo(recovered);
		assertThat(f.calls).hasValue(4);
	}

	@Test void expiredRequestCannotUseWarmCache() {
		var f = new Fixture(2, 2);
		UUID id = UUID.randomUUID();
		f.cache.view(id, "a");
		assertUnavailable(() -> f.cache.view(id, "a", () -> 0));
		assertThat(f.calls).hasValue(1);
	}

	@Test void warmCacheWorksDuringSignerOutageButExpiredCacheFails() {
		var f = new Fixture(2, 2);
		UUID id = UUID.randomUUID();
		var first = f.cache.view(id, "a");
		f.sign = (prefix, window) -> { throw new IllegalStateException(); };
		assertThat(f.cache.view(id, "a")).isEqualTo(first);
		assertThat(f.calls).hasValue(1);
		f.now.set(START.plusSeconds(271));
		assertUnavailable(() -> f.cache.view(id, "a"));
	}

	@Test void identicalRequestsShareWorkWhileDifferentPhotosProceedAndSaturationFailsFast() throws Exception {
		var f = new Fixture(2, 1);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		f.sign = (prefix, window) -> { entered.countDown(); await(release); return urls(prefix); };
		UUID a = UUID.randomUUID();
		try (var pool = Executors.newFixedThreadPool(3)) {
			Future<WishPhotoView> producer = pool.submit(() -> f.cache.view(a, "a"));
			assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
			assertUnavailable(() -> f.cache.view(UUID.randomUUID(), "b"));
			CountDownLatch waiting = new CountDownLatch(1);
			Future<WishPhotoView> waiter = pool.submit(() -> f.cache.view(a, "a", () -> { waiting.countDown(); return TimeUnit.SECONDS.toNanos(3); }));
			assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
			release.countDown();
			assertThat(waiter.get(3, TimeUnit.SECONDS)).isEqualTo(producer.get(3, TimeUnit.SECONDS));
			assertThat(f.calls).hasValue(1);
			f.cache.view(UUID.randomUUID(), "b");
			assertThat(f.calls).hasValue(2);
		} finally { release.countDown(); }
	}

	@Test void unrelatedPhotoSigningRunsInParallel() throws Exception {
		var f = new Fixture(2, 2);
		CountDownLatch both = new CountDownLatch(2), release = new CountDownLatch(1);
		f.sign = (prefix, window) -> { both.countDown(); await(release); return urls(prefix); };
		try (var pool = Executors.newFixedThreadPool(2)) {
			var one = pool.submit(() -> f.cache.view(UUID.randomUUID(), "a"));
			var two = pool.submit(() -> f.cache.view(UUID.randomUUID(), "b"));
			try { assertThat(both.await(2, TimeUnit.SECONDS)).isTrue(); } finally { release.countDown(); }
			one.get(3, TimeUnit.SECONDS); two.get(3, TimeUnit.SECONDS);
			assertThat(f.calls).hasValue(2);
		} finally { release.countDown(); }
	}

	@Test void timedOutWaiterDoesNotCancelProducerOrAnotherWaiter() throws Exception {
		var f = new Fixture(2, 2);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		f.sign = (prefix, window) -> { entered.countDown(); await(release); return urls(prefix); };
		UUID id = UUID.randomUUID();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var producer = pool.submit(() -> f.cache.view(id, "a"));
			assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
			assertUnavailable(() -> f.cache.view(id, "a", () -> TimeUnit.MILLISECONDS.toNanos(1)));
			release.countDown();
			var result = producer.get(3, TimeUnit.SECONDS);
			assertThat(f.cache.view(id, "a")).isEqualTo(result);
			assertThat(f.calls).hasValue(1);
		} finally { release.countDown(); }
	}

	@Test void waiterRechecksExpiryAfterSharedWorkCompletes() throws Exception {
		var f = new Fixture(2, 2);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), waiting = new CountDownLatch(1);
		f.sign = (prefix, window) -> { entered.countDown(); await(release); return urls(prefix); };
		UUID id = UUID.randomUUID();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var producer = pool.submit(() -> f.cache.view(id, "a"));
			assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
			CountDownLatch producerFinished = new CountDownLatch(1);
			var waiter = pool.submit(() -> f.cache.view(id, "a", () -> {
				waiting.countDown();
				await(producerFinished);
				f.now.set(START.plusSeconds(271));
				return TimeUnit.SECONDS.toNanos(2);
			}));
			assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
			release.countDown();
			producer.get(3, TimeUnit.SECONDS);
			producerFinished.countDown();
			assertThatThrownBy(() -> waiter.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(WishPhotoException.class);
		} finally { release.countDown(); }
	}

	private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
		assertThatThrownBy(operation).isInstanceOfSatisfying(WishPhotoException.class,
				e -> assertThat(e.code()).isEqualTo(WishPhotoException.Code.PHOTO_DELIVERY_UNAVAILABLE))
				.hasMessage("Wish photo delivery is unavailable.").hasNoCause();
	}
	private static void await(CountDownLatch latch) {
		try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
	}
	private static WishPhotoView.Variants urls(String prefix) { return new WishPhotoView.Variants(prefix + "/s", prefix + "/m", prefix + "/l"); }
	private static class Fixture {
		final AtomicReference<Instant> now = new AtomicReference<>(START);
		final AtomicInteger calls = new AtomicInteger();
		volatile BiFunction<String, WishPhotoStorage.SigningWindow, WishPhotoView.Variants> sign = (prefix, window) -> urls(prefix);
		final Clock clock = new Clock() {
			public ZoneId getZone() { return ZoneOffset.UTC; }
			public Clock withZone(ZoneId zone) { return this; }
			public Instant instant() { return now.get(); }
		};
		final WishPhotoStorage storage = new WishPhotoStorage() {
			public void put(String p, Map<Variant, byte[]> v) {}
			public void delete(String p) {}
			public WishPhotoView.Variants signedUrls(String p, Duration d) { throw new UnsupportedOperationException(); }
			public WishPhotoView.Variants signedUrls(String p, SigningWindow window) { calls.incrementAndGet(); return sign.apply(p, window); }
		};
		final WishPhotoSigningCache cache;
		Fixture(int capacity, int inFlight) { cache = new WishPhotoSigningCache(storage, clock, capacity, inFlight); }
	}
}
