package com.crabit.backend.wish;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.crabit.backend.wishphoto.WishPhotoView;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
		property = "kind", visible = true)
@JsonSubTypes({
		@JsonSubTypes.Type(value = SharedCardProjection.Progress.class, name = "PROGRESS"),
		@JsonSubTypes.Type(value = SharedCardProjection.Completion.class, name = "COMPLETION"),
		@JsonSubTypes.Type(value = SharedCardProjection.Abandonment.class, name = "ABANDONMENT")
})
@Schema(name = "SharedCard", description = "A privacy-safe shared Wish projection.",
		discriminatorProperty = "kind", oneOf = {
				SharedCardProjection.Progress.class, SharedCardProjection.Completion.class,
				SharedCardProjection.Abandonment.class})
public sealed interface SharedCardProjection
		permits SharedCardProjection.Progress, SharedCardProjection.Completion,
		SharedCardProjection.Abandonment {

	UUID sharedCardId();
	String kind();
	UUID ownerId();
	String ownerNickname();
	LocalDate startDate();
	LocalDate targetDate();
	String purpose();
	long targetAmount();
	int progressPercent();
	Instant contentUpdatedAt();

	@Schema(name = "ProgressSharedCard",
			description = "A currently published, non-completed Wish card.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	record Progress(
			@Schema(ref = "#/components/schemas/Uuid", description = "Stable public card UUID.")
			UUID sharedCardId,
			@Schema(allowableValues = "PROGRESS", description = "Closed variant discriminator.")
			String kind,
			@Schema(minLength = 1,
					description = "Current owner display nickname.")
			String ownerNickname,
			@Schema(ref = "#/components/schemas/Uuid") UUID ownerId,
			@Schema(format = "date", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
			LocalDate startDate,
			@Schema(ref = "#/components/schemas/Purpose",
					description = "Published Wish purpose.") String purpose,
			@Schema(ref = "#/components/schemas/KrwPositive",
					description = "Published target amount, not the current Wish amount.")
			long targetAmount,
			@Schema(minimum = "0", maximum = "100",
					description = "Floor percentage; only AMOUNT_REACHED emits 100.")
			int progressPercent,
			@Schema(description = "True only while the owning account has an OPEN adjustment case.")
			boolean balanceAdjustmentInProgress,
			@Schema(format = "date", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
			LocalDate targetDate,
			@Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
			WishPhotoView photo,
			@Schema(ref = "#/components/schemas/UtcInstant",
					description = "Latest content or publication change.")
			Instant contentUpdatedAt) implements SharedCardProjection {
	}

	@Schema(name = "CompletionSharedCard",
			description = "An explicitly completed Wish card without adjustment-case data.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	record Completion(
			@Schema(ref = "#/components/schemas/Uuid", description = "Stable public card UUID.")
			UUID sharedCardId,
			@Schema(allowableValues = "COMPLETION", description = "Closed variant discriminator.")
			String kind,
			@Schema(minLength = 1,
					description = "Current owner display nickname.")
			String ownerNickname,
			@Schema(ref = "#/components/schemas/Uuid") UUID ownerId,
			@Schema(format = "date", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
			LocalDate startDate,
			@Schema(ref = "#/components/schemas/Purpose",
					description = "Published Wish purpose.") String purpose,
			@Schema(ref = "#/components/schemas/KrwPositive",
					description = "Published target amount, not the historical Wish amount.")
			long targetAmount,
			@Schema(allowableValues = "100", description = "Always 100 for Completion.")
			int progressPercent,
			@Schema(format = "date", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED, description = "Optional stored owner target date.")
			LocalDate targetDate,
			@Schema(ref = "#/components/schemas/UtcInstant",
					description = "Wish creation time.") Instant createdAt,
			@Schema(ref = "#/components/schemas/UtcInstant",
					description = "Explicit completion time.") Instant completedAt,
			@Schema(minimum = "0", description = "Non-negative elapsed whole seconds.")
			long actualDurationSeconds,
			@Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
			WishPhotoView photo,
			@Schema(ref = "#/components/schemas/UtcInstant",
					description = "Latest content or publication change.")
			Instant contentUpdatedAt) implements SharedCardProjection {
	}

	@Schema(name = "AbandonmentSharedCard",
			description = "A published abandoned Wish card with privacy-safe immutable progress.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	record Abandonment(
			@Schema(ref = "#/components/schemas/Uuid", description = "Stable public card UUID.")
			UUID sharedCardId,
			@Schema(allowableValues = "ABANDONMENT", description = "Closed variant discriminator.")
			String kind,
			@Schema(allowableValues = "ABANDONED", description = "Closed abandoned Wish lifecycle state.")
			String state,
			@Schema(ref = "#/components/schemas/Uuid") UUID ownerId,
			@Schema(minLength = 1, description = "Current owner display nickname.")
			String ownerNickname,
			@Schema(ref = "#/components/schemas/Purpose", description = "Published Wish purpose.")
			String purpose,
			@Schema(ref = "#/components/schemas/KrwPositive",
					description = "Published target amount, not an abandonment amount or current allocation.")
			long targetAmount,
			@Schema(minimum = "0", maximum = "100",
					description = "Floor percentage derived once from the immutable abandonment amount.")
			int progressPercent,
			@Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
			WishPhotoView photo,
			@Schema(format = "date", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
			LocalDate startDate,
			@Schema(format = "date", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
			LocalDate targetDate,
			@Schema(ref = "#/components/schemas/UtcInstant",
					description = "Latest content or publication change.")
			Instant contentUpdatedAt) implements SharedCardProjection {
	}
}
