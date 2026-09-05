package com.crabit.backend.recap;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

class RecapGenerationJobTest {
	private final RecapGenerationCoordinator coordinator = mock(RecapGenerationCoordinator.class);
	private final RecapPythonClient client = mock(RecapPythonClient.class);
	private final RecapSnapshotService snapshots = mock(RecapSnapshotService.class);
	private final CardBalanceAccountRepository accounts = mock(CardBalanceAccountRepository.class);
	private final RecapGenerationJob job = new RecapGenerationJob(coordinator, client, snapshots, accounts);
	private final RecapPeriods.Period period = new RecapPeriods.Period(LocalDate.parse("2026-08-24"),LocalDate.parse("2026-08-31"));

	@Test void transactionAcquisitionFailureIsRecordedAndTheNextPreparationStillRuns() {
		var first = preparation(); var second = preparation();
		var built = new RecapSnapshotService.Snapshot(second.id(),second.studentId(),second.academyId(),"sha256:frozen","{}",0);
		when(coordinator.claimPreparation(any())).thenReturn(Optional.of(first),Optional.of(second),Optional.empty());
		when(coordinator.claim(any())).thenReturn(Optional.empty());
		when(snapshots.build(first.id(),first.accountId(),first.kind(),period)).thenThrow(new CannotCreateTransactionException("private connection detail"));
		when(snapshots.build(second.id(),second.accountId(),second.kind(),period)).thenReturn(built);
		job.runReady();
		verify(coordinator).failPreparation(eq(first),eq(true),any()); verify(coordinator).prepared(eq(second),eq(built),any());
		verifyNoInteractions(client);
	}
	@Test void deterministicSnapshotFailureDoesNotRetryOrReachPython() {
		var claim = preparation();
		when(coordinator.claimPreparation(any())).thenReturn(Optional.of(claim),Optional.empty()); when(coordinator.claim(any())).thenReturn(Optional.empty());
		when(snapshots.build(claim.id(),claim.accountId(),claim.kind(),period)).thenThrow(new IllegalStateException("unserializable snapshot"));
		job.runReady(); verify(coordinator).failPreparation(eq(claim),eq(false),any()); verifyNoInteractions(client);
	}
	@Test void failedReservationBatchRetriesTheSamePeriodOnNextPollAndDoesNotStopOtherAccounts() {
		var first = CardBalanceAccount.open(UUID.randomUUID(),UUID.randomUUID(),Instant.now());
		var second = CardBalanceAccount.open(UUID.randomUUID(),UUID.randomUUID(),Instant.now());
		when(accounts.findByClosedAtIsNullOrderByIdAsc()).thenReturn(List.of(first,second));
		when(coordinator.reserveScheduled(eq(first.id()),eq(RecapKind.WEEKLY),eq(period),any())).thenThrow(new CannotCreateTransactionException("db unavailable")).thenReturn(mock(RecapGeneration.class));
		when(coordinator.claimPreparation(any())).thenReturn(Optional.empty()); when(coordinator.claim(any())).thenReturn(Optional.empty());
		job.reserve(RecapKind.WEEKLY,period); job.runReady(); job.runReady();
		verify(coordinator,times(2)).reserveScheduled(eq(first.id()),eq(RecapKind.WEEKLY),eq(period),any());
		verify(coordinator,times(2)).reserveScheduled(eq(second.id()),eq(RecapKind.WEEKLY),eq(period),any());
		verifyNoInteractions(snapshots,client);
	}
	@Test void protocolFailureIsBoundedAndTheNextGenerationIsProcessed() {
		var first = RecapProtocolValidationTest.claim(RecapKind.WEEKLY); var second = RecapProtocolValidationTest.claim(RecapKind.MONTHLY);
		when(coordinator.claimPreparation(any())).thenReturn(Optional.empty());
		when(coordinator.claim(any())).thenReturn(Optional.of(first),Optional.of(second),Optional.empty());
		when(client.generate(first)).thenThrow(new RecapTransportException("INVALID_RESPONSE",false));
		when(client.generate(second)).thenReturn(new RecapPythonClient.Result("{}","{}"));
		job.runReady(); verify(coordinator).fail(eq(first),eq("INVALID_RESPONSE"),eq(false),any());
		verify(coordinator).succeed(eq(second),eq("{}"),eq("{}"),any());
	}
	private RecapGenerationCoordinator.PreparationClaim preparation() { return new RecapGenerationCoordinator.PreparationClaim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),RecapKind.WEEKLY,period,1); }
}
