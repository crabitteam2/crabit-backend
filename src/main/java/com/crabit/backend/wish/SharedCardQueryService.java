package com.crabit.backend.wish;

import com.crabit.backend.relationship.RelationshipContextAuthorizationService;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crabit.backend.wishphoto.WishPhotoService;

@Service
public class SharedCardQueryService {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 100;

	private final RelationshipContextAuthorizationService relationships;
	private final SharedCardQueryRepository queries;
	private final WishPhotoService photos;

	public SharedCardQueryService(
			RelationshipContextAuthorizationService relationships,
			SharedCardQueryRepository queries,
			java.util.Optional<WishPhotoService> photos) {
		this.relationships = relationships;
		this.queries = queries;
		this.photos = photos.orElse(null);
	}

	@Transactional(readOnly = true)
	public SharedCardPage list(
			UUID viewerId, UUID academyId, String cursor, Integer requestedLimit) {
		requireAcademy(viewerId, academyId);
		int limit = validateLimit(requestedLimit);
		SharedCardQueryRepository.CursorBoundary boundary = decode(cursor);
		List<SharedCardQueryRepository.Row> candidates = queries.findVisiblePage(
				viewerId, academyId, boundary, limit + 1);
		boolean hasNext = candidates.size() > limit;
		List<SharedCardQueryRepository.Row> pageRows = hasNext
				? candidates.subList(0, limit) : candidates;
		List<SharedCardProjection> items = pageRows.stream().map(this::project).toList();
		String nextCursor = hasNext ? encode(pageRows.getLast()) : null;
		return new SharedCardPage(items, nextCursor);
	}

	@Transactional(readOnly = true)
	public SharedCardProjection get(UUID viewerId, UUID academyId, UUID cardId) {
		requireAcademy(viewerId, academyId);
		return queries.findVisibleDetail(viewerId, academyId,
				Objects.requireNonNull(cardId, "cardId"))
				.map(this::project)
				.orElseThrow(() -> new WishLifecycleException(
						WishLifecycleException.Code.SHARED_CARD_NOT_FOUND,
						"Shared Card not found."));
	}

	private void requireAcademy(UUID viewerId, UUID academyId) {
		if (!relationships.canAccessAcademy(
				Objects.requireNonNull(viewerId, "viewerId"),
				Objects.requireNonNull(academyId, "academyId"))) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.ACADEMY_NOT_FOUND,
					"Academy not found.");
		}
	}

	private SharedCardProjection project(SharedCardQueryRepository.Row row) {
		var photo = photos == null ? null : photos.attachedView(row.wishId());
		if (row.kind() == SharedCardKind.COMPLETION) {
			if (row.completedAt() == null) {
				throw new IllegalStateException("Completion Shared Card requires completedAt");
			}
			long duration = Math.max(0L,
					Duration.between(row.createdAt(), row.completedAt()).getSeconds());
			return new SharedCardProjection.Completion(
					row.sharedCardId(), "COMPLETION", row.ownerNickname(), row.purpose(),
					row.targetAmount(), 100, row.targetDate(), row.createdAt(),
					row.completedAt(), duration, photo, row.contentUpdatedAt());
		}
		return new SharedCardProjection.Progress(
				row.sharedCardId(), "PROGRESS", row.ownerNickname(), row.purpose(),
				row.targetAmount(), progressPercent(row), row.balanceAdjustmentInProgress(),
				photo, row.contentUpdatedAt());
	}

	private static int progressPercent(SharedCardQueryRepository.Row row) {
		if (row.state() == WishState.AMOUNT_REACHED) {
			return 100;
		}
		BigInteger percent = BigInteger.valueOf(row.wishAmount())
				.multiply(BigInteger.valueOf(100))
				.divide(BigInteger.valueOf(row.targetAmount()));
		return Math.min(99, percent.intValueExact());
	}

	private static int validateLimit(Integer requestedLimit) {
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAX_LIMIT) {
			throw malformed("limit must be between 1 and 100.", "limit");
		}
		return limit;
	}

	private static SharedCardQueryRepository.CursorBoundary decode(String cursor) {
		if (cursor == null) {
			return null;
		}
		if (cursor.isBlank()) {
			throw malformed("cursor must be a nonblank opaque value.", "cursor");
		}
		try {
			String decoded = new String(
					Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = decoded.split("\\|", -1);
			if (parts.length != 2) {
				throw new IllegalArgumentException("wrong cursor part count");
			}
			return new SharedCardQueryRepository.CursorBoundary(
					Instant.parse(parts[0]), UUID.fromString(parts[1]));
		} catch (IllegalArgumentException | DateTimeParseException exception) {
			throw malformed("cursor is malformed.", "cursor");
		}
	}

	private static String encode(SharedCardQueryRepository.Row row) {
		String value = row.contentUpdatedAt() + "|" + row.sharedCardId();
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static WishLifecycleException malformed(String message, String field) {
		return new WishLifecycleException(
				WishLifecycleException.Code.MALFORMED_REQUEST, message, field);
	}

	@Schema(name = "SharedCardPage", description = "Visible cards and opaque continuation cursor.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record SharedCardPage(
			@ArraySchema(schema = @Schema(implementation = SharedCardProjection.class))
			List<SharedCardProjection> items,
			@Schema(nullable = true, minLength = 1,
					description = "Opaque next-page cursor, or null.")
			String nextCursor) {
	}
}
