package com.crabit.backend.wish;

import static com.crabit.backend.wish.WishFundMovementService.DEPOSIT;
import static com.crabit.backend.wish.WishFundMovementService.TRANSFER;
import static com.crabit.backend.wish.WishFundMovementService.WITHDRAW;

import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.account.StudentRepository;
import com.crabit.backend.wish.WishFundMovementService.MutationOutcome;
import com.crabit.backend.wish.WishFundMovementService.TransferOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crabit.backend.wishphoto.WishPhotoService;

@Service
class WishFundMovementTransactionService {

	private final CardBalanceAccountRepository accountRepository;
	private final StudentRepository studentRepository;
	private final WishRepository wishRepository;
	private final WishIdempotencyRepository idempotencyRepository;
	private final WishMoneyCommandService moneyCommands;
	private final BalanceAdjustmentPolicy adjustmentPolicy;
	private final WishPhotoService photos;
	private final Clock clock;

	WishFundMovementTransactionService(
			CardBalanceAccountRepository accountRepository,
			StudentRepository studentRepository,
			WishRepository wishRepository,
			WishIdempotencyRepository idempotencyRepository,
			WishMoneyCommandService moneyCommands,
			BalanceAdjustmentPolicy adjustmentPolicy,
			Optional<WishPhotoService> photos,
			Clock clock) {
		this.accountRepository = accountRepository;
		this.studentRepository = studentRepository;
		this.wishRepository = wishRepository;
		this.idempotencyRepository = idempotencyRepository;
		this.moneyCommands = moneyCommands;
		this.adjustmentPolicy = adjustmentPolicy;
		this.photos = photos.orElse(null);
		this.clock = clock;
	}

	@Transactional
	Optional<MutationOutcome> preflightDeposit(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			UUID wishId,
			String key,
			String fingerprint,
			long expectedVersion) {
		lockOwnedAccount(studentId, academyId, accountId);
		lockStudentNamespace(studentId);
		Optional<WishIdempotencyRecord> prior = prior(studentId, key);
		if (prior.isPresent()) {
			return Optional.of(replayMutation(
					prior.orElseThrow(), DEPOSIT, wishId, fingerprint));
		}
		try {
			adjustmentPolicy.requireAllowed(
					accountId, BalanceAdjustmentPolicy.Operation.DEPOSIT);
		} catch (IllegalStateException exception) {
			throw mismatchLocked();
		}
		Wish wish = lockWish(accountId, wishId);
		requireExpectedVersion(expectedVersion, wish, "expectedVersion");
		if (!wish.isActive()) {
			throw invalidState();
		}
		return Optional.empty();
	}

	@Transactional
	MutationOutcome deposit(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			UUID wishId,
			String key,
			String fingerprint,
			KrwAmount amount,
			long expectedVersion,
			DepositBalanceProof proof) {
		lockOwnedAccount(studentId, academyId, accountId);
		lockStudentNamespace(studentId);
		Optional<WishIdempotencyRecord> prior = prior(studentId, key);
		if (prior.isPresent()) {
			return replayMutation(prior.orElseThrow(), DEPOSIT, wishId, fingerprint);
		}
		Instant occurredAt = clock.instant();
		WishMoneyCommandResult result;
		try {
			result = moneyCommands.deposit(
					accountId, wishId, amount, expectedVersion, proof, occurredAt);
		} catch (WishLifecycleException exception) {
			throw exception;
		} catch (IllegalStateException | IllegalArgumentException exception) {
			throw depositFailure(exception);
		}
		wishRepository.flush();
		WishSnapshot snapshot = snapshot(lockWish(accountId, wishId), adjustmentPolicy.isOpen(accountId));
		UUID eventId = result.ledgerEvent().orElseThrow().id();
		idempotencyRepository.saveAndFlush(studentId, key, WishIdempotencyRecord.capture(
				DEPOSIT, wishId, fingerprint, 200, snapshot, eventId, occurredAt));
		return new MutationOutcome(snapshot, eventId, false, 200);
	}

