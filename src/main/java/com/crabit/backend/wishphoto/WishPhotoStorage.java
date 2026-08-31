package com.crabit.backend.wishphoto;

import java.time.Duration;
import java.util.Map;

public interface WishPhotoStorage {
	void put(String objectPrefix, Map<Variant, byte[]> variants);
	void delete(String objectPrefix);
	WishPhotoView.Variants signedUrls(String objectPrefix, Duration validity);
	enum Variant { SMALL, MEDIUM, LARGE }
}
