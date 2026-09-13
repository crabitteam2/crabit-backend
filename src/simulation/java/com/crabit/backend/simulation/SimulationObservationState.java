package com.crabit.backend.simulation;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Committed observation evidence and independent chain/FK checks. Diagnostic, never an importer. */
public final class SimulationObservationState {
    public record Observation(UUID id,UUID accountId,String status,String lookupMethod,Long balance,
        String failureCode,Long lookupVersion,Boolean firstSuccessful,UUID previousId,Long previousBalance,
        UUID changeEventId,Instant observedAt,String sourceKind,String datasetId,String sourceRef) {}
    public record Cash(UUID accountId,long sequence,String kind,long amount,Instant occurredAt) {}
    public record Account(UUID id,long lookupVersion) {}
    public record State(int schemaVersion,String schemaKind,String datasetId,List<Account> accounts,
        List<Observation> observations,List<Cash> cash) {
        public State { accounts=List.copyOf(accounts);observations=List.copyOf(observations);cash=List.copyOf(cash); }
    }
    public record Verification(int accounts,int observations,int succeeded,int failed,int depositLinks,int changeLinks) {}
    private SimulationObservationState() {}
    static State capture(JdbcTemplate jdbc,String dataset) {
        var accounts=jdbc.query("""
            SELECT a.id,a.balance_lookup_version FROM card_balance_account a
            JOIN demo_simulation_account d ON d.account_id=a.id WHERE d.dataset_id=? ORDER BY a.id
            """,(r,n)->new Account(r.getObject(1,UUID.class),r.getLong(2)),dataset);
        var observations=jdbc.query("""
            SELECT o.* FROM balance_observation o JOIN demo_simulation_account a ON a.account_id=o.account_id
            WHERE a.dataset_id=? ORDER BY o.account_id,o.account_lookup_version
            """,(r,n)->new Observation(r.getObject("id",UUID.class),r.getObject("account_id",UUID.class),
            r.getString("status"),r.getString("lookup_method"),r.getObject("actual_card_balance",Long.class),
            r.getString("failure_code"),r.getObject("account_lookup_version",Long.class),r.getObject("first_successful",Boolean.class),
            r.getObject("previous_successful_observation_id",UUID.class),r.getObject("previous_successful_balance",Long.class),
            r.getObject("balance_change_event_id",UUID.class),r.getTimestamp("observed_at").toInstant(),
            r.getString("source_kind"),r.getString("simulation_dataset_id"),r.getString("simulation_source_ref")),dataset);
        var cash=jdbc.query("""
            SELECT account_id,sequence,kind,amount_krw,occurred_at FROM demo_simulation_cash_event
            WHERE dataset_id=? ORDER BY account_id,sequence
            """,(r,n)->new Cash(r.getObject(1,UUID.class),r.getLong(2),r.getString(3),r.getLong(4),r.getTimestamp(5).toInstant()),dataset);
        return new State(1,"simulation-observation-state",dataset,accounts,observations,cash);
    }
    public static Verification verify(State state,SimulationAllocationState.State allocation,
        String dataset,Map<String,UUID> identities,Instant cutoff) {
        check(state.schemaVersion()==1 && state.schemaKind().equals("simulation-observation-state")
            && state.datasetId().equals(dataset) && allocation.datasetId().equals(dataset),"BINDING");
        Map<UUID,Account> accounts=new HashMap<>();
        Set<UUID> expected=new HashSet<>();
        // Only actually joined accounts are captured. The dispatcher supplies their exact set.
        identities.forEach((key,id)->{if(key.startsWith("ACCOUNT:"))expected.add(id);});
        for(Account a:state.accounts())check(a.id()!=null && accounts.putIfAbsent(a.id(),a)==null
            && expected.contains(a.id()) && a.lookupVersion()>=0,"ACCOUNT");
        check(accounts.keySet().equals(expected),"ACCOUNT_SET");
        Map<UUID,Map<Long,Cash>> cash=new HashMap<>();Map<UUID,Map<Long,Long>> balances=new HashMap<>();
        for(Cash c:state.cash()) {
            check(accounts.containsKey(c.accountId()) && c.sequence()>0 && c.amount()>0 && c.amount()<=9007199254740991L
                && Set.of("GRANT","PURCHASE").contains(c.kind()),"CASH");time(c.occurredAt(),cutoff);
            check(cash.computeIfAbsent(c.accountId(),ignored->new TreeMap<>()).putIfAbsent(c.sequence(),c)==null,"CASH_SEQUENCE");
        }
        for(var entry:cash.entrySet()) {
            long seq=0,balance=0;Instant previous=SimulationCashOracle.START;
            var values=new HashMap<Long,Long>();values.put(0L,0L);
            for(Cash c:entry.getValue().values()) {
                check(c.sequence()==++seq && !c.occurredAt().isBefore(previous),"CASH_SEQUENCE");previous=c.occurredAt();
                balance=Math.addExact(balance,c.kind().equals("GRANT")?c.amount():-c.amount());
                check(balance>=0 && balance<=9007199254740991L,"CASH_BALANCE");values.put(seq,balance);
            }
            balances.put(entry.getKey(),values);
        }
        Map<UUID,Observation> observations=new HashMap<>(),lastSuccess=new HashMap<>();
        Map<UUID,Long> versions=new HashMap<>();Map<UUID,Instant> times=new HashMap<>();
        Map<UUID,SimulationAllocationState.Root> roots=new HashMap<>();
        for(var root:allocation.roots())check(roots.putIfAbsent(root.id(),root)==null,"ROOT_DUPLICATE");
        Set<UUID> changes=new HashSet<>();int succeeded=0,failed=0,deposits=0;
        List<Observation> sorted=new ArrayList<>(state.observations());
        check(sorted.stream().allMatch(o->o.id()!=null && o.accountId()!=null && o.lookupVersion()!=null),"OBSERVATION_IDENTITY");
        sorted.sort(Comparator.comparing(Observation::accountId).thenComparing(Observation::lookupVersion));
        for(Observation o:sorted) {
            check(observations.putIfAbsent(o.id(),o)==null && accounts.containsKey(o.accountId()),"OBSERVATION_IDENTITY");time(o.observedAt(),cutoff);
            check(o.lookupVersion()==versions.getOrDefault(o.accountId(),0L)+1,"LOOKUP_SEQUENCE");versions.put(o.accountId(),o.lookupVersion());
            check(!o.observedAt().isBefore(times.getOrDefault(o.accountId(),SimulationCashOracle.START)),"OBSERVATION_TIME");times.put(o.accountId(),o.observedAt());
            check(Set.of("USER_REQUESTED","PRE_DEPOSIT","AUTO_DAILY").contains(o.lookupMethod()),"LOOKUP_METHOD");
            if(o.status().equals("FAILED")) {
                failed++;
                check(o.balance()==null && o.failureCode()!=null && !o.failureCode().isBlank() && o.firstSuccessful()==null
                    && o.previousId()==null && o.previousBalance()==null && o.changeEventId()==null,"FAILED_SHAPE");
                // Existing failure path has no simulated success/provenance claim.
                check(o.sourceKind().equals("PROVIDER") && o.datasetId()==null && o.sourceRef()==null,"FAILED_SOURCE");continue;
            }
            check(o.status().equals("SUCCEEDED") && o.balance()!=null && o.balance()>=0 && o.balance()<=9007199254740991L && o.failureCode()==null,"SUCCESS_SHAPE");succeeded++;
            check(o.sourceKind().equals("SIMULATION") && dataset.equals(o.datasetId()) && o.sourceRef()!=null,"SOURCE");
            String prefix="cash:"+o.accountId()+":";
            check(o.sourceRef().startsWith(prefix) && o.sourceRef().substring(prefix.length()).matches("0|[1-9][0-9]*"),"SOURCE_REF");
            long cashSequence=Long.parseLong(o.sourceRef().substring(prefix.length()));
            Long balance=balances.getOrDefault(o.accountId(),Map.of(0L,0L)).get(cashSequence);
            check(balance!=null && balance.equals(o.balance()),"SOURCE_BALANCE");
            var ledger=cash.getOrDefault(o.accountId(),Map.of());
            if(cashSequence>0)check(!ledger.get(cashSequence).occurredAt().isAfter(o.observedAt()),"SOURCE_TIME");
            Cash next=ledger.get(cashSequence+1);
            // Equal-time commands retain sequence authority; later cash at the same instant is possible.
            check(next==null || !next.occurredAt().isBefore(o.observedAt()),"STALE_SOURCE");
            Observation prior=lastSuccess.get(o.accountId());long priorBalance=prior==null?0:prior.balance();
            check(Objects.equals(o.previousId(),prior==null?null:prior.id()) && Objects.equals(o.previousBalance(),priorBalance)
                && Objects.equals(o.firstSuccessful(),prior==null?Boolean.TRUE:null),"SUCCESS_CHAIN");
            long delta=Math.subtractExact(o.balance(),priorBalance);
            if(delta==0)check(o.changeEventId()==null,"ZERO_CHANGE");
            else {
                var root=roots.get(o.changeEventId());
                check(root!=null && changes.add(root.id()) && root.accountId().equals(o.accountId())
                    && root.kind().equals("CARD_BALANCE_CHANGE") && root.accountDelta()==delta
                    && root.occurredAt().equals(o.observedAt()) && root.correctionOfEventId()==null,"CHANGE_LINK");
            }
            lastSuccess.put(o.accountId(),o);
        }
        for(Account a:accounts.values())check(a.lookupVersion()==versions.getOrDefault(a.id(),0L),"ACCOUNT_LOOKUP_VERSION");
        for(var root:allocation.roots()) {
            if(root.kind().equals("CARD_BALANCE_CHANGE"))check(changes.contains(root.id()),"ORPHAN_CHANGE");
            if(root.depositObservationId()!=null) {
                Observation o=observations.get(root.depositObservationId());
                check(o!=null && o.status().equals("SUCCEEDED") && o.lookupMethod().equals("PRE_DEPOSIT")
                    && o.accountId().equals(root.accountId()) && o.observedAt().equals(root.occurredAt()),"DEPOSIT_LINK");deposits++;
            }
        }
        identities.forEach((key,id)->{if(key.startsWith("BALANCE_OBSERVATION:"))check(observations.containsKey(id),"MISSING_COMMAND_OBSERVATION");});
        return new Verification(accounts.size(),observations.size(),succeeded,failed,deposits,changes.size());
    }
    private static void time(Instant at,Instant cutoff) {
        check(at!=null && !at.isBefore(SimulationCashOracle.START) && at.isBefore(SimulationCashOracle.END) && !at.isAfter(cutoff),"TIME");
    }
    private static void check(boolean condition,String code) { if(!condition)throw new IllegalStateException("OBSERVATION_"+code); }
}
