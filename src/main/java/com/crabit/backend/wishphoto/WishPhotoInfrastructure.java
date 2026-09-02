package com.crabit.backend.wishphoto;

import java.time.Duration;
import java.util.Map;
import java.time.Clock;
import com.crabit.backend.wishphoto.googlecloud.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class WishPhotoInfrastructure {
	@Bean
	@ConditionalOnProperty(name = "crabit.wish-photo.enabled", havingValue = "true")
	GoogleCloudPhotoRequestBudget googleCloudPhotoRequestBudget() {
		return new GoogleCloudPhotoRequestBudget();
	}

	@Bean
	@ConditionalOnMissingBean(WishPhotoClock.class)
	WishPhotoClock wishPhotoClock(Clock domainClock, @Value("${crabit.wish-photo.enabled:false}") boolean enabled) {
		return new WishPhotoClock(enabled ? Clock.systemUTC() : domainClock);
	}
	@Bean(destroyMethod = "close")
	@Lazy
	@ConditionalOnProperty(name = "crabit.wish-photo.enabled", havingValue = "true")
	GoogleCloudPhotoRuntime googleCloudPhotoRuntime(Environment env, WishPhotoClock clock) {
		return new GoogleCloudPhotoRuntime(new GoogleCloudPhotoSettings(
				env.getProperty("crabit.wish-photo.environment", ""), env.getProperty("crabit.wish-photo.project-id", ""),
				env.getProperty("crabit.wish-photo.project-number", ""), env.getProperty("crabit.wish-photo.bucket", ""),
				env.getProperty("crabit.wish-photo.service-account", "")), clock);
	}
	@Bean
	@ConditionalOnMissingBean
	@Lazy
	WishPhotoStorage wishPhotoStorage(@Value("${crabit.wish-photo.enabled:false}") boolean enabled, ObjectProvider<GoogleCloudPhotoRuntime> runtime) {
		if (enabled) return runtime.getObject().storage();
		return new WishPhotoStorage() {
			@Override public void put(String prefix, Map<Variant, byte[]> variants) { unavailable(); }
			@Override public void delete(String prefix) { unavailable(); }
			@Override public WishPhotoView.Variants signedUrls(String prefix, Duration validity) {
				throw new WishPhotoException(WishPhotoException.Code.PHOTO_DELIVERY_UNAVAILABLE,
						"Private Wish photo delivery is not configured.");
			}
			private <T> T unavailable() {
				throw new WishPhotoException(WishPhotoException.Code.PHOTO_PROCESSING_UNAVAILABLE,
						"Private Wish photo storage is not configured.");
			}
		};
	}

	@Bean
	@ConditionalOnMissingBean
	@Lazy
	WishPhotoSafetyScanner wishPhotoSafetyScanner(@Value("${crabit.wish-photo.enabled:false}") boolean enabled, ObjectProvider<GoogleCloudPhotoRuntime> runtime) {
		if (enabled) return runtime.getObject().safety();
		return bytes -> { throw new WishPhotoException(
				WishPhotoException.Code.PHOTO_PROCESSING_UNAVAILABLE,
				"Wish photo safety screening is not configured."); };
	}
}
