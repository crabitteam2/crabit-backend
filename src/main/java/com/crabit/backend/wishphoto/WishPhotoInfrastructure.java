package com.crabit.backend.wishphoto;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class WishPhotoInfrastructure {
	@Bean
	@ConditionalOnMissingBean
	WishPhotoStorage wishPhotoStorage() {
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
	WishPhotoSafetyScanner wishPhotoSafetyScanner() {
		return bytes -> { throw new WishPhotoException(
				WishPhotoException.Code.PHOTO_PROCESSING_UNAVAILABLE,
				"Wish photo safety screening is not configured."); };
	}
}
