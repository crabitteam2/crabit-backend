package com.crabit.backend.wish;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class ImmutableHistoryCursor {

	private static final int VERSION = 1;

	Boundary decode(
			String rawCursor, String operation, UUID accountId, UUID wishId) {
		if (rawCursor == null) {
			return null;
		}
		if (rawCursor.isBlank()) {
			throw malformed();
		}
		try {
			String decoded = new String(
					Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8);
			String[] fields = decoded.split("\\|", -1);
			if (fields.length != 6
					|| Integer.parseInt(fields[0]) != VERSION
					|| !Objects.equals(fields[1], operation)
					|| !Objects.equals(UUID.fromString(fields[2]), accountId)
					|| !Objects.equals(fields[3].isEmpty() ? null : UUID.fromString(fields[3]), wishId)) {
				throw malformed();
			}
			return new Boundary(Instant.parse(fields[4]), UUID.fromString(fields[5]));
		} catch (WishLifecycleException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw malformed();
		}
	}

	String encode(
			String operation, UUID accountId, UUID wishId, Boundary boundary) {
		String payload = String.join("|",
				Integer.toString(VERSION),
				operation,
				accountId.toString(),
				wishId == null ? "" : wishId.toString(),
				boundary.occurredAt().toString(),
				boundary.eventId().toString());
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
	}

	private static WishLifecycleException malformed() {
		return new WishLifecycleException(
				WishLifecycleException.Code.MALFORMED_REQUEST,
				"The history cursor is malformed or belongs to another resource.",
				"cursor");
	}

	record Boundary(Instant occurredAt, UUID eventId) {
	}

}
