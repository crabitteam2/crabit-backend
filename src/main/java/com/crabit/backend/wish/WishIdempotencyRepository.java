package com.crabit.backend.wish;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
class WishIdempotencyRepository {

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;

	WishIdempotencyRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	Optional<WishIdempotencyRecord> findByStudentIdAndIdempotencyKey(
			UUID studentId, String key) {
		List<String> serialized = jdbc.query(
				"SELECT (wish_idempotency_records -> ?)::text FROM student WHERE id = ?",
				(result, row) -> result.getString(1),
				key,
				studentId);
		return serialized.stream()
				.filter(value -> value != null)
				.findFirst()
				.map(this::deserialize);
	}

	void saveAndFlush(UUID studentId, String key, WishIdempotencyRecord record) {
		String serialized = serialize(record);
		int updated = jdbc.update("""
				UPDATE student
				SET wish_idempotency_records = jsonb_set(
						wish_idempotency_records, ARRAY[?], CAST(? AS jsonb), true)
				WHERE id = ? AND NOT jsonb_exists(wish_idempotency_records, ?)
				""", key, serialized, studentId, key);
		if (updated != 1) {
			throw new IllegalStateException("Idempotency record already exists or student is absent");
		}
	}

	private String serialize(WishIdempotencyRecord record) {
		try {
			return objectMapper.writeValueAsString(record);
		} catch (JacksonException exception) {
			throw new IllegalStateException("Cannot serialize Wish idempotency record", exception);
		}
	}

	private WishIdempotencyRecord deserialize(String serialized) {
		try {
			return objectMapper.readValue(serialized, WishIdempotencyRecord.class);
		} catch (JacksonException exception) {
			throw new IllegalStateException("Cannot deserialize Wish idempotency record", exception);
		}
	}
}
