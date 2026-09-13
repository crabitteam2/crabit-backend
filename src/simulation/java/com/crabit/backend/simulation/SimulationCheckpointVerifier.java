package com.crabit.backend.simulation;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Financial checks over preserved checkpoint JSON; never queries or repairs the database. */
public final class SimulationCheckpointVerifier {
    public record Verification(int accounts,int checkpoints,int ledgerApplications,int activeWishFacts) {}
    private SimulationCheckpointVerifier() {}
    public static Verification verify(SimulationRelationalState.Export export) {
        var tables=export.state().tables();
        var roots=index(tables.get("ledger_event"),"id");
        var observations=index(tables.get("balance_observation"),"id");
        var wishes=index(tables.get("wish"),"id");
        var applications=index(tables.get("historical_ledger_application"),"event_id");
        check(applications.keySet().equals(roots.keySet()),"APPLICATION_SET");
        for(var entry:applications.entrySet()) {
            var application=entry.getValue();var root=roots.get(entry.getKey());
            check(text(application,"account_id").equals(text(root,"account_id"))
                && number(application,"application_order")==number(root,"application_order"),"APPLICATION_BINDING");
            check(time(application,"applied_at").equals(time(root,"occurred_at")),"APPLICATION_TIME");
        }
        Map<String,NavigableMap<Long,Long>> wishAmounts=new HashMap<>();
        for(JsonNode effect:tables.get("ledger_wish_effect")) {
            var root=roots.get(text(effect,"event_id"));check(root!=null,"EFFECT_REFERENCE");
            wishAmounts.computeIfAbsent(text(effect,"wish_id"),ignored->new TreeMap<>())
                .merge(number(root,"application_order"),number(effect,"wish_delta"),Math::addExact);
        }
        for(var amounts:wishAmounts.values()) {
            long sum=0;for(var entry:amounts.entrySet()) { sum=Math.addExact(sum,entry.getValue());entry.setValue(sum); }
        }
        int checkpoints=0,facts=0;
        for(JsonNode account:tables.get("card_balance_account")) {
            String id=text(account,"id");
            var rows=tables.get("historical_balance_checkpoint").stream().filter(r->text(r,"account_id").equals(id))
                .sorted(Comparator.comparingLong(r->number(r,"revision"))).toList();
            check(!rows.isEmpty(),"ACCOUNT_BASELINE");long revision=0,order=0,lookup=0;Instant previous=time(account,"opened_at");
            for(JsonNode row:rows) {
                checkpoints++;revision++;
                check(number(row,"revision")==revision && row.get("is_baseline").booleanValue()==(revision==1),"REVISION_CHAIN");
                Instant applied=time(row,"applied_at");long watermark=number(row,"ledger_application_order");
                check(!applied.isBefore(previous) && watermark>=order,"WATERMARK_ORDER");
                if(revision==1)check(applied.equals(time(account,"opened_at")) && watermark==0
                    && row.get("active_wishes").isEmpty() && row.get("latest_observation_id").isNull(),"BASELINE_STATE");
                if(watermark>0)check(applications.values().stream().anyMatch(a->text(a,"account_id").equals(id)
                    && number(a,"application_order")==watermark),"WATERMARK_REFERENCE");
                for(JsonNode application:applications.values())if(text(application,"account_id").equals(id)
                    && number(application,"application_order")<=watermark)
                    check(!time(application,"applied_at").isAfter(applied),"APPLICATION_AFTER_CHECKPOINT");
                long version=0;
                if(row.hasNonNull("latest_observation_id")) {
                    var latest=observations.get(text(row,"latest_observation_id"));check(latest!=null,"OBSERVATION_REFERENCE");
                    version=number(latest,"account_lookup_version");
                    check(text(latest,"account_id").equals(id) && row.hasNonNull("observation_lookup_version")
                        && number(row,"observation_lookup_version")==version && version>=lookup
                        && !time(latest,"observed_at").isAfter(applied),"OBSERVATION_BINDING");
                    final long bound=version;
                    var success=observations.values().stream().filter(o->text(o,"account_id").equals(id)
                        && text(o,"status").equals("SUCCEEDED") && number(o,"account_lookup_version")<=bound)
                        .max(Comparator.comparingLong(o->number(o,"account_lookup_version"))).orElse(null);
                    check(success==null ? row.get("last_successful_observation_id").isNull()
                        : row.hasNonNull("last_successful_observation_id") && text(row,"last_successful_observation_id").equals(text(success,"id")),"SUCCESS_CHAIN");
                } else check(row.get("observation_lookup_version").isNull() && row.get("last_successful_observation_id").isNull() && lookup==0,"OBSERVATION_NULLS");
                var snapshot=row.get("active_wishes");check(snapshot.isArray(),"ACTIVE_SHAPE");
                Map<String,JsonNode> active=new HashMap<>();long total=0;
                for(JsonNode fact:snapshot) {
                    facts++;
                    check(fact.isObject() && new HashSet<>(fact.propertyNames()).equals(Set.of("wishId","state","targetAmount","amount")),"ACTIVE_SHAPE");
                    String wishId=text(fact,"wishId");var wish=wishes.get(wishId);
                    check(wish!=null && text(wish,"account_id").equals(id) && !time(wish,"created_at").isAfter(applied)
                        && active.put(wishId,fact)==null,"ACTIVE_REFERENCE");
                    long amount=number(fact,"amount"),target=number(fact,"targetAmount");
                    check(target>0 && target<=9007199254740991L && amount>=0 && amount<=target
                        && Set.of("IN_PROGRESS","AMOUNT_REACHED").contains(text(fact,"state"))
                        && text(fact,"state").equals(amount==target?"AMOUNT_REACHED":"IN_PROGRESS"),"ACTIVE_STATE");
                    var amounts=wishAmounts.get(wishId);
                    var ledger=amounts==null?null:amounts.floorEntry(watermark);
                    long ledgerAmount=ledger==null?0:ledger.getValue();
                    check(amount==ledgerAmount,"ACTIVE_LEDGER_AMOUNT");total=Math.addExact(total,amount);
                }
                check(total==number(row,"active_wish_allocation"),"ALLOCATION_TOTAL");
                if(row.hasNonNull("representative_wish_id")) {
                    var representative=active.get(text(row,"representative_wish_id"));
                    check(representative!=null && representative.get("state").equals(row.get("representative_state"))
                        && number(representative,"amount")==number(row,"representative_amount")
                        && number(representative,"targetAmount")==number(row,"representative_target_amount"),"REPRESENTATIVE_BINDING");
                } else check(row.get("representative_state").isNull() && row.get("representative_amount").isNull()
                    && row.get("representative_target_amount").isNull(),"REPRESENTATIVE_NULLS");
                previous=applied;order=watermark;lookup=version;
            }
            var last=rows.getLast();
            long latestOrder=roots.values().stream().filter(r->text(r,"account_id").equals(id)).mapToLong(r->number(r,"application_order")).max().orElse(0);
            long latestLookup=observations.values().stream().filter(r->text(r,"account_id").equals(id)).mapToLong(r->number(r,"account_lookup_version")).max().orElse(0);
            check(order==latestOrder && lookup==latestLookup,"LATEST_WATERMARK");
            var finalActive=index(last.get("active_wishes"),"wishId");
            Set<String> expected=new HashSet<>();
            for(JsonNode wish:wishes.values())if(text(wish,"account_id").equals(id) && wish.get("deleted_at").isNull()
                && Set.of("IN_PROGRESS","AMOUNT_REACHED").contains(text(wish,"state"))) {
                String wishId=text(wish,"id");expected.add(wishId);var fact=finalActive.get(wishId);
                check(fact!=null && text(fact,"state").equals(text(wish,"state"))
                    && number(fact,"amount")==number(wish,"wish_amount") && number(fact,"targetAmount")==number(wish,"target_amount"),"LATEST_WISH_STATE");
            }
            check(finalActive.keySet().equals(expected),"LATEST_ACTIVE_SET");
            var selection=tables.get("representative_wish_selection").stream().filter(r->text(r,"account_id").equals(id)).toList();
            check(selection.isEmpty()?last.get("representative_wish_id").isNull():selection.size()==1
                && selection.getFirst().get("wish_id").equals(last.get("representative_wish_id")),"LATEST_REPRESENTATIVE");
        }
        return new Verification(tables.get("card_balance_account").size(),checkpoints,applications.size(),facts);
    }
    private static Map<String,JsonNode> index(Iterable<JsonNode> rows,String field) {
        Map<String,JsonNode> index=new HashMap<>();for(JsonNode row:rows)check(index.put(text(row,field),row)==null,"DUPLICATE_IDENTITY");return index;
    }
    private static String text(JsonNode row,String field) { return row.get(field).asString(); }
    private static long number(JsonNode row,String field) {
        JsonNode value=row.get(field);check(value!=null && value.isIntegralNumber() && value.canConvertToLong(),"INTEGER_REQUIRED");return value.longValue();
    }
    private static Instant time(JsonNode row,String field) { return OffsetDateTime.parse(text(row,field)).toInstant(); }
    private static void check(boolean ok,String code) { if(!ok)throw new IllegalStateException("CHECKPOINT_"+code); }
}
