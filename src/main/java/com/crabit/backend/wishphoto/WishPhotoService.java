package com.crabit.backend.wishphoto;

import com.crabit.backend.account.StudentRepository;
import com.crabit.backend.wish.WishIdempotencyRepository;
import com.crabit.backend.wishphoto.googlecloud.WishPhotoClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WishPhotoService {
	private static final Duration RECEIPT_RETENTION = Duration.ofHours(24);
	private final WishPhotoRepository photos;
	private final StudentRepository students;
	private final WishIdempotencyRepository wishReceipts;
	private final WishPhotoProcessor processor;
	private final WishPhotoSafetyScanner safety;
	private final WishPhotoStorage storage;
	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final boolean enabled;
	private final TransactionTemplate requiresNew;

	WishPhotoService(WishPhotoRepository photos, StudentRepository students,
			WishIdempotencyRepository wishReceipts,
			WishPhotoProcessor processor, WishPhotoSafetyScanner safety,
			WishPhotoStorage storage, JdbcTemplate jdbc, WishPhotoClock photoClock,
			PlatformTransactionManager transactionManager,
			@Value("${crabit.wish-photo.enabled:false}") boolean enabled) {
		this.photos = photos; this.students = students; this.wishReceipts = wishReceipts;
		this.processor = processor;
		this.safety = safety; this.storage = storage; this.jdbc = jdbc; this.clock = photoClock.value();
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
				revokeReferencesBeforeInaccessible(ownerId, receipt.photoId(), now);
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
		lockOwnerNamespace(ownerId);
		lockUploadReceipts(ownerId, List.of(photoId));
		WishPhoto photo = photos.lockById(photoId).filter(value -> value.ownerStudentId().equals(ownerId))
				.orElseThrow(WishPhotoService::notFound);
		Instant now = clock.instant();
		revokeReferencesBeforeInaccessible(ownerId, photo.id(), now);
		photo.requestDeletion(now);
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
		lockOwnerNamespace(ownerId);
		lockUploadReceipts(ownerId, List.of(photoId));
		WishPhoto photo = photos.lockById(photoId).filter(value -> value.ownerStudentId().equals(ownerId))
				.orElseThrow(WishPhotoService::notFound);
		photo.attach(wishId, clock.instant());
	}

	@Transactional
	public void detach(UUID wishId) {
		WishPhoto discovered = photos.findByAttachedWishIdAndState(wishId, WishPhotoState.ATTACHED)
				.orElse(null);
		if (discovered == null) return;
		UUID ownerId = discovered.ownerStudentId();
		lockOwnerNamespace(ownerId);
		WishPhoto current = photos.findByAttachedWishIdAndState(wishId, WishPhotoState.ATTACHED)
				.orElse(null);
		if (current == null) return;
		lockUploadReceipts(ownerId, List.of(current.id()));
		WishPhoto photo = photos.lockById(current.id())
				.filter(value -> value.ownerStudentId().equals(ownerId))
				.filter(value -> wishId.equals(value.attachedWishId()))
				.filter(value -> value.state() == WishPhotoState.ATTACHED)
				.orElse(null);
		if (photo == null) return;
		Instant now = clock.instant();
		revokeReferencesBeforeInaccessible(ownerId, photo.id(), now);
		photo.detach(now);
		enqueue(photo, now);
	}

	@Transactional
	public boolean replace(UUID ownerId, UUID wishId, UUID replacementId) {
		requireEnabled();
		lockOwnerNamespace(ownerId);
		WishPhoto current = photos.findByAttachedWishIdAndState(wishId, WishPhotoState.ATTACHED).orElse(null);
		if (current != null && current.id().equals(replacementId)) return false;
		List<UUID> photoIds = java.util.stream.Stream.of(
				current == null ? null : current.id(), replacementId)
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.toList();
		lockUploadReceipts(ownerId, photoIds);
		Map<UUID, WishPhoto> locked = lockPhotos(photoIds);
		WishPhoto lockedCurrent = current == null ? null : locked.get(current.id());
		if (lockedCurrent != null
				&& (lockedCurrent.state() != WishPhotoState.ATTACHED
						|| !wishId.equals(lockedCurrent.attachedWishId())
						|| !ownerId.equals(lockedCurrent.ownerStudentId()))) {
			lockedCurrent = null;
		}
		WishPhoto replacement = replacementId == null ? null : locked.get(replacementId);
		if (replacementId != null && (replacement == null
				|| !replacement.ownerStudentId().equals(ownerId))) {
			throw notFound();
		}
		Instant now = clock.instant();
		if (lockedCurrent != null) {
			revokeReferencesBeforeInaccessible(ownerId, lockedCurrent.id(), now);
			lockedCurrent.detach(now);
			enqueue(lockedCurrent, now);
			photos.flush();
		}
		if (replacement != null) replacement.attach(wishId, now);
		return lockedCurrent != null || replacementId != null;
	}

	@Transactional
	public Map<UUID, WishPhotoView> replayAttachedViews(
			UUID ownerId, Map<UUID, UUID> expectedPhotoByWish) {
		if (!enabled) throw new WishPhotoException(
				WishPhotoException.Code.PHOTO_DELIVERY_UNAVAILABLE,
				"Wish photo delivery is unavailable.");
		if (expectedPhotoByWish.isEmpty()) return Map.of();
		lockOwnerNamespace(ownerId);
		List<UUID> photoIds = expectedPhotoByWish.values().stream().distinct().sorted().toList();
		lockUploadReceipts(ownerId, photoIds);
		Map<UUID, WishPhoto> locked = lockPhotos(photoIds);
		for (Map.Entry<UUID, UUID> expected : expectedPhotoByWish.entrySet()) {
			WishPhoto photo = locked.get(expected.getValue());
			if (photo == null
					|| !ownerId.equals(photo.ownerStudentId())
					|| photo.state() != WishPhotoState.ATTACHED
					|| !expected.getKey().equals(photo.attachedWishId())) {
				throw expired();
			}
		}
		Map<UUID, WishPhotoView> result = new LinkedHashMap<>();
		for (Map.Entry<UUID, UUID> expected : expectedPhotoByWish.entrySet()) {
			result.put(expected.getKey(), view(locked.get(expected.getValue())));
		}
		return Map.copyOf(result);
	}

	@Transactional
	boolean expireOnePending(Instant now) {
		List<PhotoOwner> candidates = jdbc.query("""
				SELECT id, owner_student_id
				FROM wish_photo
				WHERE state = 'PENDING' AND expires_at <= ?
				ORDER BY expires_at, id
				LIMIT 1
				""", (row, index) -> new PhotoOwner(
				row.getObject(1, UUID.class), row.getObject(2, UUID.class)), Timestamp.from(now));
		if (candidates.isEmpty()) return false;
		PhotoOwner candidate = candidates.getFirst();
		lockOwnerNamespace(candidate.ownerId());
		lockUploadReceipts(candidate.ownerId(), List.of(candidate.photoId()));
		WishPhoto photo = photos.lockById(candidate.photoId()).orElse(null);
		if (photo == null || photo.state() != WishPhotoState.PENDING
				|| now.isBefore(photo.expiresAt())) return false;
		revokeReferencesBeforeInaccessible(candidate.ownerId(), candidate.photoId(), now);
		photo.requestDeletion(now);
		enqueue(photo, now);
		return true;
	}

	@Transactional
	CleanupWork prepareOneCleanup(Instant now) {
		List<Work> candidates = jdbc.query("""
				SELECT photo_id, object_prefix, attempt_count
				FROM wish_photo_cleanup_work
				WHERE next_attempt_at <= ?
				ORDER BY requested_at, photo_id
				LIMIT 1
				""", (row, index) -> new Work(row.getObject(1, UUID.class),
				row.getString(2), row.getInt(3)), Timestamp.from(now));
		if (candidates.isEmpty()) return null;
		Work candidate = candidates.getFirst();
		WishPhoto discovered = photos.findById(candidate.photoId()).orElse(null);
		if (discovered == null) {
			Work lockedWork = lockCleanupWork(candidate.photoId());
			return lockedWork == null ? null : CleanupWork.orphan(lockedWork);
		}
		UUID ownerId = discovered.ownerStudentId();
		lockOwnerNamespace(ownerId);
		lockUploadReceipts(ownerId, List.of(candidate.photoId()));
		WishPhoto photo = photos.lockById(candidate.photoId()).orElse(null);
		Work lockedWork = lockCleanupWork(candidate.photoId());
		if (lockedWork == null) return null;
		if (photo == null) {
			return CleanupWork.orphan(lockedWork);
		}
		if (photo.state() != WishPhotoState.DELETE_PENDING) {
			deferCleanupWork(lockedWork, now, "photo is not delete-pending");
			return null;
		}
		revokeReferencesBeforeInaccessible(ownerId, photo.id(), now);
		return CleanupWork.owned(lockedWork, ownerId);
	}

	void deleteCleanupObject(CleanupWork work) {
		storage.delete(work.objectPrefix());
	}

	@Transactional
	void completeCleanup(CleanupWork prepared, Instant now) {
		if (prepared.ownerId() == null) {
			Work work = lockCleanupWork(prepared.photoId());
			if (matches(work, prepared)) {
				jdbc.update("DELETE FROM wish_photo_cleanup_work WHERE photo_id = ?",
						prepared.photoId());
			}
			return;
		}
		lockOwnerNamespace(prepared.ownerId());
		lockUploadReceipts(prepared.ownerId(), List.of(prepared.photoId()));
		WishPhoto photo = photos.lockById(prepared.photoId()).orElse(null);
		Work work = lockCleanupWork(prepared.photoId());
		if (!matches(work, prepared)) return;
		if (photo != null) {
			if (photo.state() != WishPhotoState.DELETE_PENDING
					|| !prepared.ownerId().equals(photo.ownerStudentId())) {
				deferCleanupWork(work, now, "photo changed before cleanup completion");
				return;
			}
			revokeReferencesBeforeInaccessible(prepared.ownerId(), prepared.photoId(), now);
			photos.deleteById(prepared.photoId());
		}
		jdbc.update("DELETE FROM wish_photo_cleanup_work WHERE photo_id = ?", prepared.photoId());
	}

	@Transactional
	void deferCleanup(CleanupWork prepared, Instant now) {
		if (prepared.ownerId() != null) {
			lockOwnerNamespace(prepared.ownerId());
			lockUploadReceipts(prepared.ownerId(), List.of(prepared.photoId()));
			photos.lockById(prepared.photoId());
		}
		Work work = lockCleanupWork(prepared.photoId());
		if (matches(work, prepared)) {
			deferCleanupWork(work, now, "storage deletion failed");
		}
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

	private void revokeReferencesBeforeInaccessible(UUID ownerId, UUID photoId, Instant now) {
		wishReceipts.redactPhotoReferences(ownerId, photoId);
		markRevoked(photoId, now);
		if (wishReceipts.hasActivePhotoReference(ownerId, photoId)) {
			throw new IllegalStateException("Wish photo replay reference redaction failed");
		}
	}

	private void lockOwnerNamespace(UUID ownerId) {
		students.lockById(ownerId).orElseThrow(WishPhotoService::notFound);
	}

	private void lockUploadReceipts(UUID ownerId, List<UUID> photoIds) {
		photoIds.stream().distinct().sorted().forEach(photoId -> jdbc.query(
				"SELECT photo_id FROM wish_photo_upload_receipt "
						+ "WHERE owner_student_id = ? AND photo_id = ? FOR UPDATE",
				(row, index) -> row.getObject(1, UUID.class), ownerId, photoId));
	}

	private Map<UUID, WishPhoto> lockPhotos(List<UUID> photoIds) {
		Map<UUID, WishPhoto> locked = new LinkedHashMap<>();
		for (UUID photoId : photoIds.stream().distinct().sorted().toList()) {
			photos.lockById(photoId).ifPresent(photo -> locked.put(photoId, photo));
		}
		return locked;
	}

	private Work lockCleanupWork(UUID photoId) {
		List<Work> rows = jdbc.query("""
				SELECT photo_id, object_prefix, attempt_count
				FROM wish_photo_cleanup_work
				WHERE photo_id = ?
				FOR UPDATE
				""", (row, index) -> new Work(row.getObject(1, UUID.class),
				row.getString(2), row.getInt(3)), photoId);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private static boolean matches(Work work, CleanupWork prepared) {
		return work != null
				&& work.photoId().equals(prepared.photoId())
				&& work.objectPrefix().equals(prepared.objectPrefix());
	}

	private void deferCleanupWork(Work work, Instant now, String reason) {
		long delay = Math.min(3600, 1L << Math.min(12, work.attemptCount()));
		jdbc.update("UPDATE wish_photo_cleanup_work SET attempt_count = attempt_count + 1, "
				+ "next_attempt_at = ?, last_error = ? WHERE photo_id = ?",
				Timestamp.from(now.plus(Duration.ofSeconds(delay))), reason, work.photoId());
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
			var window = new WishPhotoStorage.SigningWindow(clock.instant());
			var variants = storage.signedUrls(photo.objectPrefix(), window);
			if (variants == null || variants.small() == null || variants.medium() == null
					|| variants.large() == null || !clock.instant().isBefore(window.expiresAt())) {
				throw new IllegalStateException("Incomplete or expired delivery");
			}
			return new WishPhotoView(photo.id(), variants, window.expiresAt());
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
	private record PhotoOwner(UUID photoId, UUID ownerId) {}
	private record Work(UUID photoId, String objectPrefix, int attemptCount) {}
	record CleanupWork(UUID photoId, UUID ownerId, String objectPrefix, int attemptCount) {
		private static CleanupWork orphan(Work work) {
			return new CleanupWork(work.photoId(), null, work.objectPrefix(), work.attemptCount());
		}

		private static CleanupWork owned(Work work, UUID ownerId) {
			return new CleanupWork(
					work.photoId(), ownerId, work.objectPrefix(), work.attemptCount());
		}
	}
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