	@Transactional
	MutationOutcome withdraw(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			UUID wishId,
			String key,
			String fingerprint,
			KrwAmount amount,
			long expectedVersion) {
		lockOwnedAccount(studentId, academyId, accountId);
		lockStudentNamespace(studentId);
		Optional<WishIdempotencyRecord> prior = prior(studentId, key);
		if (prior.isPresent()) {
			return replayMutation(prior.orElseThrow(), WITHDRAW, wishId, fingerprint);
		}
		Instant occurredAt = clock.instant();
		WishMoneyCommandResult result;
		try {
			result = moneyCommands.withdraw(
					accountId, wishId, amount, expectedVersion, occurredAt);
		} catch (WishLifecycleException exception) {
			throw exception;
		} catch (IllegalStateException | IllegalArgumentException exception) {
			throw withdrawalFailure(exception);
		}
		wishRepository.flush();
		WishSnapshot snapshot = snapshot(lockWish(accountId, wishId), adjustmentPolicy.isOpen(accountId));
		UUID eventId = result.ledgerEvent().orElseThrow().id();
		idempotencyRepository.saveAndFlush(studentId, key, WishIdempotencyRecord.capture(
				WITHDRAW, wishId, fingerprint, 200, snapshot, eventId, occurredAt));
		return new MutationOutcome(snapshot, eventId, false, 200);
	}

	@Transactional
	TransferOutcome transfer(
			UUID studentId,
			UUID academyId,
			UUID accountId,
			String key,
			String fingerprint,
			UUID sourceWishId,
			UUID destinationWishId,
			KrwAmount amount,
			long sourceExpectedVersion,
			long destinationExpectedVersion) {
		lockOwnedAccount(studentId, academyId, accountId);
		lockStudentNamespace(studentId);
		Optional<WishIdempotencyRecord> prior = prior(studentId, key);
		if (prior.isPresent()) {
			return replayTransfer(prior.orElseThrow(), accountId, fingerprint);
		}
		Instant occurredAt = clock.instant();
		WishMoneyCommandResult result;
		try {
			result = moneyCommands.transfer(accountId, sourceWishId, destinationWishId,
					amount, sourceExpectedVersion, destinationExpectedVersion, occurredAt);
		} catch (WishLifecycleException exception) {
			throw exception;
		} catch (IllegalStateException | IllegalArgumentException exception) {
			throw transferFailure(exception, accountId, sourceWishId, destinationWishId);
		}
		wishRepository.flush();
		boolean adjustmentOpen = adjustmentPolicy.isOpen(accountId);
		WishSnapshot source = snapshot(lockWish(accountId, sourceWishId), adjustmentOpen);
		WishSnapshot destination = snapshot(lockWish(accountId, destinationWishId), adjustmentOpen);
		LedgerEvent event = result.ledgerEvent().orElseThrow();
		idempotencyRepository.saveAndFlush(studentId, key,
				WishIdempotencyRecord.captureTransfer(TRANSFER, accountId, fingerprint, 200,
						source, destination, event.id(), event.occurredAt(), occurredAt));
		return new TransferOutcome(
				source, destination, event.id(), event.occurredAt(), false, 200);
	}

	private void lockOwnedAccount(UUID studentId, UUID academyId, UUID accountId) {
		accountRepository.lockOwnedActive(accountId, studentId, academyId)
				.orElseThrow(WishFundMovementTransactionService::accountNotFound);
	}

	private void lockStudentNamespace(UUID studentId) {
		studentRepository.lockById(studentId)
				.orElseThrow(WishFundMovementTransactionService::accountNotFound);
	}

	private Wish lockWish(UUID accountId, UUID wishId) {
		return wishRepository.lockVisibleByAccountIdAndId(accountId, wishId)
				.orElseThrow(WishFundMovementTransactionService::wishNotFound);
	}

	private Optional<WishIdempotencyRecord> prior(UUID studentId, String key) {
		return idempotencyRepository.findByStudentIdAndIdempotencyKey(studentId, key);
	}

	private MutationOutcome replayMutation(
			WishIdempotencyRecord record,
			String operation,
			UUID targetId,
			String fingerprint) {
		requireReplayMatch(record, operation, targetId, fingerprint);
		return new MutationOutcome(record.snapshot().withPhoto(attachedView(record.snapshot().id())),
				record.eventId(), true, record.httpStatus());
	}

	private TransferOutcome replayTransfer(
			WishIdempotencyRecord record, UUID accountId, String fingerprint) {
		requireReplayMatch(record, TRANSFER, accountId, fingerprint);
		if (record.destinationSnapshot() == null || record.eventId() == null
				|| record.occurredAt() == null) {
			throw idempotencyReused();
		}
		return new TransferOutcome(record.snapshot().withPhoto(attachedView(record.snapshot().id())),
				record.destinationSnapshot().withPhoto(
						attachedView(record.destinationSnapshot().id())),
				record.eventId(), record.occurredAt(), true, record.httpStatus());
	}

	private WishSnapshot snapshot(Wish wish, boolean adjustmentOpen) {
		return WishSnapshot.from(wish, adjustmentOpen, attachedView(wish.id()));
	}

	private com.crabit.backend.wishphoto.WishPhotoView attachedView(UUID wishId) {
		return photos == null ? null : photos.attachedView(wishId);
	}

