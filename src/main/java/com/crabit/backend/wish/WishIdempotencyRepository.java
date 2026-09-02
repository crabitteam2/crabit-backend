package com.crabit.backend.wish;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class WishIdempotencyRepository {

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

	void replaceExisting(UUID studentId, String key, WishIdempotencyRecord record) {
		String serialized = serialize(record);
		int updated = jdbc.update("""
				UPDATE student
				SET wish_idempotency_records = jsonb_set(
						wish_idempotency_records, ARRAY[?], CAST(? AS jsonb), false)
				WHERE id = ? AND jsonb_exists(wish_idempotency_records, ?)
				""", key, serialized, studentId, key);
		if (updated != 1) {
			throw new IllegalStateException("Idempotency record disappeared during replay");
		}
	}

	public void redactPhotoReferences(UUID studentId, UUID photoId) {
		String id = photoId.toString();
		jdbc.update("""
				UPDATE student
				SET wish_idempotency_records = (
					SELECT COALESCE(jsonb_object_agg(entry.key,
						CASE
							WHEN entry.value #>> '{destinationPhotoReplayState,kind}' = 'ACTIVE_PHOTO'
								AND entry.value #>> '{destinationPhotoReplayState,photoId}' = ?
							THEN jsonb_set(
								CASE
									WHEN entry.value #>> '{photoReplayState,kind}' = 'ACTIVE_PHOTO'
										AND entry.value #>> '{photoReplayState,photoId}' = ?
									THEN jsonb_set(entry.value, '{photoReplayState}',
										'{"kind":"PHOTO_REVOKED"}'::jsonb, false)
									ELSE entry.value
								END,
								'{destinationPhotoReplayState}',
								'{"kind":"PHOTO_REVOKED"}'::jsonb, false)
							WHEN entry.value #>> '{photoReplayState,kind}' = 'ACTIVE_PHOTO'
								AND entry.value #>> '{photoReplayState,photoId}' = ?
							THEN jsonb_set(entry.value, '{photoReplayState}',
								'{"kind":"PHOTO_REVOKED"}'::jsonb, false)
							ELSE entry.value
						END), '{}'::jsonb)
					FROM jsonb_each(wish_idempotency_records) AS entry
				)
				WHERE id = ?
					AND EXISTS (
						SELECT 1 FROM jsonb_each(wish_idempotency_records) AS entry
						WHERE (entry.value #>> '{photoReplayState,kind}' = 'ACTIVE_PHOTO'
								AND entry.value #>> '{photoReplayState,photoId}' = ?)
							OR (entry.value #>> '{destinationPhotoReplayState,kind}' = 'ACTIVE_PHOTO'
								AND entry.value #>> '{destinationPhotoReplayState,photoId}' = ?)
					)
				""", id, id, id, studentId, id, id);
	}

	public boolean hasActivePhotoReference(UUID studentId, UUID photoId) {
		String id = photoId.toString();
		Boolean active = jdbc.queryForObject("""
				SELECT EXISTS (
					SELECT 1
					FROM student, LATERAL jsonb_each(wish_idempotency_records) AS entry
					WHERE student.id = ?
						AND ((entry.value #>> '{photoReplayState,kind}' = 'ACTIVE_PHOTO'
								AND entry.value #>> '{photoReplayState,photoId}' = ?)
							OR (entry.value #>> '{destinationPhotoReplayState,kind}' = 'ACTIVE_PHOTO'
								AND entry.value #>> '{destinationPhotoReplayState,photoId}' = ?))
				)
				""", Boolean.class, studentId, id, id);
		return Boolean.TRUE.equals(active);
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
