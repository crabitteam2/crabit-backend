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

	AccountBoundary decodeAccount(String raw, String operation, UUID accountId,
			ImmutableHistoryQueryOptions options) {
		if (raw == null) return null;
		try {
			String[] fields = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8)
					.split("\\|", -1);
			if (fields.length == 6 && fields[0].equals("1") && options.defaults()) {
				return new AccountBoundary(decode(raw, operation, accountId, null), null);
			}
			if (fields.length != 11 || !fields[0].equals("2")
					|| !fields[1].equals(operation) || !UUID.fromString(fields[2]).equals(accountId)
					|| !fields[3].equals("1")
					|| !fields[4].equals(time(options.from())) || !fields[5].equals(time(options.to()))
					|| !fields[6].equals(text(options.query())) || !fields[7].equals(options.sort())) {
				throw malformed();
			}
			long ceiling = Long.parseLong(fields[10]);
			if (ceiling < 0) throw malformed();
			return new AccountBoundary(new Boundary(Instant.parse(fields[8]), UUID.fromString(fields[9])), ceiling);
		} catch (RuntimeException exception) {
			throw malformed();
		}
	}

	String encodeAccount(String operation, UUID accountId, ImmutableHistoryQueryOptions options,
			Boundary boundary, Long ceiling) {
		if (ceiling == null) return encode(operation, accountId, null, boundary);
		String payload = String.join("|", "2", operation, accountId.toString(), "1",
				time(options.from()), time(options.to()), text(options.query()), options.sort(),
				boundary.occurredAt().toString(), boundary.eventId().toString(), ceiling.toString());
		return text(payload);
	}

	private static String time(Instant value) { return value == null ? "" : value.toString(); }
	private static String text(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	record AccountBoundary(Boundary boundary, Long ceiling) { }

	private static WishLifecycleException malformed() {
		return new WishLifecycleException(
				WishLifecycleException.Code.MALFORMED_REQUEST,
				"The history cursor is malformed or belongs to another resource.",
				"cursor");
	}

	record Boundary(Instant occurredAt, UUID eventId) {
	}

}
