package com.crabit.backend.wish;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.account.StudentRepository;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WishLifecycleService {

	private static final int MAX_PAGE_SIZE = 100;
	private static final String CREATE = "CREATE";
	private static final String COMPLETE = "COMPLETE";
	private static final String ABANDON = "ABANDON";
	private static final String DELETE = "DELETE";

	private final CardBalanceAccountRepository accountRepository;
	private final StudentRepository studentRepository;
	private final WishRepository wishRepository;
	private final WishIdempotencyRepository idempotencyRepository;
	private final WishEditCommandService editCommands;
	private final WishMoneyCommandService moneyCommands;
	private final BalanceAdjustmentPolicy adjustmentPolicy;
	private final RepresentativeWishService representativeWishes;
	private final Clock clock;

	public WishLifecycleService(
			CardBalanceAccountRepository accountRepository,
			StudentRepository studentRepository,
			WishRepository wishRepository,
			WishIdempotencyRepository idempotencyRepository,
			WishEditCommandService editCommands,
			WishMoneyCommandService moneyCommands,
			BalanceAdjustmentPolicy adjustmentPolicy,
			RepresentativeWishService representativeWishes,
			Clock clock) {
		this.accountRepository = accountRepository;
		this.studentRepository = studentRepository;
		this.wishRepository = wishRepository;
		this.idempotencyRepository = idempotencyRepository;
		this.editCommands = editCommands;
		this.moneyCommands = moneyCommands;
		this.adjustmentPolicy = adjustmentPolicy;
		this.representativeWishes = representativeWishes;
		this.clock = clock;
	}

	@Transactional
	public WishPage list(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			String encodedCursor,
			int limit,
			Set<WishState> states) {
		requirePageSize(limit);
		lockOwnedAccountForProjection(studentId, academyId, accountId);
		Cursor cursor = encodedCursor == null ? null : decodeCursor(encodedCursor);
		Collection<WishState> requestedStates = states == null ? Set.of() : states;
		Pageable page = PageRequest.of(0, limit + 1);
		List<Wish> remaining;
		if (cursor == null) {
			remaining = requestedStates.isEmpty()
					? wishRepository.findByAccountIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
							accountId, page)
					: wishRepository.findByAccountIdAndDeletedAtIsNullAndStateInOrderByCreatedAtDescIdDesc(
							accountId, requestedStates, page);
		} else {
			remaining = requestedStates.isEmpty()
					? wishRepository.findPageAfter(
							accountId, cursor.createdAt(), cursor.id(), page)
					: wishRepository.findPageAfterInStates(
							accountId, requestedStates, cursor.createdAt(), cursor.id(), page);
		}
		boolean hasNext = remaining.size() > limit;
		boolean adjustmentOpen = adjustmentPolicy.isOpen(accountId);
		List<WishSnapshot> items = remaining.stream()
				.limit(limit)
				.map(wish -> WishSnapshot.from(wish, adjustmentOpen))
				.toList();
		String nextCursor = hasNext
				? encodeCursor(remaining.get(limit - 1))
				: null;
		return new WishPage(items, nextCursor);
	}

	@Transactional
	public WishSnapshot get(
			UUID studentId, UUID academyId, UUID accountId, UUID wishId) {
		lockOwnedAccountForProjection(studentId, academyId, accountId);
		Wish wish = wishRepository.findByAccountIdAndIdAndDeletedAtIsNull(accountId, wishId)
				.orElseThrow(WishLifecycleService::wishNotFound);
		return WishSnapshot.from(wish, adjustmentPolicy.isOpen(accountId));
	}

	@Transactional
	public MutationOutcome create(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			String idempotencyKey,
			String purpose,
			long targetAmount,
			LocalDate startDate,
			LocalDate targetDate) {
		Instant now = clock.instant();
		CardBalanceAccount account = lockOwnedAccount(studentId, academyId, accountId);
		lockStudentNamespace(studentId);
		String normalizedPurpose = normalizePurpose(purpose);
		KrwAmount target = positiveAmount(targetAmount);
		String key = requireIdempotencyKey(idempotencyKey);
		try {
			Wish.validatePlanPeriod(startDate, targetDate);
		} catch (WishDateRangeException exception) {
			throw WishLifecycleException.invalidDateRange();
		}
		String fingerprint = fingerprint(
				CREATE, "v2", accountId.toString(), normalizedPurpose, Long.toString(target.won()),
				startDate == null ? "null" : startDate.toString(),
				targetDate == null ? "null" : targetDate.toString());
		String legacyFingerprint = startDate == null
				? fingerprint(
						CREATE, accountId.toString(), normalizedPurpose, Long.toString(target.won()),
						targetDate == null ? "null" : targetDate.toString())
				: null;
		Optional<WishIdempotencyRecord> prior = prior(studentId, key);
		if (prior.isPresent()) {
			return replayCreate(
					prior.orElseThrow(), accountId, fingerprint, legacyFingerprint);
		}
		try {
			adjustmentPolicy.requireAllowed(
					accountId, BalanceAdjustmentPolicy.Operation.CREATE_WISH);
		} catch (IllegalStateException exception) {
			throw mismatchLocked();
		}

		Wish wish = Wish.create(account.id(), account.academyId(), normalizedPurpose,
				target, startDate, targetDate, now);
		wishRepository.saveAndFlush(wish);
		representativeWishes.reconcile(accountId);
		return capture(studentId, key, CREATE, accountId, fingerprint, 201,
				WishSnapshot.from(wish, false), null, now);
	}

	@Transactional
	public MutationOutcome patch(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			UUID wishId,
			long expectedVersion,
			WishPatch patch) {
		Instant now = clock.instant();
		lockOwnedAccount(studentId, academyId, accountId);
		Wish wish = lockWish(accountId, wishId);
		requireExpectedVersion(expectedVersion, wish);
		try {
			Wish updated = editCommands.patch(accountId, wishId, patch, now);
			wishRepository.flush();
			return new MutationOutcome(
					WishSnapshot.from(updated, adjustmentPolicy.isOpen(accountId)),
					null, false, 200);
		} catch (WishDateRangeException exception) {
			throw WishLifecycleException.invalidDateRange();
		} catch (IllegalStateException exception) {
			if (exception.getMessage() != null
					&& exception.getMessage().contains("balance adjustment")) {
				throw new WishLifecycleException(
						WishLifecycleException.Code.BALANCE_MISMATCH_LOCKED,
						"The account balance must be reconciled before this operation.");
			}
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_STATE_TRANSITION,
					"The Wish cannot be changed from its current state.");
		} catch (IllegalArgumentException exception) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_AMOUNT,
					exception.getMessage());
		}
	}

	@Transactional
	public MutationOutcome complete(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			UUID wishId,
			String idempotencyKey,
			long expectedVersion) {
		return terminal(studentId, academyId, accountId, wishId, idempotencyKey,
				expectedVersion, COMPLETE);
	}

	@Transactional
	public MutationOutcome abandon(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			UUID wishId,
			String idempotencyKey,
			long expectedVersion) {
		return terminal(studentId, academyId, accountId, wishId, idempotencyKey,
				expectedVersion, ABANDON);
	}

	@Transactional
	public MutationOutcome delete(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			UUID wishId,
			String idempotencyKey,
			long expectedVersion) {
		return terminal(studentId, academyId, accountId, wishId, idempotencyKey,
				expectedVersion, DELETE);
	}

	private MutationOutcome terminal(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			UUID wishId,
			String idempotencyKey,
			long expectedVersion,
			String operation) {
		Instant now = clock.instant();
		lockOwnedAccount(studentId, academyId, accountId);
		lockStudentNamespace(studentId);
		String key = requireIdempotencyKey(idempotencyKey);
		requireNonNegativeVersion(expectedVersion);
		String fingerprint = fingerprint(
				operation, accountId.toString(), wishId.toString(), Long.toString(expectedVersion));
		Optional<WishIdempotencyRecord> prior = prior(studentId, key);
		if (prior.isPresent()) {
			return replay(prior.orElseThrow(), operation, wishId, fingerprint);
		}

		Wish wish = lockWish(accountId, wishId);
		requireExpectedVersion(expectedVersion, wish);
		WishMoneyCommandResult result;
		try {
			result = switch (operation) {
				case COMPLETE -> moneyCommands.complete(accountId, wishId, now);
				case ABANDON -> moneyCommands.abandon(accountId, wishId, now);
				case DELETE -> moneyCommands.tombstone(accountId, wishId, now);
				default -> throw new IllegalStateException("Unsupported terminal operation");
			};
		} catch (IllegalStateException exception) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_STATE_TRANSITION,
					"The Wish cannot transition from its current state.");
		}
		wishRepository.flush();
		UUID eventId = result.ledgerEvent().map(LedgerEvent::id).orElse(null);
		return capture(studentId, key, operation, wishId, fingerprint, 200,
				WishSnapshot.from(wish, adjustmentPolicy.isOpen(accountId)), eventId, now);
	}

	private MutationOutcome capture(
			UUID studentId,
			String key,
			String operation,
			UUID targetId,
			String fingerprint,
			int status,
			WishSnapshot wish,
			UUID eventId,
			Instant recordedAt) {
		idempotencyRepository.saveAndFlush(studentId, key, WishIdempotencyRecord.capture(
				operation, targetId, fingerprint, status, wish, eventId, recordedAt));
		return new MutationOutcome(wish, eventId, false, status);
	}

	private Optional<WishIdempotencyRecord> prior(UUID studentId, String key) {
		return idempotencyRepository.findByStudentIdAndIdempotencyKey(studentId, key);
	}

	private MutationOutcome replay(
			WishIdempotencyRecord record,
			String operation,
			UUID targetId,
			String fingerprint) {
		if (!record.matches(operation, targetId, fingerprint)) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.IDEMPOTENCY_KEY_REUSED,
					"Idempotency-Key was already used for a different request.");
		}
		return new MutationOutcome(record.snapshot(), record.eventId(), true, record.httpStatus());
	}

	private MutationOutcome replayCreate(
			WishIdempotencyRecord record,
			UUID accountId,
			String fingerprint,
			String legacyFingerprint) {
		boolean currentMatch = record.matches(CREATE, accountId, fingerprint);
		boolean legacyMatch = legacyFingerprint != null
				&& record.matches(CREATE, accountId, legacyFingerprint);
		if (!currentMatch && !legacyMatch) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.IDEMPOTENCY_KEY_REUSED,
					"Idempotency-Key was already used for a different request.");
		}
		return new MutationOutcome(
				record.snapshot(), record.eventId(), true, record.httpStatus());
	}

	private CardBalanceAccount lockOwnedAccount(
			UUID studentId, UUID academyId, UUID accountId) {
		return accountRepository.lockOwnedActive(accountId, studentId, academyId)
				.orElseThrow(WishLifecycleService::accountNotFound);
	}

	private CardBalanceAccount lockOwnedAccountForProjection(
			UUID studentId, UUID academyId, UUID accountId) {
		return accountRepository.lockOwnedActiveForProjection(
				accountId, studentId, academyId)
				.orElseThrow(WishLifecycleService::accountNotFound);
	}

	private void lockStudentNamespace(UUID studentId) {
		studentRepository.lockById(studentId)
				.orElseThrow(WishLifecycleService::accountNotFound);
	}

	private Wish lockWish(UUID accountId, UUID wishId) {
		return wishRepository.lockVisibleByAccountIdAndId(accountId, wishId)
				.orElseThrow(WishLifecycleService::wishNotFound);
	}

	private static void requireExpectedVersion(long expectedVersion, Wish wish) {
		requireNonNegativeVersion(expectedVersion);
		if (wish.version() != expectedVersion) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.VERSION_CONFLICT,
					"The supplied Wish version is stale.",
					"expectedVersion");
		}
	}

	private static void requireNonNegativeVersion(long version) {
		if (version < 0) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_VERSION,
					"Version must be non-negative.",
					"expectedVersion");
		}
	}

	private static KrwAmount positiveAmount(long amount) {
		try {
			return KrwAmount.positive(amount);
		} catch (IllegalArgumentException exception) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_AMOUNT,
					"targetAmount must be a positive JavaScript-safe integer.",
					"targetAmount");
		}
	}

	private static String normalizePurpose(String purpose) {
		try {
			return Wish.normalizePurpose(purpose);
		} catch (IllegalArgumentException exception) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_PURPOSE,
					exception.getMessage(),
					"purpose");
		}
	}

	private static String requireIdempotencyKey(String key) {
		if (key == null || key.isBlank()) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.IDEMPOTENCY_KEY_REQUIRED,
					"Idempotency-Key is required.",
					"Idempotency-Key");
		}
		if (key.length() > 200) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.MALFORMED_REQUEST,
					"Idempotency-Key must contain at most 200 characters.",
					"Idempotency-Key");
		}
		return key;
	}

	private static void requirePageSize(int limit) {
		if (limit < 1 || limit > MAX_PAGE_SIZE) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.MALFORMED_REQUEST,
					"limit must be between 1 and 100.",
					"limit");
		}
	}

	private static String encodeCursor(Wish wish) {
		ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES + Integer.BYTES + 2 * Long.BYTES);
		bytes.putLong(wish.createdAt().getEpochSecond());
		bytes.putInt(wish.createdAt().getNano());
		bytes.putLong(wish.id().getMostSignificantBits());
		bytes.putLong(wish.id().getLeastSignificantBits());
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
	}

	private static Cursor decodeCursor(String encoded) {
		try {
			byte[] raw = Base64.getUrlDecoder().decode(encoded);
			if (raw.length != Long.BYTES + Integer.BYTES + 2 * Long.BYTES) {
				throw new IllegalArgumentException("wrong cursor size");
			}
			ByteBuffer bytes = ByteBuffer.wrap(raw);
			Instant createdAt = Instant.ofEpochSecond(bytes.getLong(), bytes.getInt());
			UUID id = new UUID(bytes.getLong(), bytes.getLong());
			return new Cursor(createdAt, id);
		} catch (RuntimeException exception) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.MALFORMED_REQUEST,
					"cursor is invalid.",
					"cursor");
		}
	}

	private static String fingerprint(String... values) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String value : values) {
				byte[] bytes = Objects.requireNonNull(value, "fingerprint value")
						.getBytes(StandardCharsets.UTF_8);
				digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
				digest.update(bytes);
			}
			return "sha256:" + HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static WishLifecycleException accountNotFound() {
		return new WishLifecycleException(
				WishLifecycleException.Code.CARD_BALANCE_ACCOUNT_NOT_FOUND,
				"Card Balance Account not found.");
	}

	private static WishLifecycleException wishNotFound() {
		return new WishLifecycleException(
				WishLifecycleException.Code.WISH_NOT_FOUND,
				"Wish not found.");
	}

	private static WishLifecycleException mismatchLocked() {
		return new WishLifecycleException(
				WishLifecycleException.Code.BALANCE_MISMATCH_LOCKED,
				"The account balance must be reconciled before this operation.");
	}

	@Schema(
			name = "WishPage",
			description = "A descending page of visible owned Wishes.",
			example = "{\"items\":[],\"nextCursor\":null}")
	public record WishPage(
			@ArraySchema(
					arraySchema = @Schema(
							description = "Non-deleted owned Wishes in createdAt descending, id descending "
									+ "order.",
							requiredMode = Schema.RequiredMode.REQUIRED),
					schema = @Schema(implementation = WishSnapshot.class))
			List<WishSnapshot> items,
			@Schema(description = "Opaque cursor for the next Wish page; null when no further page exists.",
					nullable = true,
					example = "AAABmQ9SVwAAAAAAAAAAAAAAAAAAAAAB") String nextCursor) {
		public WishPage {
			items = List.copyOf(items);
		}
	}

	public record MutationOutcome(
			WishSnapshot wish, UUID eventId, boolean replayed, int httpStatus) {
	}

	private record Cursor(Instant createdAt, UUID id) {
	}
}
