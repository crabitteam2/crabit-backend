package com.crabit.backend.wishphoto;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.crabit.backend.api.WishApiExceptionHandler;
import com.crabit.backend.relationship.RelationshipContextAuthorizationService;
import com.crabit.backend.wish.*;
import com.crabit.backend.wishphoto.googlecloud.*;
import com.google.auth.ServiceAccountSigner;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class WishPhotoRequestBudgetTest {
	@Test void mvcRequestReturnsOnlySanitizedRetryableFailureWhenTheMultiPhotoBudgetIsExhausted() throws Exception {
		Fixture fixture = new Fixture(Duration.ofMillis(1800));
		var mvc = MockMvcBuilders.standaloneSetup(new DeliveryController(fixture))
				.setControllerAdvice(new WishApiExceptionHandler()).addFilters(fixture.filter).build();
		mvc.perform(get("/v1/shared-cards"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.error.code").value("PHOTO_DELIVERY_UNAVAILABLE"))
				.andExpect(jsonPath("$.error.message").value("Wish photo delivery is unavailable."))
				.andExpect(jsonPath("$.error.retryable").value(true))
				.andExpect(jsonPath("$.items").doesNotExist());
		assertThat(fixture.signatures).hasValue(4);
		assertThat(Duration.ofNanos(fixture.nanos.get())).isEqualTo(Duration.ofMillis(7200));
	}

	@Test void multiPhotoSharedCardListFailsBeforeOrdinaryDeadlineEvenWhenEachSignatureFitsItsRpcTimeout() throws Exception {
		Fixture fixture = new Fixture(Duration.ofMillis(1800));
		assertThatThrownBy(() -> fixture.request("GET", "/v1/shared-cards", Duration.ZERO))
				.isInstanceOfSatisfying(WishPhotoException.class, exception ->
						assertThat(exception.code()).isEqualTo(WishPhotoException.Code.PHOTO_DELIVERY_UNAVAILABLE))
				.hasMessage("Wish photo delivery is unavailable.").hasNoCause();
		assertThat(fixture.signatures).hasValue(4);
		assertThat(Duration.ofNanos(fixture.nanos.get())).isEqualTo(Duration.ofMillis(7200));
	}

	@Test void successfulMultiPhotoListKeepsEachOriginalFiveMinuteCapabilityWindow() throws Exception {
		Fixture fixture = new Fixture(Duration.ofSeconds(1));
		var page = fixture.request("GET", "/v1/shared-cards", Duration.ZERO);
		assertThat(page.items()).hasSize(2);
		assertThat(fixture.signatures).hasValue(6);
		for (int index = 0; index < 2; index++) {
			var photo = ((SharedCardProjection.Progress) page.items().get(index)).photo();
			assertThat(photo.expiresAt()).isEqualTo(Fixture.START.plusSeconds(300 + index * 3));
			String issued = index == 0 ? "20260902T060000Z" : "20260902T060003Z";
			for (String url : List.of(photo.variants().small(), photo.variants().medium(), photo.variants().large())) {
				assertThat(url).contains("X-Goog-Date=" + issued, "X-Goog-Expires=300");
			}
		}
	}

	@Test void expiredRequestDoesNotLeakIntoTheNextRequestOnTheSameThread() throws Exception {
		Fixture fixture = new Fixture(Duration.ofSeconds(1));
		assertThatThrownBy(() -> fixture.request("GET", "/v1/shared-cards", Duration.ofSeconds(7)))
				.isInstanceOf(WishPhotoException.class);
		assertThat(fixture.signatures).hasValue(0);
		assertThat(fixture.request("GET", "/v1/shared-cards", Duration.ZERO).items()).hasSize(2);
		assertThat(fixture.signatures).hasValue(6);
		assertThat(RequestContextHolder.getRequestAttributes()).isNull();
	}

	@Test void uploadKeepsItsLongerBudgetButOrdinaryMutationDoesNotReceiveIt() throws Exception {
		Fixture upload = new Fixture(Duration.ofSeconds(1));
		assertThat(upload.request("POST", "/v1/wish-photos", Duration.ofSeconds(20)).items()).hasSize(2);
		assertThat(Duration.ofNanos(upload.nanos.get())).isEqualTo(Duration.ofSeconds(26));
		Fixture ordinary = new Fixture(Duration.ofSeconds(1));
		assertThatThrownBy(() -> ordinary.request("POST", "/v1/wishes", Duration.ofSeconds(7)))
				.isInstanceOf(WishPhotoException.class);
		assertThat(ordinary.signatures).hasValue(0);
	}

	@RestController
	static final class DeliveryController {
		private final Fixture fixture;
		DeliveryController(Fixture fixture) { this.fixture = fixture; }
		@GetMapping("/v1/shared-cards")
		SharedCardQueryService.SharedCardPage list() {
			return fixture.service.list(fixture.viewer, fixture.academy, null, 2);
		}
	}

	private static final class Fixture {
		static final Instant START = Instant.parse("2026-09-02T06:00:00Z");
		final AtomicLong nanos = new AtomicLong();
		final AtomicInteger signatures = new AtomicInteger();
		final UUID viewer = UUID.randomUUID(), academy = UUID.randomUUID();
		final SharedCardQueryService service;
		final GoogleCloudPhotoRequestBudget filter = new GoogleCloudPhotoRequestBudget(nanos::get);
		Fixture(Duration signatureTime) {
			Clock clock = new Clock() {
				public ZoneId getZone() { return ZoneOffset.UTC; }
				public Clock withZone(ZoneId zone) { return this; }
				public Instant instant() { return START.plusNanos(nanos.get()); }
			};
			UUID owner = UUID.randomUUID();
			WishPhotoRepository repository = mock(WishPhotoRepository.class);
			List<SharedCardQueryRepository.Row> rows = new ArrayList<>();
			for (int index = 0; index < 2; index++) {
				UUID wish = UUID.randomUUID();
				WishPhoto photo = WishPhoto.pending(owner, "a".repeat(64), START);
				photo.attach(wish, START);
				when(repository.findByAttachedWishIdAndState(wish, WishPhotoState.ATTACHED)).thenReturn(Optional.of(photo));
				rows.add(new SharedCardQueryRepository.Row(UUID.randomUUID(), SharedCardKind.PROGRESS,
						owner, "student", 15, UUID.randomUUID(), academy, START, null, wish, "wish",
						1000, 100, WishState.IN_PROGRESS, null, null, START, null, null, START, false));
			}
			var storage = new GoogleCloudWishPhotoStorage(null, "private-bucket", "offline-project", new ServiceAccountSigner() {
				public String getAccount() { return "runtime@example.test"; }
				public byte[] sign(byte[] bytes) {
					signatures.incrementAndGet();
					nanos.addAndGet(signatureTime.toNanos());
					return new byte[]{1, 2, 3};
				}
			}, clock);
			var photos = new WishPhotoService(repository, null, null, null, null, storage, null,
					new WishPhotoClock(clock), new DataSourceTransactionManager(), true);
			var authorization = mock(RelationshipContextAuthorizationService.class);
			when(authorization.canAccessAcademy(viewer, academy)).thenReturn(true);
			var queries = mock(SharedCardQueryRepository.class);
			when(queries.findVisiblePage(viewer, academy, null, null, 3)).thenReturn(rows);
			service = new SharedCardQueryService(authorization, queries, Optional.of(photos), mock(com.crabit.backend.wish.SharedCardCursor.class));
		}
		SharedCardQueryService.SharedCardPage request(String method, String path, Duration beforeDelivery) throws Exception {
			var request = new MockHttpServletRequest(method, path);
			var attributes = new ServletRequestAttributes(request);
			AtomicReference<SharedCardQueryService.SharedCardPage> result = new AtomicReference<>();
			try {
				filter.doFilter(request, new MockHttpServletResponse(), (incoming, response) -> {
					RequestContextHolder.setRequestAttributes(attributes);
					nanos.addAndGet(beforeDelivery.toNanos());
					result.set(service.list(viewer, academy, null, 2));
				});
				return result.get();
			} finally {
				RequestContextHolder.resetRequestAttributes();
				attributes.requestCompleted();
			}
		}
	}
}