	private static void requireReplayMatch(
			WishIdempotencyRecord record,
			String operation,
			UUID targetId,
			String fingerprint) {
		if (!record.matches(operation, targetId, fingerprint)) {
			throw idempotencyReused();
		}
	}

	private WishLifecycleException transferFailure(
			RuntimeException exception,
			UUID accountId,
			UUID sourceWishId,
			UUID destinationWishId) {
		String message = message(exception);
		if (message.contains("Every Wish")) {
			return classifyTransferScope(accountId, sourceWishId, destinationWishId);
		}
		if (message.contains("balance adjustment")) {
			return mismatchLocked();
		}
		if (message.contains("source Wish amount")) {
			return new WishLifecycleException(
					WishLifecycleException.Code.INSUFFICIENT_WISH_AMOUNT,
					"The source Wish does not contain enough funds.");
		}
		if (message.contains("destination Wish target")) {
			return new WishLifecycleException(
					WishLifecycleException.Code.TARGET_AMOUNT_EXCEEDED,
					"The transfer exceeds the destination Wish target.");
		}
		return invalidState();
	}

	private WishLifecycleException classifyTransferScope(
			UUID accountId, UUID sourceWishId, UUID destinationWishId) {
		for (UUID wishId : new UUID[] {sourceWishId, destinationWishId}) {
			Optional<Wish> candidate = wishRepository.findById(wishId);
			if (candidate.isEmpty() || candidate.orElseThrow().isDeleted()) {
				return wishNotFound();
			}
			if (!candidate.orElseThrow().accountId().equals(accountId)) {
				return new WishLifecycleException(
						WishLifecycleException.Code.CROSS_ACCOUNT_TRANSFER_FORBIDDEN,
						"Wish transfers must stay within one Card Balance Account.");
			}
		}
		return wishNotFound();
	}

	private static WishLifecycleException depositFailure(RuntimeException exception) {
		String message = message(exception);
		if (message.contains("Every Wish")) {
			return wishNotFound();
		}
		if (message.contains("balance adjustment")) {
			return mismatchLocked();
		}
		if (message.contains("available balance")) {
			return new WishLifecycleException(
					WishLifecycleException.Code.INSUFFICIENT_AVAILABLE_BALANCE,
					"The deposit exceeds display available balance.");
		}
		if (message.contains("Wish target")) {
			return new WishLifecycleException(
					WishLifecycleException.Code.TARGET_AMOUNT_EXCEEDED,
					"The deposit exceeds the Wish target.");
		}
		if (message.contains("observation") || message.contains("balance proof")) {
			return new WishLifecycleException(
					WishLifecycleException.Code.BALANCE_SYNC_FAILED,
					"Card balance could not be refreshed.");
		}
		return invalidState();
	}

	private static WishLifecycleException withdrawalFailure(RuntimeException exception) {
		String message = message(exception);
		if (message.contains("Every Wish")) {
			return wishNotFound();
		}
		if (message.contains("Wish amount")) {
			return new WishLifecycleException(
					WishLifecycleException.Code.INSUFFICIENT_WISH_AMOUNT,
					"The Wish does not contain enough funds.");
		}
		return invalidState();
	}

	private static void requireExpectedVersion(long expected, Wish wish, String field) {
		if (wish.version() != expected) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.VERSION_CONFLICT,
					"The supplied Wish version is stale.", field);
		}
	}

	private static String message(RuntimeException exception) {
		return exception.getMessage() == null ? "" : exception.getMessage();
	}

	private static WishLifecycleException accountNotFound() {
		return new WishLifecycleException(
				WishLifecycleException.Code.CARD_BALANCE_ACCOUNT_NOT_FOUND,
				"Card Balance Account not found.");
	}

	private static WishLifecycleException wishNotFound() {
		return new WishLifecycleException(
				WishLifecycleException.Code.WISH_NOT_FOUND, "Wish not found.");
	}

	private static WishLifecycleException idempotencyReused() {
		return new WishLifecycleException(
				WishLifecycleException.Code.IDEMPOTENCY_KEY_REUSED,
				"Idempotency-Key was already used for a different request.");
	}

	private static WishLifecycleException invalidState() {
		return new WishLifecycleException(
				WishLifecycleException.Code.INVALID_STATE_TRANSITION,
				"The Wish cannot be changed from its current state.");
	}

	private static WishLifecycleException mismatchLocked() {
		return new WishLifecycleException(
				WishLifecycleException.Code.BALANCE_MISMATCH_LOCKED,
				"The account balance must be reconciled before this operation.",
				null, Map.of("adjustmentStatus", "OPEN"));
	}
}
