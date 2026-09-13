package com.crabit.backend.simulation;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Episode semantics over the exact exported tables after FK and checkpoint checks. */
public final class SimulationAdjustmentVerifier {
    public record Verification(int cases,int links,int openCases,int resolvedCases) {}
    private SimulationAdjustmentVerifier() {}
    public static Verification verify(SimulationRelationalState.Export export) {
        var tables=export.state().tables();int links=0,open=0,resolved=0;
        Set<String> openAccounts=new HashSet<>();
        for(JsonNode episode:tables.get("balance_adjustment_case")) {
            String id=text(episode,"id"),account=text(episode,"account_id");Instant opened=time(episode,"opened_at");
            var observation=one(tables.get("balance_observation"),"id",text(episode,"opening_balance_observation_id"));
            check(text(observation,"account_id").equals(account) && text(observation,"status").equals("SUCCEEDED")
                && time(observation,"observed_at").equals(opened),"OPENING_OBSERVATION");
            var firstCheckpoint=tables.get("historical_balance_checkpoint").stream().filter(c->text(c,"account_id").equals(account)
                && c.hasNonNull("latest_observation_id") && text(c,"latest_observation_id").equals(text(observation,"id")))
                .min(Comparator.comparingLong(c->number(c,"revision"))).orElseThrow(()->new IllegalStateException("ADJUSTMENT_OPENING_CHECKPOINT"));
            long shortage=Math.subtractExact(number(firstCheckpoint,"active_wish_allocation"),number(observation,"actual_card_balance"));
            check(shortage>0 && number(episode,"opened_shortage")==shortage,"OPENING_SHORTAGE");
            var events=tables.get("balance_adjustment_case_event").stream().filter(e->text(e,"adjustment_case_id").equals(id))
                .sorted(Comparator.comparingLong(e->number(e,"sequence_number"))).toList();
            boolean hasOpening=episode.hasNonNull("opening_event_id");
            if(hasOpening) {
                var root=one(tables.get("ledger_event"),"id",text(episode,"opening_event_id"));
                check(text(root,"account_id").equals(account) && text(root,"event_type").equals("CARD_BALANCE_CHANGE")
                    && number(root,"account_delta")<0 && root.get("id").equals(observation.get("balance_change_event_id"))
                    && root.get("event_type").equals(episode.get("opening_event_type"))
                    && number(root,"account_delta")==number(episode,"opening_event_delta")
                    && episode.get("opening_balance_observation_first_successful").isNull(),"OPENING_DECREASE");
                check(!events.isEmpty() && text(events.getFirst(),"event_role").equals("OPENING_DECREASE")
                    && events.getFirst().get("event_id").equals(root.get("id")),"OPENING_LINK");
            } else check(observation.get("first_successful").booleanValue()
                && episode.get("opening_balance_observation_first_successful").booleanValue()
                && episode.get("opening_event_type").isNull() && episode.get("opening_event_delta").isNull(),"EVENTLESS_ORIGIN");
            Instant prior=opened;long priorOrder=0;Set<String> roots=new HashSet<>();
            for(int i=0;i<events.size();i++) {
                var link=events.get(i);links++;
                check(number(link,"sequence_number")==i && text(link,"account_id").equals(account)
                    && roots.add(text(link,"event_id")),"LINK_SEQUENCE");
                var root=one(tables.get("ledger_event"),"id",text(link,"event_id"));
                check(text(root,"account_id").equals(account) && !time(root,"occurred_at").isBefore(prior)
                    && number(root,"application_order")>priorOrder,"LINK_ORDER");
                String expected=i==0 && hasOpening?"OPENING_DECREASE":i==events.size()-1 && text(episode,"status").equals("RESOLVED")?"RESOLUTION":"INTERMEDIATE";
                check(text(link,"event_role").equals(expected),"LINK_ROLE");
                prior=time(root,"occurred_at");priorOrder=number(root,"application_order");
            }
            if(text(episode,"status").equals("OPEN")) {
                open++;check(openAccounts.add(account) && episode.get("resolved_at").isNull() && episode.get("resolution_event_id").isNull(),"OPEN_STATE");
            } else {
                resolved++;check(text(episode,"status").equals("RESOLVED") && episode.hasNonNull("resolved_at")
                    && episode.hasNonNull("resolution_event_id") && !events.isEmpty()
                    && text(events.getLast(),"event_role").equals("RESOLUTION")
                    && events.getLast().get("event_id").equals(episode.get("resolution_event_id"))
                    && !time(episode,"resolved_at").isBefore(prior),"RESOLVED_STATE");
            }
            JsonNode settlement;
            if(text(episode,"status").equals("OPEN")) {
                settlement=tables.get("historical_balance_checkpoint").stream().filter(c->text(c,"account_id").equals(account))
                    .max(Comparator.comparingLong(c->number(c,"revision"))).orElseThrow();
            } else {
                long resolutionOrder=number(one(tables.get("ledger_event"),"id",text(episode,"resolution_event_id")),"application_order");
                settlement=tables.get("historical_balance_checkpoint").stream().filter(c->text(c,"account_id").equals(account)
                    && number(c,"ledger_application_order")>=resolutionOrder)
                    .min(Comparator.comparingLong(c->number(c,"revision"))).orElseThrow(()->new IllegalStateException("ADJUSTMENT_RESOLUTION_CHECKPOINT"));
            }
            check(settlement.hasNonNull("last_successful_observation_id"),"SETTLEMENT_OBSERVATION");
            var settledObservation=one(tables.get("balance_observation"),"id",text(settlement,"last_successful_observation_id"));
            boolean shortageRemains=number(settlement,"active_wish_allocation")>number(settledObservation,"actual_card_balance");
            check(shortageRemains==text(episode,"status").equals("OPEN"),"SETTLEMENT_SHORTAGE");
            var outbox=one(tables.get("mismatch_notification_outbox"),"adjustment_case_id",id);
            check(time(outbox,"created_at").equals(opened),"OUTBOX_ORIGIN");
        }
        check(links==tables.get("balance_adjustment_case_event").size(),"ORPHAN_LINK");
        check(tables.get("mismatch_notification_outbox").size()==tables.get("balance_adjustment_case").size(),"OUTBOX_SET");
        return new Verification(tables.get("balance_adjustment_case").size(),links,open,resolved);
    }
    private static JsonNode one(List<JsonNode> rows,String field,String id) {
        var matches=rows.stream().filter(r->text(r,field).equals(id)).toList();check(matches.size()==1,"REFERENCE");return matches.getFirst();
    }
    private static long number(JsonNode row,String field) {
        JsonNode value=row.get(field);check(value!=null && value.isIntegralNumber() && value.canConvertToLong(),"INTEGER_REQUIRED");return value.longValue();
    }
    private static String text(JsonNode row,String field) { return row.get(field).asString(); }
    private static Instant time(JsonNode row,String field) { return OffsetDateTime.parse(text(row,field)).toInstant(); }
    private static void check(boolean ok,String code) { if(!ok)throw new IllegalStateException("ADJUSTMENT_"+code); }
}
