package com.crabit.backend.simulation;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reconciles candidate-author metrics from frozen original rows, without production metric helpers. */
public final class SimulationRecapAuthorVerifier {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private SimulationRecapAuthorVerifier() {}
    public record Verification(int authorsVerified) {}

    public static Verification verify(JsonNode request,JsonNode source) {
        JsonNode peers=source.get("peer_source");
        String academy=str(request,"academy_id");Instant cutoff=time(request,"snapshot_at");
        Map<String,JsonNode> wishes=index(peers.get("wish")),accounts=index(peers.get("accounts"));
        int count=0;
        for(JsonNode candidate:request.get("input").get("success_story_candidates")) {
            JsonNode wish=wishes.get(str(candidate,"wish_id"));check(wish!=null,"WISH");
            JsonNode account=accounts.get(str(wish,"account_id"));
            check(account!=null && str(account,"academy_id").equals(academy),"AUTHOR");
            String owner=str(account,"student_id"),accountId=str(account,"id");
            LocalDate month=time(wish,"completed_at").atZone(SEOUL).toLocalDate().withDayOfMonth(1).minusMonths(1);
            Instant start=month.atStartOfDay(SEOUL).toInstant(),end=month.plusMonths(1).atStartOfDay(SEOUL).toInstant();
            Instant middle=month.plusDays(15).atStartOfDay(SEOUL).toInstant();
            Map<String,JsonNode> events=new HashMap<>();Set<String> parents=new HashSet<>();
            for(JsonNode event:peers.get("ledger_event"))if(str(event,"account_id").equals(accountId)) {
                check(time(event,"occurred_at").isBefore(cutoff),"FUTURE_LEDGER");
                check(events.put(str(event,"id"),event)==null,"DUPLICATE_EVENT");
                if(nonnull(event,"correction_of_event_id"))check(parents.add(str(event,"correction_of_event_id")),"BRANCHED_CORRECTION");
            }
            Map<String,String> roots=new HashMap<>();
            for(var entry:events.entrySet()) {
                JsonNode cursor=entry.getValue();Set<String> seen=new HashSet<>();
                while(nonnull(cursor,"correction_of_event_id")) {
                    check(seen.add(str(cursor,"id")),"CYCLIC_CORRECTION");
                    JsonNode parent=events.get(str(cursor,"correction_of_event_id"));check(parent!=null,"MISSING_CORRECTION");
                    check(!time(parent,"occurred_at").isAfter(time(cursor,"occurred_at")),"CORRECTION_TIME");cursor=parent;
                }
                roots.put(entry.getKey(),str(cursor,"id"));
            }
            Map<String,Map<String,Long>> sums=new HashMap<>();Set<String> pairs=new HashSet<>();
            for(JsonNode effect:peers.get("ledger_wish_effect"))if(str(effect,"account_id").equals(accountId)) {
                String event=str(effect,"event_id"),id=str(effect,"wish_id");
                check(roots.containsKey(event) && pairs.add(event+":"+id),"EFFECT_EVENT");
                check(wishes.containsKey(id) && str(wishes.get(id),"account_id").equals(accountId),"EFFECT_OWNER");
                check(effect.get("wish_delta").isIntegralNumber(),"EFFECT_AMOUNT");
                sums.computeIfAbsent(roots.get(event),ignored->new HashMap<>()).merge(id,effect.get("wish_delta").asLong(),Math::addExact);
            }
            long deposits=0,net=0,first=0,transfers=0,abandons=0,visits=0;Set<LocalDate> days=new TreeSet<>();
            for(var entry:sums.entrySet()) {
                JsonNode root=events.get(entry.getKey());Instant at=time(root,"occurred_at");
                if(!within(at,start,end))continue;
                String type=str(root,"event_type");
                for(long delta:entry.getValue().values()) {
                    if(type.equals("WISH_TRANSFER")) {if(delta<0)transfers++;continue;}
                    if(!Set.of("WISH_DEPOSIT","WISH_WITHDRAWAL").contains(type))continue;
                    if(delta>0){deposits++;days.add(at.atZone(SEOUL).toLocalDate());}
                    net=Math.addExact(net,delta);if(at.isBefore(middle))first=Math.addExact(first,delta);
                }
            }
            for(JsonNode w:peers.get("wish"))if(str(w,"account_id").equals(accountId)
                && str(w,"state").equals("ABANDONED") && nonnull(w,"abandoned_at") && within(time(w,"abandoned_at"),start,end))abandons++;
            for(JsonNode visit:source.get("behavior_event"))if(str(visit,"academy_id").equals(academy)
                && str(visit,"event_type").equals("PROFILE_VISIT") && str(visit,"actor_id").equals(owner)
                && within(time(visit,"occurred_at"),start,end) && !time(visit,"received_at").isAfter(cutoff))visits++;
            List<LocalDate> dates=new ArrayList<>(days);List<Long> gaps=new ArrayList<>();
            for(int i=1;i<dates.size();i++)gaps.add(ChronoUnit.DAYS.between(dates.get(i-1),dates.get(i)));
            Double deviation=null;
            if(!gaps.isEmpty()) {double mean=gaps.stream().mapToLong(Long::longValue).average().orElseThrow();
                deviation=Math.sqrt(gaps.stream().mapToDouble(g->Math.pow(g-mean,2)).average().orElseThrow());}
            var expected=JSON.createObjectNode().put("metrics_version","core-metrics-v1").put("deposit_count",deposits)
                .put("total_savings",net).put("avg_amount",deposits==0?0.0:(double)net/deposits)
                .put("abandon_count",abandons).put("transfer_count",transfers).put("visit_count",visits);
            if(deviation==null)expected.putNull("regularity_std");else expected.put("regularity_std",deviation);
            if(net>0)expected.put("pace_bias",((double)net-2.0*first)/net);else expected.putNull("pace_bias");
            JsonNode actual=candidate.get("author_previous_month");check(actual!=null && actual.isObject() && actual.size()==expected.size(),"METRICS_FIELDS");
            for(String field:expected.propertyNames()) {
                JsonNode value=actual.get(field),wanted=expected.get(field);
                boolean equal=value!=null && (wanted.isIntegralNumber()?value.isIntegralNumber()&&value.asLong()==wanted.asLong():
                    wanted.isNumber()?value.isNumber()&&Double.compare(value.asDouble(),wanted.asDouble())==0:wanted.equals(value));
                check(equal,"METRIC:"+field);
            }
            count++;
        }
        return new Verification(count);
    }
    private static Map<String,JsonNode> index(JsonNode rows){var out=new HashMap<String,JsonNode>();for(JsonNode r:rows)check(out.put(str(r,"id"),r)==null,"DUPLICATE_ID");return out;}
    private static boolean within(Instant at,Instant start,Instant end){return !at.isBefore(start)&&at.isBefore(end);}
    private static boolean nonnull(JsonNode row,String field){return row.has(field)&&!row.get(field).isNull();}
    private static String str(JsonNode row,String field){return row.get(field).asString();}
    private static Instant time(JsonNode row,String field){return Instant.parse(str(row,field));}
    private static void check(boolean ok,String code){if(!ok)throw new IllegalArgumentException("RECAP_AUTHOR_"+code);}
}
