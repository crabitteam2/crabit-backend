package com.crabit.backend.wishphoto;

public interface WishPhotoSafetyScanner {
	boolean allowed(byte[] canonicalJpeg);
}
