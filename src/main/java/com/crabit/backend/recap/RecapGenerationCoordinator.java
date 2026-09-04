package com.crabit.backend.recap;

import com.crabit.backend.account.CardBalanceAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecapGenerationCoordinator {
	private final RecapGenerationRepository generations;
	private final CardBalanceAccountRepository accounts;
	public RecapGenerationCoordinator(RecapGenerationRepository generations, CardBalanceAccountRepository accounts) {
		this.generations=generations; this.accounts=accounts;
	}
	@Transactional
	public RecapGeneration reserve(UUID id, UUID account, UUID student, UUID academy, RecapKind kind,
			LocalDate start, LocalDate end, String inputDigest, String requestJson, Instant now) {
		accounts.lockById(account).orElseThrow();
		var byId=generations.findById(id);
		if(byId.isPresent() && !byId.get().inputDigest().equals(inputDigest)) throw new IllegalStateException("Generation id is already bound to another input");
		var rows=generations.lockLogical(account, kind, start, end);
		for(var row:rows) if(row.inputDigest().equals(inputDigest)) return row;
		long version=rows.isEmpty()?1:rows.getFirst().generationVersion()+1;
		return generations.save(new RecapGeneration(id,account,student,academy,kind,start,end,version,inputDigest,requestJson,now));
	}
	@Transactional
	public RecapGeneration reserveNotEligible(UUID id, UUID account, UUID student, UUID academy, RecapKind kind,
			LocalDate start, LocalDate end, String inputDigest, String requestJson, Instant now) {
		RecapGeneration generation=reserve(id,account,student,academy,kind,start,end,inputDigest,requestJson,now);
		if(generation.state()==RecapGenerationState.PENDING){
			for(var row:generations.lockLogical(account,kind,start,end)) if(row.currentVersion()) row.supersede();
			generation.notEligible(now); generation.makeCurrent();
		}
		return generation;
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<Claim> claim(Instant now) {
		var ready=generations.lockReady(now); if(ready.isEmpty()) return Optional.empty(); var g=ready.getFirst();
		if(g.attemptCount()>=3) { g.fail("RETRY_EXHAUSTED",false,now,null); return Optional.empty(); }
		g.start(now); return Optional.of(new Claim(g.id(),g.accountId(),g.studentId(),g.academyId(),g.kind(),g.inputDigest(),g.requestJson(),g.attemptCount()));
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void succeed(Claim claim, String view, String metrics, Instant now) {
		var g=generations.findByIdAndInputDigest(claim.id(),claim.inputDigest()).orElseThrow();
		var rows=generations.lockLogical(g.accountId(),g.kind(),g.periodStart(),g.periodEndExclusive());
		for(var row:rows) if(row.currentVersion()) row.supersede();
		g.succeed(view,metrics,now);g.makeCurrent();
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(Claim claim, String code, boolean retryable, Instant now) {
		var g=generations.findByIdAndInputDigest(claim.id(),claim.inputDigest()).orElseThrow();
		boolean retry=retryable && g.attemptCount()<3; long delay=1L << Math.max(0,g.attemptCount()-1);
		g.fail(code,retry,now,retry?now.plusSeconds(delay*60):null);
	}
	public record Claim(UUID id, UUID accountId, UUID studentId, UUID academyId, RecapKind kind,
			String inputDigest, String requestJson, int attempt) {}
}
