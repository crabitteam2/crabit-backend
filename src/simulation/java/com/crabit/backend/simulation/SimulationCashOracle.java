package com.crabit.backend.simulation;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/** Independent cash verification; never invokes production services or mutates a database.
 * Opening funding is an explicit GRANT, matching V21's zero-funded initial account.
 * Allocation operations cannot change cash and are absent from this command projection.
 */
public final class SimulationCashOracle {
    public static final long MAX_WON = 9_007_199_254_740_991L;
    public static final Instant START = Instant.parse("2026-05-31T15:00:00Z");
    public static final Instant END = Instant.parse("2026-09-10T15:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public enum Kind { GRANT, PURCHASE }
    public enum Outcome { APPLIED, REJECTED, FAILED }
    public record Account(String id, Instant joinedAt) {}
    public record Command(String eventId, long sequence, String accountId, Instant occurredAt,
                          Kind kind, long amountKrw, String cashEntryId, Outcome outcome,
                          String budgetMonth, Instant scheduledAt) {}
    public record Entry(String id, String eventId, String accountId, long sequence,
                        Instant occurredAt, Kind kind, long amountKrw, long balanceAfter) {}
    public record Balance(String accountId, long amountKrw, long sequence) {}
    public record Result(Map<String, Balance> balances, Map<String, Map<YearMonth, Long>> grants,
                         int applied, int rejected, int failed) {
        public Result {
            balances = Map.copyOf(balances);
            Map<String,Map<YearMonth,Long>> copy = new TreeMap<>();
            grants.forEach((key,value)->copy.put(key,Map.copyOf(value)));
            grants = Map.copyOf(copy);
        }
    }
    public static final class Violation extends IllegalArgumentException {
        private final String rule;
        private final String logicalId;
        Violation(String rule, String logicalId) {
            super("INVARIANT_VIOLATION: " + rule); this.rule=rule; this.logicalId=logicalId;
        }
        public String rule() { return rule; }
        public String logicalId() { return logicalId; }
    }

    public Result verify(List<Account> accounts, List<Command> commands, List<Entry> ledger,
                         List<Balance> exportedBalances) {
        require(accounts != null && commands != null && ledger != null && exportedBalances != null,
            "REQUIRED_INPUT", null);
        Map<String,Account> members = new HashMap<>();
        Map<String,Balance> balances = new TreeMap<>();
        Map<String,Map<YearMonth,Long>> grants = new TreeMap<>();
        for (Account a : accounts) {
            require(a != null,"ACCOUNT_REQUIRED",null); id(a.id()); instant(a.joinedAt(),a.id());
            require(!a.joinedAt().isBefore(START) && a.joinedAt().isBefore(END),"ENROLLMENT_RANGE",a.id());
            require(members.putIfAbsent(a.id(),a)==null,"ACCOUNT_DUPLICATE",a.id());
            balances.put(a.id(),new Balance(a.id(),0,0)); grants.put(a.id(),new TreeMap<>());
        }
        require(!members.isEmpty(),"ACCOUNTS_REQUIRED",null);
        Map<String,Entry> byEvent = new HashMap<>(); Set<String> entryIds = new HashSet<>();
        for (Entry e : ledger) {
            require(e != null,"ENTRY_REQUIRED",null); id(e.id()); id(e.eventId()); id(e.accountId());
            require(entryIds.add(e.id()) && byEvent.putIfAbsent(e.eventId(),e)==null,"LEDGER_DUPLICATE",e.eventId());
        }
        Set<String> eventIds = new HashSet<>(), commandEntryIds = new HashSet<>();
        long previousSequence=0; Instant previousTime=START; int applied=0,rejected=0,failed=0,ledgerIndex=0;
        for (Command c : commands) {
            require(c != null,"COMMAND_REQUIRED",null); id(c.eventId()); id(c.accountId()); id(c.cashEntryId());
            String event=c.eventId();
            require(eventIds.add(event),"EVENT_DUPLICATE",event);
            require(commandEntryIds.add(c.cashEntryId()),"CASH_ENTRY_DUPLICATE",event);
            require(c.sequence()>previousSequence && c.sequence()<=MAX_WON,"EVENT_SEQUENCE",event);
            instant(c.occurredAt(),event);
            require(!c.occurredAt().isBefore(previousTime) && c.occurredAt().isBefore(END),"EVENT_TIME",event);
            Account a=members.get(c.accountId());
            require(a!=null && !c.occurredAt().isBefore(a.joinedAt()),"ACTOR_ENROLLMENT",event);
            require(c.kind()!=null && c.outcome()!=null,"COMMAND_VARIANT",event);
            require(c.amountKrw()>0 && c.amountKrw()<=MAX_WON,"AMOUNT_RANGE",event);
            if (c.kind()==Kind.GRANT) {
                instant(c.scheduledAt(),event);
                require(!c.scheduledAt().isAfter(c.occurredAt()) && !c.scheduledAt().isBefore(a.joinedAt()),"GRANT_SCHEDULE",event);
                YearMonth month;
                try { month=YearMonth.parse(c.budgetMonth()); }
                catch (RuntimeException ex) { throw new Violation("GRANT_BUDGET_MONTH",event); }
                require(c.budgetMonth().matches("[0-9]{4}-[0-9]{2}") && month.equals(YearMonth.from(c.scheduledAt().atZone(SEOUL))),"GRANT_BUDGET_MONTH",event);
            } else require(c.budgetMonth()==null && c.scheduledAt()==null,"PURCHASE_VARIANT",event);
            previousSequence=c.sequence(); previousTime=c.occurredAt();
            Entry e=byEvent.remove(event);
            if (c.outcome()!=Outcome.APPLIED) {
                require(e==null,"FAILED_COMMAND_HAS_LEDGER",event);
                if(c.outcome()==Outcome.REJECTED)rejected++; else failed++;
                continue;
            }
            require(e!=null,"APPLIED_COMMAND_MISSING_LEDGER",event);
            require(ledger.get(ledgerIndex++).equals(e),"LEDGER_CAUSAL_ORDER",event);
            Balance old=balances.get(c.accountId());
            // Two bounded safe integers fit in long even before the output bound check.
            long next=c.kind()==Kind.GRANT ? old.amountKrw()+c.amountKrw() : old.amountKrw()-c.amountKrw();
            require(next>=0 && next<=MAX_WON,"CASH_RANGE",event);
            long sequence=old.sequence()+1;
            require(e.id().equals(c.cashEntryId()) && e.accountId().equals(c.accountId())
                && Objects.equals(e.occurredAt(),c.occurredAt()) && e.kind()==c.kind()
                && e.amountKrw()==c.amountKrw() && e.sequence()==sequence && e.balanceAfter()==next,
                "LEDGER_COMMAND_MISMATCH",event);
            balances.put(c.accountId(),new Balance(c.accountId(),next,sequence));
            if(c.kind()==Kind.GRANT) {
                // Budget attribution follows realized receipt time; scheduled month remains immutable evidence.
                var months=grants.get(c.accountId()); YearMonth month=YearMonth.from(c.occurredAt().atZone(SEOUL));
                long total=months.getOrDefault(month,0L)+c.amountKrw();
                require(total<=MAX_WON,"MONTHLY_GRANT_OVERFLOW",event); months.put(month,total);
            }
            applied++;
        }
        require(byEvent.isEmpty(),"ORPHAN_LEDGER",null);
        Map<String,Balance> exported=new HashMap<>();
        for(Balance b:exportedBalances) {
            require(b!=null,"BALANCE_REQUIRED",null); id(b.accountId());
            require(exported.putIfAbsent(b.accountId(),b)==null,"BALANCE_DUPLICATE",b.accountId());
        }
        require(exported.equals(balances),"FINAL_BALANCE_MISMATCH",null);
        return new Result(balances,grants,applied,rejected,failed);
    }
    /** Caller must independently establish active, fully observed membership for this month. */
    public void verifyFullMonthBudget(Result result, String accountId, YearMonth month) {
        require(result!=null && month!=null && result.grants().containsKey(accountId),"BUDGET_ACCOUNT",accountId);
        require(!month.atDay(1).atStartOfDay(SEOUL).toInstant().isBefore(START)
            && !month.plusMonths(1).atDay(1).atStartOfDay(SEOUL).toInstant().isAfter(END),"BUDGET_PARTIAL_MONTH",accountId);
        long amount=result.grants().get(accountId).getOrDefault(month,0L);
        require(amount>=10_000 && amount<=30_000,"MONTHLY_GRANT_BUDGET",accountId);
    }
    private static void id(String value) {
        require(value!=null && value.matches("[A-Za-z0-9:_-]{1,160}"),"LOGICAL_ID",null);
    }
    private static void instant(Instant value,String id) {
        require(value!=null && value.getNano()%1000==0,"INSTANT_MICROSECONDS",id);
    }
    private static void require(boolean ok,String rule,String id) {
        if(!ok) throw new Violation(rule,id);
    }
}
