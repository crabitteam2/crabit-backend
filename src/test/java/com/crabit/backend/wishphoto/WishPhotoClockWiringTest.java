package com.crabit.backend.wishphoto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.crabit.backend.wishphoto.googlecloud.WishPhotoClock;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class WishPhotoClockWiringTest {
	@Test void enabledE2eAdvancesPhotoTimeWhileDomainTimeRemainsFixedAndFakesAvoidMetadata() throws Exception {
		Clock fixture = Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
		try (var context = context(fixture, true)) {
			assertThat(context.getBean(Clock.class)).isSameAs(fixture);
			Clock photo = context.getBean(WishPhotoClock.class).value();
			assertThat(photo).isNotSameAs(fixture);
			Instant before = photo.instant();
			Thread.sleep(5);
			assertThat(photo.instant()).isAfter(before);
			assertThat(context.getBean(Clock.class).instant()).isEqualTo(fixture.instant());
		}
	}
	@Test void disabledPhotoClockUsesDeterministicDomainClockWithoutCloudConfiguration() {
		Clock fixture = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
		try (var context = context(fixture, false)) {
			assertThat(context.getBean(WishPhotoClock.class).value()).isSameAs(fixture);
		}
	}
	private static AnnotationConfigApplicationContext context(Clock fixture, boolean enabled) {
		var context = new AnnotationConfigApplicationContext();
		context.getEnvironment().setActiveProfiles("e2e");
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("photo-test", Map.of("crabit.wish-photo.enabled", enabled)));
		context.registerBean(Clock.class, () -> fixture);
		context.registerBean(WishPhotoStorage.class, () -> mock(WishPhotoStorage.class));
		context.registerBean(WishPhotoSafetyScanner.class, () -> bytes -> true);
		context.register(WishPhotoInfrastructure.class);
		context.refresh();
		return context;
	}
}
