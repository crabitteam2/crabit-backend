package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecapGenerationCoordinatorTest {
	private final RecapGenerationRepository generations=mock(RecapGenerationRepository.class);
	private final CardBalanceAccountRepository accounts=mock(CardBalanceAccountRepository.class);
	private final RecapGenerationCoordinator coordinator=new RecapGenerationCoordinator(generations,accounts);
	private final UUID account=UUID.randomUUID(),student=UUID.randomUUID(),academy=UUID.randomUUID();
	private final LocalDate start=LocalDate.parse("2026-08-01"),end=LocalDate.parse("2026-09-01");
	private final Instant now=Instant.parse("2026-09-01T00:00:00Z");

	@Test void serializesOnAccountAndReusesTheSameInput(){
		var existing=generation(UUID.randomUUID(),"sha256:same");
		when(accounts.lockById(account)).thenReturn(Optional.of(mock(CardBalanceAccount.class)));
		when(generations.findById(any())).thenReturn(Optional.empty());
		when(generations.lockLogical(account,RecapKind.MONTHLY,start,end)).thenReturn(java.util.List.of(existing));
		assertThat(coordinator.reserve(UUID.randomUUID(),account,student,academy,RecapKind.MONTHLY,start,end,"sha256:same","{}",now)).isSameAs(existing);
		var order=inOrder(accounts,generations); order.verify(accounts).lockById(account); order.verify(generations).findById(any()); order.verify(generations).lockLogical(account,RecapKind.MONTHLY,start,end);
		verify(generations,never()).save(any());
	}

	@Test void rejectsGenerationIdReuseWithDifferentInput(){
		UUID id=UUID.randomUUID(); when(accounts.lockById(account)).thenReturn(Optional.of(mock(CardBalanceAccount.class)));
		when(generations.findById(id)).thenReturn(Optional.of(generation(id,"sha256:old")));
		assertThatThrownBy(()->coordinator.reserve(id,account,student,academy,RecapKind.MONTHLY,start,end,"sha256:new","{}",now)).isInstanceOf(IllegalStateException.class);
		verify(generations,never()).save(any());
	}

	@Test void definitiveMonthlyIneligibilityBecomesCurrentAtomically(){
		var rows=new ArrayList<RecapGeneration>();
		when(accounts.lockById(account)).thenReturn(Optional.of(mock(CardBalanceAccount.class)));
		when(generations.findById(any())).thenReturn(Optional.empty()); when(generations.lockLogical(account,RecapKind.MONTHLY,start,end)).thenReturn(rows);
		when(generations.save(any())).thenAnswer(inv->{RecapGeneration g=inv.getArgument(0);rows.add(g);return g;});
		var result=coordinator.reserveNotEligible(UUID.randomUUID(),account,student,academy,RecapKind.MONTHLY,start,end,"sha256:x","{}",now);
		assertThat(result.state()).isEqualTo(RecapGenerationState.NOT_ELIGIBLE); assertThat(result.currentVersion()).isTrue();
	}

	@Test void reclaimsAStaleRunningGenerationWithTheFrozenRequestAndBoundedAttempt(){
		var generation=generation(UUID.randomUUID(),"sha256:x"); generation.start(now.minusSeconds(600));
		when(generations.lockReady(now,now.minus(RecapGenerationCoordinator.RUNNING_LEASE))).thenReturn(java.util.List.of(generation));
		var claim=coordinator.claim(now).orElseThrow();
		assertThat(claim.requestJson()).isEqualTo("{}"); assertThat(claim.attempt()).isEqualTo(2);
		assertThat(generation.state()).isEqualTo(RecapGenerationState.RUNNING);
	}

	@Test void ignoresCompletionFromAClaimThatLostItsLease(){
		when(accounts.lockById(account)).thenReturn(Optional.of(mock(CardBalanceAccount.class)));
		var generation=generation(UUID.randomUUID(),"sha256:x"); generation.start(now.minusSeconds(600)); generation.start(now);
		var stale=new RecapGenerationCoordinator.Claim(generation.id(),account,student,academy,RecapKind.MONTHLY,"sha256:x","{}",1);
		when(generations.findByIdAndInputDigest(generation.id(),"sha256:x")).thenReturn(Optional.of(generation));
		when(generations.lockLogical(account,RecapKind.MONTHLY,start,end)).thenReturn(java.util.List.of(generation));
		coordinator.succeed(stale,"{}","{}",now);
		assertThat(generation.state()).isEqualTo(RecapGenerationState.RUNNING);
	}

	private RecapGeneration generation(UUID id,String digest){return new RecapGeneration(id,account,student,academy,RecapKind.MONTHLY,start,end,1,digest,"{}",now);}
}
