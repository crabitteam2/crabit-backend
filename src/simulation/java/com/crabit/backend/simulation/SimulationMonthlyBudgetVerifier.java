package com.crabit.backend.simulation;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Monthly policy over command-to-ledger reconciled cash, without changing grants or allocations. */
public final class SimulationMonthlyBudgetVerifier {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final long MINIMUM=10000, MAXIMUM=30000;
    public record Month(String accountId,String budgetMonth,String coverage,long grantedKrw,int appliedGrants) {}
    public record Verification(Instant observedThrough,int completeMonths,int partialMonths,List<Month> months) {
        public Verification { months=List.copyOf(months); }
    }
    private record Key(String account,YearMonth month) {}
    private SimulationMonthlyBudgetVerifier() {}

    /**
     * Includes only enrolled accounts. observedThrough is a checked replay frontier, inclusive;
     * month completeness requires the exclusive month end to be at or before that frontier.
     * Failed/rejected attempts never count. Scheduled month labels do not pull future grants forward.
     */
    public static Verification verify(String dataset,JsonNode people,List<JsonNode> events,JsonNode cash,Instant observedThrough) {
        check(observedThrough!=null && !observedThrough.isBefore(SimulationCashOracle.START)
            && !observedThrough.isAfter(SimulationCashOracle.END),"FRONTIER");
        Map<String,JsonNode> enrolled=new TreeMap<>();
        for(JsonNode person:people) {
            String account=text(person,"logicalAccountId");
            check(enrolled.putIfAbsent(account,person)==null,"DUPLICATE_ACCOUNT");
            check(!time(person,"joinedAt").isAfter(observedThrough),"FUTURE_ENROLLMENT");
        }
        for(JsonNode event:events)check(!time(event,"occurredAt").isAfter(observedThrough),"FUTURE_EVENT");
        // Establish that each accepted grant is present exactly once in the actual cash export.
        var manifest=tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("datasetId",dataset);
        new SimulationBundleCashVerifier().verify(manifest,people,events,cash);
        Map<String,JsonNode> grants=new HashMap<>();
        for(JsonNode event:events)if(text(event,"kind").equals("GRANT") && text(event.get("outcome"),"status").equals("APPLIED"))
            check(grants.putIfAbsent(text(event,"eventId"),event)==null,"DUPLICATE_GRANT");
        Map<Key,Long> totals=new HashMap<>();Map<Key,Integer> counts=new HashMap<>();
        for(JsonNode entry:cash.get("ledger"))if(text(entry,"kind").equals("GRANT")) {
            JsonNode grant=grants.remove(text(entry,"eventId"));check(grant!=null,"UNBOUND_GRANT");
            String account=text(entry,"accountId");
            check(enrolled.containsKey(account),"UNKNOWN_ACCOUNT");
            check(text(enrolled.get(account),"logicalStudentId").equals(text(grant,"actorStudentId")),"GRANT_ACTOR");
            // The checked ledger timestamp is the actual receipt; budgetMonth remains schedule evidence.
            YearMonth month=YearMonth.from(time(entry,"occurredAt").atZone(SEOUL));
            Key key=new Key(account,month);
            totals.merge(key,entry.get("amountKrw").longValue(),Math::addExact);
            counts.merge(key,1,Math::addExact);
        }
        check(grants.isEmpty(),"MISSING_GRANT");
        List<Month> rows=new ArrayList<>();int complete=0,partial=0;
        for(var person:enrolled.entrySet()) {
            Instant joined=time(person.getValue(),"joinedAt");
            YearMonth first=YearMonth.from(joined.atZone(SEOUL));
            for(YearMonth month=first;;month=month.plusMonths(1)) {
                Instant start=month.atDay(1).atStartOfDay(SEOUL).toInstant();
                if(start.isAfter(observedThrough) || !start.isBefore(SimulationCashOracle.END))break;
                Instant end=month.plusMonths(1).atDay(1).atStartOfDay(SEOUL).toInstant();
                boolean full=!joined.isAfter(start) && !end.isAfter(observedThrough);
                Key key=new Key(person.getKey(),month);long total=totals.getOrDefault(key,0L);
                // Joining and unfinished months report actual receipts; no invented proration.
                if(full) { check(total>=MINIMUM && total<=MAXIMUM,"COMPLETE_MONTH_RANGE:"+person.getKey()+":"+month);complete++; }
                else partial++;
                rows.add(new Month(person.getKey(),month.toString(),full?"COMPLETE":"PARTIAL",total,counts.getOrDefault(key,0)));
                totals.remove(key);
            }
        }
        check(totals.isEmpty(),"GRANT_OUTSIDE_ENROLLMENT");
        return new Verification(observedThrough,complete,partial,rows);
    }
    private static String text(JsonNode n,String key) { return n.get(key).asString(); }
    private static Instant time(JsonNode n,String key) { return Instant.parse(text(n,key)); }
    private static void check(boolean ok,String rule) { if(!ok)throw new IllegalStateException("MONTHLY_BUDGET_"+rule); }
}
