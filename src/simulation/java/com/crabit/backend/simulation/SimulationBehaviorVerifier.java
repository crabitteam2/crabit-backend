package com.crabit.backend.simulation;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Independent comparison of retained behavior with executed logical commands. No DB access or repair. */
public final class SimulationBehaviorVerifier {
    private SimulationBehaviorVerifier() {}
    public record Verification(int contexts,int items,int events,int impressions,int visits,int clicksWithoutPriorExposure,
                               SimulationBehaviorAccessVerifier.Verification access) {}
    private record Pair(String actor,String id) {}

    public static Verification verify(SimulationRelationalState.Export export,List<JsonNode> commands,Map<String,UUID> identities) {
        var tables=export.state().tables();
        Map<String,JsonNode> contexts=new HashMap<>();
        for(JsonNode row:tables.get("behavior_result_context"))check(contexts.put(s(row,"id"),row)==null,"DUPLICATE_CONTEXT");
        Map<Pair,JsonNode> events=new HashMap<>(),impressions=new HashMap<>(),visits=new HashMap<>();
        for(JsonNode row:tables.get("behavior_event"))check(events.put(pair(row,"event_id"),row)==null,"DUPLICATE_EVENT");
        for(JsonNode row:tables.get("behavior_impression"))check(impressions.put(pair(row,"impression_id"),row)==null,"DUPLICATE_IMPRESSION");
        for(JsonNode row:tables.get("feed_visit_evidence"))check(visits.put(pair(row,"event_id"),row)==null,"DUPLICATE_VISIT");
        Map<String,SortedMap<Long,String>> items=new HashMap<>();
        for(JsonNode row:tables.get("behavior_result_item")) {
            String context=s(row,"context_id");long position=integer(row,"position");
            check(contexts.containsKey(context) && position>=0 && position<100,"RESULT_POSITION");
            check(items.computeIfAbsent(context,k->new TreeMap<>()).put(position,s(row,"card_id"))==null,"RESULT_POSITION");
        }
        // Card IDs are retained provenance, including cards since made private or deleted.
        Map<String,String> cardWishes=new HashMap<>(),wishAccounts=new HashMap<>(),accountOwners=new HashMap<>();
        for(JsonNode history:tables.get("feed_source_history")) {
            JsonNode p=history.get("payload");String kind=s(history,"source_kind");
            if(kind.equals("shared_card"))immutable(cardWishes,s(p,"id"),s(p,"wish_id"));
            if(kind.equals("wish"))immutable(wishAccounts,s(p,"id"),s(p,"account_id"));
            if(kind.equals("card_balance_account"))immutable(accountOwners,s(p,"id"),s(p,"student_id"));
        }
        Set<String> expectedContexts=new HashSet<>();Set<Pair> expectedEvents=new HashSet<>(),expectedVisits=new HashSet<>(),usedImpressions=new HashSet<>();
        Map<Pair,String> exposed=new HashMap<>();Map<Pair,String> logicalImpressions=new HashMap<>();
        Map<Pair,String> physicalImpressions=new HashMap<>();int unmatched=0;
        for(JsonNode event:commands) {
            if(!s(event.get("outcome"),"status").equals("APPLIED"))continue;
            String kind=s(event,"kind");
            if(!Set.of("FEED_QUERY","PROFILE_VISIT","CLICK","IMPRESSION").contains(kind))continue;
            JsonNode c=event.get("command");String actor=id(identities,"STUDENT",s(event,"actorStudentId"));
            String academy=id(identities,"ACADEMY",s(c,"academyId"));Instant when=time(event,"occurredAt");
            if(kind.equals("FEED_QUERY")) {
                String context=id(identities,"FEED_CONTEXT",s(c,"resultContextId"));
                check(expectedContexts.add(context),"CONTEXT_REUSED");JsonNode row=contexts.get(context);check(row!=null,"MISSING_CONTEXT");
                check(s(row,"actor_id").equals(actor) && s(row,"academy_id").equals(academy) && time(row,"created_at").equals(when),"CONTEXT_COMMAND");
                SortedMap<Long,String> page=items.getOrDefault(context,new TreeMap<>());
                check(page.size()==c.get("orderedCardIds").size(),"RESULT_ITEMS");int position=0;Set<String> distinct=new HashSet<>();
                for(JsonNode logical:c.get("orderedCardIds")) {
                    String card=id(identities,"SHARED_CARD",logical.asString());
                    check(card.equals(page.get((long)position++)) && distinct.add(card) && cardWishes.containsKey(card),"RESULT_ITEMS");
                }
                continue;
            }
            Pair key=new Pair(actor,id(identities,"BEHAVIOR_EVENT",s(event,"eventId")));check(expectedEvents.add(key),"EVENT_REUSED");
            JsonNode row=events.get(key);check(row!=null,"MISSING_EVENT");
            String type=kind.equals("CLICK")?"FEED_CLICK":kind.equals("IMPRESSION")?"FEED_EXPOSURE":"PROFILE_VISIT";
            check(s(row,"event_type").equals(type) && s(row,"academy_id").equals(academy)
                && time(row,"occurred_at").equals(when) && time(row,"received_at").equals(when),"EVENT_COMMAND");
            check(!s(row,"target_id").equals(actor),"SELF_TARGET");
            if(kind.equals("PROFILE_VISIT")) {
                check(s(row,"target_id").equals(id(identities,"STUDENT",s(c,"targetStudentId"))),"VISIT_TARGET");
                for(String field:List.of("context_id","card_id","position","impression_id","click_kind"))check(row.get(field).isNull(),"VISIT_SHAPE");
                expectedVisits.add(key);JsonNode visit=visits.get(key);check(visit!=null,"MISSING_VISIT_EVIDENCE");
                check(s(visit,"target_author_id").equals(s(row,"target_id")) && s(visit,"academy_id").equals(academy)
                    && time(visit,"occurred_at").equals(when) && time(visit,"received_at").equals(when)
                    && time(visit,"captured_at").equals(when),"VISIT_EVIDENCE_BINDING");
                verifyVisit(visit,tables.get("feed_source_history"),when);
                continue;
            }
            check(s(row,"context_id").equals(id(identities,"FEED_CONTEXT",s(c,"resultContextId")))
                && s(row,"card_id").equals(id(identities,"SHARED_CARD",s(c,"cardId")))
                && integer(row,"position")==integer(c,"position"),"FEED_EVENT_COMMAND");
            check(kind.equals("CLICK")?s(row,"click_kind").equals(s(c,"clickKind")):row.get("click_kind").isNull(),"CLICK_KIND");
            String owner=accountOwners.get(wishAccounts.get(cardWishes.get(s(row,"card_id"))));
            check(owner!=null && owner.equals(s(row,"target_id")),"CARD_AUTHOR");
            JsonNode context=contexts.get(s(row,"context_id"));check(context!=null,"MISSING_CONTEXT");
            check(when.isBefore(time(context,"created_at").plus(Duration.ofHours(24)))
                && !when.isBefore(time(context,"created_at")),"CONTEXT_ACCEPTANCE_TIME");
            Pair impressionKey=pair(row,"impression_id");JsonNode impression=impressions.get(impressionKey);check(impression!=null,"MISSING_IMPRESSION");
            usedImpressions.add(impressionKey);
            for(String field:List.of("context_id","academy_id","card_id","position"))check(row.get(field).equals(impression.get(field)),"IMPRESSION_BINDING");
            Pair logical=new Pair(actor,s(c,"impressionId"));
            immutable(logicalImpressions,logical,impressionKey.id());immutable(physicalImpressions,impressionKey,logical.id());
            if(kind.equals("IMPRESSION"))check(exposed.put(impressionKey,key.id())==null,"DUPLICATE_EXPOSURE");
            else if(!exposed.containsKey(impressionKey))unmatched++;
        }
        check(contexts.keySet().equals(expectedContexts),"CONTEXT_SET");check(events.keySet().equals(expectedEvents),"EVENT_SET");
        check(visits.keySet().equals(expectedVisits),"VISIT_EVIDENCE_SET");check(impressions.keySet().equals(usedImpressions),"IMPRESSION_SET");
        for(var entry:impressions.entrySet()) {
            JsonNode saved=entry.getValue().get("exposed_event_id");String exposure=exposed.get(entry.getKey());
            check(exposure==null?saved.isNull():saved.isString() && saved.asString().equals(exposure),"EXPOSED_EVENT_LINK");
        }
        var access=SimulationBehaviorAccessVerifier.verify(export,commands,identities);
        return new Verification(contexts.size(),tables.get("behavior_result_item").size(),events.size(),impressions.size(),visits.size(),unmatched,access);
    }
    private static void verifyVisit(JsonNode visit,List<JsonNode> history,Instant when) {
        // Fixed synchronous replay has neither a legacy baseline nor a future business timestamp.
        check(s(visit,"evidence_version").equals("feed-visit-evidence-v1") && s(visit,"evidence_status").equals("COMPLETE")
            && visit.get("unknown_reason").isNull() && time(visit,"history_coverage_start").equals(SimulationCashOracle.START),"VISIT_EVIDENCE_STATUS");
        check(s(visit,"classifier_version").matches("wish-category-v1@sha256:[0-9a-f]{64}"),"CLASSIFIER_VERSION");
        JsonNode categories=visit.get("category_ids");check(categories.isArray(),"VISIT_CATEGORIES");String previous=null;
        for(JsonNode category:categories) {
            check(category.isString() && !category.asString().isBlank() && (previous==null || previous.compareTo(category.asString())<0),"VISIT_CATEGORIES");previous=category.asString();
        }
        Map<Long,JsonNode> versions=new HashMap<>();long maximum=0;
        for(JsonNode row:history) { long version=integer(row,"version");versions.put(version,row);maximum=Math.max(maximum,version); }
        JsonNode refs=visit.get("source_versions");check(refs.isArray(),"VISIT_SOURCES");Set<String> seen=new HashSet<>();long high=-1;boolean baseline=false;
        for(JsonNode ref:refs) {
            check(ref.isString() && seen.add(ref.asString()),"VISIT_SOURCES");String value=ref.asString();
            if(value.equals("baseline:"+SimulationCashOracle.START)) { baseline=true;continue; }
            String[] parts=value.split(":",2);check(parts.length==2 && parts[1].matches("[0-9]+"),"VISIT_SOURCES");
            long version;try {version=Long.parseLong(parts[1]);}catch(NumberFormatException failure){throw new IllegalStateException("BEHAVIOR_VISIT_SOURCES");}
            if(parts[0].equals("history")) { check(high==-1 && version<=maximum,"VISIT_HIGH_WATER");high=version;continue; }
            String expected=switch(parts[0]) {case "wish"->"wish";case "card"->"shared_card";case "account"->"card_balance_account";default->"";};
            JsonNode row=versions.get(version);check(row!=null && s(row,"source_kind").equals(expected),"VISIT_SOURCE_REFERENCE");
            check(!time(row,"valid_from").isAfter(when) && (row.get("valid_to").isNull() || !time(row,"valid_to").isBefore(when)),"VISIT_SOURCE_TIME");
        }
        check(baseline && high>=0,"VISIT_SOURCES");
        if(high>0)check(versions.containsKey(high) && !time(versions.get(high),"valid_from").isAfter(when),"VISIT_HIGH_WATER");
        Map<String,JsonNode> sourceWishes=new HashMap<>(),sourceCards=new HashMap<>(),sourceAccounts=new HashMap<>();
        for(String ref:seen) {
            if(ref.startsWith("wish:")||ref.startsWith("card:")||ref.startsWith("account:")) {
                JsonNode payload=versions.get(Long.parseLong(ref.substring(ref.indexOf(':')+1))).get("payload");
                (ref.startsWith("wish:")?sourceWishes:ref.startsWith("card:")?sourceCards:sourceAccounts).put(s(payload,"id"),payload);
            }
        }
        for(JsonNode account:sourceAccounts.values())check(s(account,"student_id").equals(s(visit,"target_author_id"))
            && s(account,"academy_id").equals(s(visit,"academy_id")),"VISIT_SOURCE_OWNER");
        for(JsonNode wish:sourceWishes.values())check(sourceAccounts.containsKey(s(wish,"account_id"))
            && s(wish,"academy_id").equals(s(visit,"academy_id"))
            && sourceCards.values().stream().anyMatch(card->s(card,"wish_id").equals(s(wish,"id"))),"VISIT_SOURCE_GRAPH");
        for(JsonNode card:sourceCards.values())check(sourceWishes.containsKey(s(card,"wish_id")),"VISIT_SOURCE_GRAPH");
        for(String account:sourceAccounts.keySet())check(sourceWishes.values().stream().anyMatch(wish->s(wish,"account_id").equals(account)),"VISIT_SOURCE_GRAPH");
        for(String ref:seen)if(ref.startsWith("wish:")||ref.startsWith("card:")||ref.startsWith("account:"))
            check(Long.parseLong(ref.substring(ref.indexOf(':')+1))<=high,"VISIT_HIGH_WATER");
    }
    private static <K> void immutable(Map<K,String> map,K key,String value) { String old=map.putIfAbsent(key,value);check(old==null||old.equals(value),"IDENTITY_CHANGED"); }
    private static Pair pair(JsonNode row,String column) {return new Pair(s(row,"actor_id"),s(row,column));}
    private static String id(Map<String,UUID> ids,String kind,String logical) {UUID id=ids.get(kind+":"+logical);check(id!=null,"IDENTITY_MISSING");return id.toString();}
    private static String s(JsonNode row,String key) {JsonNode value=row.get(key);check(value!=null&&value.isString(),"STRING_REQUIRED");return value.asString();}
    private static long integer(JsonNode row,String key) {JsonNode value=row.get(key);check(value!=null&&value.isIntegralNumber()&&value.canConvertToLong(),"INTEGER_REQUIRED");return value.longValue();}
    private static Instant time(JsonNode row,String key) {return OffsetDateTime.parse(s(row,key)).toInstant();}
    private static void check(boolean condition,String code) {if(!condition)throw new IllegalStateException("BEHAVIOR_"+code);}
}
