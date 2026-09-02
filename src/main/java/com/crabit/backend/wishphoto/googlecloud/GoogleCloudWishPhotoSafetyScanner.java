package com.crabit.backend.wishphoto.googlecloud;

import com.crabit.backend.wishphoto.*;
import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import java.util.function.Function;

public final class GoogleCloudWishPhotoSafetyScanner implements WishPhotoSafetyScanner {
	private final Function<BatchAnnotateImagesRequest, BatchAnnotateImagesResponse> inspect;
	public GoogleCloudWishPhotoSafetyScanner(Function<BatchAnnotateImagesRequest, BatchAnnotateImagesResponse> inspect) { this.inspect = inspect; }
	@Override public boolean allowed(byte[] jpeg) {
		try {
			if (jpeg == null || jpeg.length == 0 || jpeg.length > 5242880) throw new IllegalArgumentException();
			var request = BatchAnnotateImagesRequest.newBuilder().addRequests(AnnotateImageRequest.newBuilder()
					.setImage(Image.newBuilder().setContent(ByteString.copyFrom(jpeg)))
					.addFeatures(Feature.newBuilder().setType(Feature.Type.SAFE_SEARCH_DETECTION))).build();
			var batch = inspect.apply(request);
			if (batch == null || batch.getResponsesCount() != 1) throw new IllegalStateException();
			var response = batch.getResponses(0);
			if (response.hasError() || !response.hasSafeSearchAnnotation()) throw new IllegalStateException();
			var safety = response.getSafeSearchAnnotation();
			int[] values = {safety.getAdultValue(), safety.getRacyValue(), safety.getViolenceValue()};
			for (int value : values) if (value < 1 || value > 5) throw new IllegalStateException();
			for (int value : values) if (value >= 4) return false;
			return true;
		} catch (RuntimeException exception) {
			throw new WishPhotoException(WishPhotoException.Code.PHOTO_PROCESSING_UNAVAILABLE, "Wish photo safety screening is unavailable.");
		}
	}
}
