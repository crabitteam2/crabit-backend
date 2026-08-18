package com.crabit.backend.wish;

import com.crabit.backend.balance.CardBalanceSyncFailedException;
import com.crabit.backend.balance.PreDepositBalanceService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Public orchestration boundary for Wish fund movements.
 *
 * <p>Deposit intentionally has no surrounding transaction: PRE_DEPOSIT observation success or
 * failure must commit before the allocation transaction starts.</p>
 */
@Service
public class WishFundMovementService {

	static final String DEPOSIT = "DEPOSIT";
	static final String WITHDRAW = "WITHDRAW";
	static final String TRANSFER = "TRANSFER";

	private final WishFundMovementTransactionService transactions;
	private final PreDepositBalanceService preDepositBalances;

	public WishFundMovementService(
			WishFundMovementTransactionService transactions,
			PreDepositBalanceService preDepositBalances) {
		this.transactions = transactions;
		this.preDepositBalances = preDepositBalances;
	}

	public MutationOutcome deposit(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			UUID wishId,
			String idempotencyKey,
			long amount,
			long expectedVersion) {
		String key = requireIdempotencyKey(idempotencyKey);
		KrwAmount movement = positiveAmount(amount, "amount");
		requireNonNegativeVersion(expectedVersion, "expectedVersion");
		String fingerprint = fingerprint(DEPOSIT, accountId.toString(), wishId.toString(),
				Long.toString(amount), Long.toString(expectedVersion));
		Optional<MutationOutcome> replay = transactions.preflightDeposit(
				studentId, academyId, accountId, wishId, key, fingerprint, expectedVersion);
		if (replay.isPresent()) {
			return replay.orElseThrow();
		}

		DepositBalanceProof proof;
		try {
			proof = preDepositBalances.prepare(accountId);
		} catch (CardBalanceSyncFailedException exception) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.BALANCE_SYNC_FAILED,
					"Card balance could not be refreshed.");
		}
		return transactions.deposit(studentId, academyId, accountId, wishId, key,
				fingerprint, movement, expectedVersion, proof);
	}

	public MutationOutcome withdraw(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			UUID wishId,
			String idempotencyKey,
			long amount,
			long expectedVersion) {
		String key = requireIdempotencyKey(idempotencyKey);
		KrwAmount movement = positiveAmount(amount, "amount");
		requireNonNegativeVersion(expectedVersion, "expectedVersion");
		String fingerprint = fingerprint(WITHDRAW, accountId.toString(), wishId.toString(),
				Long.toString(amount), Long.toString(expectedVersion));
		return transactions.withdraw(studentId, academyId, accountId, wishId, key,
				fingerprint, movement, expectedVersion);
	}

	public TransferOutcome transfer(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			String idempotencyKey,
			UUID sourceWishId,
			UUID destinationWishId,
			long amount,
			long sourceExpectedVersion,
			long destinationExpectedVersion) {
		String key = requireIdempotencyKey(idempotencyKey);
		KrwAmount movement = positiveAmount(amount, "amount");
		requireNonNegativeVersion(sourceExpectedVersion, "sourceExpectedVersion");
		requireNonNegativeVersion(destinationExpectedVersion, "destinationExpectedVersion");
		String fingerprint = fingerprint(TRANSFER, accountId.toString(),
				Objects.requireNonNull(sourceWishId, "sourceWishId").toString(),
				Objects.requireNonNull(destinationWishId, "destinationWishId").toString(),
				Long.toString(amount), Long.toString(sourceExpectedVersion),
				Long.toString(destinationExpectedVersion));
		return transactions.transfer(studentId, academyId, accountId, key, fingerprint,
				sourceWishId, destinationWishId, movement, sourceExpectedVersion,
				destinationExpectedVersion);
	}

	private static String requireIdempotencyKey(String key) {
		if (key == null || key.isBlank()) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.IDEMPOTENCY_KEY_REQUIRED,
					"Idempotency-Key is required.", "Idempotency-Key");
		}
		if (key.length() > 200) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.MALFORMED_REQUEST,
					"Idempotency-Key must contain at most 200 characters.",
					"Idempotency-Key");
		}
		return key;
	}

	private static KrwAmount positiveAmount(long amount, String field) {
		try {
			return KrwAmount.positive(amount);
		} catch (IllegalArgumentException exception) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_AMOUNT,
					field + " must be a positive JavaScript-safe integer.", field);
		}
	}

	private static void requireNonNegativeVersion(long version, String field) {
		if (version < 0) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.INVALID_VERSION,
					field + " must be non-negative.", field);
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

	public record MutationOutcome(
			WishSnapshot wish, UUID eventId, boolean replayed, int httpStatus) {
	}

	public record TransferOutcome(
			WishSnapshot sourceWish,
			WishSnapshot destinationWish,
			UUID eventId,
			Instant occurredAt,
			boolean replayed,
			int httpStatus) {
	}
}
