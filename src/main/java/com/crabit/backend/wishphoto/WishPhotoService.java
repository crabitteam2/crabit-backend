package com.crabit.backend.wishphoto;

import com.crabit.backend.account.StudentRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WishPhotoService {
	private static final Duration URL_VALIDITY = Duration.ofMinutes(5);
	private static final Duration RECEIPT_RETENTION = Duration.ofHours(24);
	private final WishPhotoRepository photos;
	private final StudentRepository students;
	private final WishPhotoProcessor processor;
	private final WishPhotoSafetyScanner safety;
	private final WishPhotoStorage storage;
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final boolean enabled;
	private final TransactionTemplate requiresNew;

	WishPhotoService(WishPhotoRepository photos, StudentRepository students,
			WishPhotoProcessor processor, WishPhotoSafetyScanner safety,
			WishPhotoStorage storage, JdbcTemplate jdbc, Clock clock,
			PlatformTransactionManager transactionManager,
			@Value("${crabit.wish-photo.enabled:false}") boolean enabled) {
		this.photos = photos; this.students = students; this.processor = processor;
		this.safety = safety; this.storage = storage; this.jdbc = jdbc; this.clock = clock;
		this.enabled = enabled;
		this.requiresNew = new TransactionTemplate(transactionManager);
		this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Transactional(noRollbackFor = ReceiptRepairException.class)
	public UploadOutcome upload(UUID ownerId, String key, byte[] source, String contentType) {
		requireEnabled();
		Instant now = clock.instant();
		String idempotencyKey = requireKey(key);
		String digest = digest(source);
		students.lockById(ownerId).orElseThrow(() -> notFound());
		jdbc.update("DELETE FROM wish_photo_upload_receipt WHERE owner_student_id = ? "
				+ "AND idempotency_key = ? "
				+ "AND (outcome ->> 'retainUntil')::timestamptz <= ?",
				ownerId, idempotencyKey, Timestamp.from(now));
		var prior = jdbc.query("SELECT content_digest, photo_id, outcome ->> 'kind' "
				+ "FROM wish_photo_upload_receipt "
				+ "WHERE owner_student_id = ? AND idempotency_key = ? FOR UPDATE",
				(rs, row) -> new Receipt(rs.getString(1), rs.getObject(2, UUID.class),
						rs.getString(3)), ownerId, idempotencyKey);
		if (!prior.isEmpty()) {
			Receipt receipt = prior.getFirst();
			if (!receipt.digest().equals(digest)) throw new WishPhotoException(
					WishPhotoException.Code.IDEMPOTENCY_KEY_REUSED,
					"Idempotency-Key was already used for different content.");
			if (ReceiptKind.REVOKED_SUCCESS.name().equals(receipt.kind())) throw expired();
			if (!ReceiptKind.ACTIVE_SUCCESS.name().equals(receipt.kind())) {
				throw replayedFailure(receipt.kind());
			}
			WishPhoto photo = photos.lockById(receipt.photoId()).orElse(null);
			if (photo == null || photo.state() == WishPhotoState.DELETE_PENDING
					|| (photo.state() == WishPhotoState.PENDING && !now.isBefore(photo.expiresAt()))) {
				markRevoked(receipt.photoId(), now);
				throw new ReceiptRepairException();
			}
			return new UploadOutcome(view(photo), true);
		}
		long attempts = jdbc.queryForObject("SELECT count(*) FROM wish_photo_processing_attempt "
				+ "WHERE owner_student_id = ? AND attempted_at > ?", Long.class,
				ownerId, Timestamp.from(now.minus(Duration.ofHours(1))));
		long pending = photos.countByOwnerStudentIdAndStateAndExpiresAtAfter(
				ownerId, WishPhotoState.PENDING, now);
		long retryAfter = Math.max(attempts >= 20 ? attemptRetryAfter(ownerId, now) : 0,
				pending >= 3 ? pendingRetryAfter(ownerId, now) : 0);
		if (retryAfter > 0) throw new WishPhotoException(
				WishPhotoException.Code.PHOTO_UPLOAD_RATE_LIMITED,
				"Wish photo upload rate limit exceeded.", Math.toIntExact(retryAfter));
		recordAttempt(ownerId, now);
		Map<WishPhotoStorage.Variant, byte[]> variants;
		try {
			variants = processor.process(source, contentType);
			if (!safety.allowed(variants.get(WishPhotoStorage.Variant.LARGE))) {
				throw new WishPhotoException(WishPhotoException.Code.PHOTO_CONTENT_NOT_ALLOWED,
						"Wish photo content is not allowed.");
			}
		} catch (WishPhotoException exception) {
			if (terminal(exception.code())) recordFailure(ownerId, idempotencyKey, digest, exception, now);
			throw exception;
		}
		WishPhoto photo = WishPhoto.pending(ownerId, digest, now);
		try { storage.put(photo.objectPrefix(), variants); }
		catch (RuntimeException exception) {
			compensate(photo.id(), photo.objectPrefix());
			if (exception instanceof WishPhotoException photoException) throw photoException;
			throw new WishPhotoException(WishPhotoException.Code.PHOTO_PROCESSING_UNAVAILABLE,
					"Wish photo processing is unavailable.");
		}
		registerRollbackCompensation(photo.id(), photo.objectPrefix());
		photos.saveAndFlush(photo);
		insertReceipt(ownerId, idempotencyKey, digest, ReceiptKind.ACTIVE_SUCCESS,
				photo.id(), now);
		return new UploadOutcome(view(photo), false);
	}

	@Transactional
	public void cancel(UUID ownerId, UUID photoId) {
		requireEnabled();
		WishPhoto photo = photos.lockById(photoId).filter(value -> value.ownerStudentId().equals(ownerId))
				.orElseThrow(WishPhotoService::notFound);
		Instant now = clock.instant();
		photo.requestDeletion(now);
		markRevoked(photo.id(), now);
		enqueue(photo, now);
	}

	@Transactional(readOnly = true)
	public WishPhotoView attachedView(UUID wishId) {
		WishPhoto photo = photos.findByAttachedWishIdAndState(wishId, WishPhotoState.ATTACHED)
				.orElse(null);
		if (photo == null) return null;
		requireEnabled();
		return view(photo);
	}

	@Transactional
	public void attach(UUID ownerId, UUID photoId, UUID wishId) {
		requireEnabled();
		WishPhoto photo = photos.lockById(photoId).filter(value -> value.ownerStudentId().equals(ownerId))
				.orElseThrow(WishPhotoService::notFound);
		photo.attach(wishId, clock.instant());
	}

	@Transactional
	public void detach(UUID wishId) {
		photos.findByAttachedWishIdAndState(wishId, WishPhotoState.ATTACHED).ifPresent(photo -> {
			Instant now = clock.instant();
			photo.detach(now);
			markRevoked(photo.id(), now);
			enqueue(photo, now);
		});
	}

	@Transactional
	public boolean replace(UUID ownerId, UUID wishId, UUID replacementId) {
		requireEnabled();
		WishPhoto current = photos.findByAttachedWishIdAndState(wishId, WishPhotoState.ATTACHED).orElse(null);
		if (current != null && current.id().equals(replacementId)) return false;
		WishPhoto replacement = replacementId == null ? null
				: photos.lockById(replacementId)
						.filter(value -> value.ownerStudentId().equals(ownerId))
						.orElseThrow(WishPhotoService::notFound);
		Instant now = clock.instant();
		if (current != null) {
			current.detach(now);
			markRevoked(current.id(), now);
			enqueue(current, now);
			photos.flush();
		}
		if (replacement != null) replacement.attach(wishId, now);
		return current != null || replacementId != null;
	}

	private void recordAttempt(UUID ownerId, Instant now) {
		requiresNew.executeWithoutResult(status -> jdbc.update(
				"INSERT INTO wish_photo_processing_attempt(id, owner_student_id, attempted_at) VALUES (?, ?, ?)",
				UUID.randomUUID(), ownerId, Timestamp.from(now)));
	}

	private void recordFailure(UUID ownerId, String key, String digest,
			WishPhotoException exception, Instant now) {
		requiresNew.executeWithoutResult(status -> insertReceipt(ownerId, key, digest,
				ReceiptKind.valueOf(exception.code().name()), null, now));
	}

	private void insertReceipt(UUID ownerId, String key, String digest, ReceiptKind kind,
			UUID photoId, Instant now) {
		jdbc.update("INSERT INTO wish_photo_upload_receipt(owner_student_id, idempotency_key, "
				+ "content_digest, outcome, photo_id) VALUES (?, ?, ?, "
				+ "jsonb_build_object('kind', ?, 'retainUntil', ?), ?) "
				+ "ON CONFLICT (owner_student_id, idempotency_key) DO NOTHING",
				ownerId, key, digest, kind.name(), now.plus(RECEIPT_RETENTION).toString(), photoId);
	}

	private void markRevoked(UUID photoId, Instant now) {
		if (photoId == null) return;
		jdbc.update("UPDATE wish_photo_upload_receipt SET outcome = jsonb_build_object("
				+ "'kind', 'REVOKED_SUCCESS', 'retainUntil', outcome ->> 'retainUntil') "
				+ "WHERE photo_id = ? AND outcome ->> 'kind' = 'ACTIVE_SUCCESS' "
				+ "AND (outcome ->> 'retainUntil')::timestamptz > ?",
				photoId, Timestamp.from(now));
	}

	private long attemptRetryAfter(UUID ownerId, Instant now) {
		Timestamp earliest = jdbc.queryForObject("SELECT min(attempted_at) FROM wish_photo_processing_attempt " +
				"WHERE owner_student_id = ? AND attempted_at > ?", Timestamp.class,
				ownerId, Timestamp.from(now.minus(Duration.ofHours(1))));
		return secondsUntil(now, earliest.toInstant().plus(Duration.ofHours(1)));
	}

	private long pendingRetryAfter(UUID ownerId, Instant now) {
		Timestamp earliest = jdbc.queryForObject("SELECT min(expires_at) FROM wish_photo " +
				"WHERE owner_student_id = ? AND state = 'PENDING' AND expires_at > ?", Timestamp.class,
				ownerId, Timestamp.from(now));
		return secondsUntil(now, earliest.toInstant());
	}

	private static long secondsUntil(Instant now, Instant release) {
		long millis = Math.max(1, Duration.between(now, release).toMillis());
		return Math.max(1, (millis + 999) / 1000);
	}

	private static boolean terminal(WishPhotoException.Code code) {
		return code == WishPhotoException.Code.PHOTO_TOO_LARGE
				|| code == WishPhotoException.Code.UNSUPPORTED_PHOTO_TYPE
				|| code == WishPhotoException.Code.INVALID_PHOTO
				|| code == WishPhotoException.Code.PHOTO_CONTENT_NOT_ALLOWED;
	}

	private static WishPhotoException replayedFailure(String kind) {
		WishPhotoException.Code code = WishPhotoException.Code.valueOf(kind);
		String message = switch (code) {
			case PHOTO_TOO_LARGE -> "Wish photo exceeds 5 MiB.";
			case UNSUPPORTED_PHOTO_TYPE -> "Wish photo must be a JPEG.";
			case INVALID_PHOTO -> "Wish photo must be a valid 1080x1080 JPEG.";
			case PHOTO_CONTENT_NOT_ALLOWED -> "Wish photo content is not allowed.";
			default -> throw new IllegalStateException("Receipt contains a non-replayable outcome");
		};
		return new WishPhotoException(code, message);
	}

	private void registerRollbackCompensation(UUID photoId, String prefix) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) compensate(photoId, prefix);
			}
		});
	}

	private void compensate(UUID photoId, String prefix) {
		try {
			storage.delete(prefix);
		} catch (RuntimeException exception) {
			Instant now = clock.instant();
			requiresNew.executeWithoutResult(status -> jdbc.update(
					"INSERT INTO wish_photo_cleanup_work(photo_id, object_prefix, requested_at, next_attempt_at) "
							+ "VALUES (?, ?, ?, ?) ON CONFLICT (photo_id) DO NOTHING",
					photoId, prefix, Timestamp.from(now), Timestamp.from(now)));
		}
	}

	private void requireEnabled() {
		if (!enabled) throw new WishPhotoException(WishPhotoException.Code.PHOTO_PROCESSING_UNAVAILABLE,
				"Wish photo runtime is disabled.");
	}

	private void enqueue(WishPhoto photo, Instant now) {
		jdbc.update("INSERT INTO wish_photo_cleanup_work(photo_id, object_prefix, requested_at, next_attempt_at) "
				+ "VALUES (?, ?, ?, ?) ON CONFLICT (photo_id) DO NOTHING", photo.id(), photo.objectPrefix(),
				Timestamp.from(now), Timestamp.from(now));
	}

	private WishPhotoView view(WishPhoto photo) {
		try {
			return new WishPhotoView(photo.id(), storage.signedUrls(photo.objectPrefix(), URL_VALIDITY),
					clock.instant().plus(URL_VALIDITY));
		} catch (WishPhotoException exception) { throw exception; }
		catch (RuntimeException exception) { throw new WishPhotoException(
				WishPhotoException.Code.PHOTO_DELIVERY_UNAVAILABLE, "Wish photo delivery is unavailable."); }
	}

	private static String requireKey(String key) {
		if (key == null || key.isBlank()) throw new WishPhotoException(
				WishPhotoException.Code.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key is required.");
		if (key.length() > 200) throw new WishPhotoException(
				WishPhotoException.Code.MALFORMED_REQUEST, "Idempotency-Key is too long.");
		return key;
	}
	private static String digest(byte[] value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
		catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
	}
	private static WishPhotoException notFound() { return new WishPhotoException(
			WishPhotoException.Code.WISH_PHOTO_NOT_FOUND, "Wish photo not found."); }
	private static WishPhotoException expired() { return new WishPhotoException(
			WishPhotoException.Code.WISH_PHOTO_EXPIRED, "Wish photo is no longer available."); }
	public record UploadOutcome(WishPhotoView photo, boolean replayed) {}
	private record Receipt(String digest, UUID photoId, String kind) {}
	private enum ReceiptKind {
		ACTIVE_SUCCESS,
		REVOKED_SUCCESS,
		PHOTO_TOO_LARGE,
		UNSUPPORTED_PHOTO_TYPE,
		INVALID_PHOTO,
		PHOTO_CONTENT_NOT_ALLOWED
	}
	private static final class ReceiptRepairException extends WishPhotoException {
		private ReceiptRepairException() {
			super(Code.WISH_PHOTO_EXPIRED, "Wish photo is no longer available.");
		}
	}
}
