package com.crabit.backend.wishphoto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "WishPhoto", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record WishPhotoView(UUID id, Variants variants, Instant expiresAt) {
	@Schema(name = "WishPhotoVariants", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record Variants(String small, String medium, String large) {}
}
