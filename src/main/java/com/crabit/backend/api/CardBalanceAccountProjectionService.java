package com.crabit.backend.api;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.wish.BalanceAdjustmentPolicy;
import com.crabit.backend.wish.BalanceBreakdown;
import com.crabit.backend.wish.BalanceObservation;
import com.crabit.backend.wish.BalanceObservationRepository;
import com.crabit.backend.wish.BalanceObservationStatus;
import com.crabit.backend.wish.KrwAmount;
import com.crabit.backend.wish.Wish;
import com.crabit.backend.wish.WishRepository;
import com.crabit.backend.wish.WishState;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardBalanceAccountProjectionService {

	private static final List<WishState> ACTIVE_STATES =
			List.of(WishState.IN_PROGRESS, WishState.AMOUNT_REACHED);

	private final CardBalanceAccountRepository accounts;
	private final BalanceObservationRepository observations;
	private final WishRepository wishes;
	private final BalanceAdjustmentPolicy adjustmentPolicy;

	public CardBalanceAccountProjectionService(
			CardBalanceAccountRepository accounts,
			BalanceObservationRepository observations,
			WishRepository wishes,
			BalanceAdjustmentPolicy adjustmentPolicy) {
		this.accounts = accounts;
		this.observations = observations;
		this.wishes = wishes;
		this.adjustmentPolicy = adjustmentPolicy;
	}

	@Transactional(readOnly = true)
	public CardBalanceAccountPage listOwned(UUID studentId, UUID academyId) {
		List<AccountSnapshot> items = accounts
				.findByStudentIdAndAcademyIdAndClosedAtIsNullOrderByIdAsc(studentId, academyId)
				.stream()
				.map(this::projectCurrent)
				.toList();
		return new CardBalanceAccountPage(items, null);
	}

	@Transactional(readOnly = true)
	public KnownCardBalanceAccount projectSuccessful(
			CardBalanceAccount account, BalanceObservation successfulObservation) {
		return known(account, successfulObservation,
				adjustmentPolicy.isOpen(account.id()), "SUCCESS");
	}

	private AccountSnapshot projectCurrent(CardBalanceAccount account) {
		Optional<BalanceObservation> latestAttempt = observations
				.findFirstByAccountIdAndAccountLookupVersionIsNotNullOrderByAccountLookupVersionDesc(
						account.id());
		Optional<BalanceObservation> latestSuccess = observations
				.findFirstByAccountIdAndStatusAndAccountLookupVersionIsNotNullOrderByAccountLookupVersionDesc(
						account.id(), BalanceObservationStatus.SUCCEEDED);
		if (latestSuccess.isEmpty()) {
			String refreshStatus = latestAttempt
					.filter(attempt -> attempt.status() == BalanceObservationStatus.FAILED)
					.map(ignored -> "FAILED")
					.orElse(null);
			return new UnknownCardBalanceAccount(
					account.id(), account.academyId(), "UNKNOWN",
					null, null, null, null, false, refreshStatus, null);
		}
		String refreshStatus = latestAttempt
				.map(BalanceObservation::status)
				.map(status -> status == BalanceObservationStatus.SUCCEEDED ? "SUCCESS" : "FAILED")
				.orElse("SUCCESS");
		return known(account, latestSuccess.orElseThrow(),
				adjustmentPolicy.isOpen(account.id()), refreshStatus);
	}

	private KnownCardBalanceAccount known(
			CardBalanceAccount account,
			BalanceObservation successfulObservation,
			boolean adjustmentOpen,
			String refreshStatus) {
		KrwAmount activeWishTotal = wishes
				.findByAccountIdAndDeletedAtIsNullAndStateIn(account.id(), ACTIVE_STATES)
				.stream()
				.map(Wish::amount)
				.reduce(KrwAmount.zero(), KrwAmount::plus);
		BalanceBreakdown balance = BalanceBreakdown.calculate(
				successfulObservation.actualCardBalance(), activeWishTotal);
		return new KnownCardBalanceAccount(
				account.id(), account.academyId(), "KNOWN",
				balance.actualCardBalance().won(), balance.ledgerAvailable().won(),
				balance.displayAvailable().won(), balance.unresolvedShortage().won(),
				adjustmentOpen, refreshStatus, successfulObservation.observedAt());
	}

	@Schema(
			name = "CardBalanceAccount",
			oneOf = {UnknownCardBalanceAccount.class, KnownCardBalanceAccount.class},
			discriminatorProperty = "balanceKnowledge",
			discriminatorMapping = {
				@DiscriminatorMapping(value = "UNKNOWN", schema = UnknownCardBalanceAccount.class),
				@DiscriminatorMapping(value = "KNOWN", schema = KnownCardBalanceAccount.class)
			})
	public sealed interface AccountSnapshot
			permits UnknownCardBalanceAccount, KnownCardBalanceAccount {
	}

	@Schema(
			name = "UnknownCardBalanceAccount",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record UnknownCardBalanceAccount(
			UUID cardBalanceAccountId,
			UUID academyId,
			@Schema(allowableValues = "UNKNOWN") String balanceKnowledge,
			Long actualCardBalance,
			Long ledgerAvailableBalance,
			Long displayAvailableBalance,
			Long unresolvedShortage,
			@Schema(allowableValues = "false") boolean balanceAdjustmentInProgress,
			String lastRefreshStatus,
			Instant lastRefreshedAt) implements AccountSnapshot {
	}

	@Schema(
			name = "KnownCardBalanceAccount",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record KnownCardBalanceAccount(
			UUID cardBalanceAccountId,
			UUID academyId,
			@Schema(allowableValues = "KNOWN") String balanceKnowledge,
			long actualCardBalance,
			long ledgerAvailableBalance,
			long displayAvailableBalance,
			long unresolvedShortage,
			@Schema(description = "True iff an account-scoped Balance Adjustment Case is OPEN at "
					+ "response read time; false for RESOLVED-only history. A later failed lookup "
					+ "retains the latest successful amounts while this flag reflects the current case.")
			boolean balanceAdjustmentInProgress,
			String lastRefreshStatus,
			Instant lastRefreshedAt) implements AccountSnapshot {
	}

	@Schema(
			name = "CardBalanceAccountPage",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record CardBalanceAccountPage(
			@ArraySchema(schema = @Schema(implementation = AccountSnapshot.class))
			List<AccountSnapshot> items,
			@Schema(nullable = true) String nextCursor) {
		public CardBalanceAccountPage {
			items = List.copyOf(items);
		}
	}
}
