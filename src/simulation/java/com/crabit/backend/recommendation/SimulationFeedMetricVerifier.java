package com.crabit.backend.recommendation;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Independent account-wide metric oracle over captured rows; never invokes production aggregation. */
public final class SimulationFeedMetricVerifier {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private SimulationFeedMetricVerifier() {}

    public static void verify(JsonNode actual,YearMonth month,JsonNode account,Instant cutoff,JsonNode source) {
        JsonNode expected=compute(month,account,cutoff,source);
        check(actual!=null && actual.isObject() && actual.size()==expected.size(),"FIELDS");
        for(String field:expected.propertyNames()) {
            JsonNode value=actual.get(field),wanted=expected.get(field);
            boolean same=value!=null && (wanted.isIntegralNumber()?value.isIntegralNumber()&&value.canConvertToLong()&&value.asLong()==wanted.asLong():
                wanted.isNumber()?value.isNumber()&&Double.compare(value.asDouble(),wanted.asDouble())==0:wanted.equals(value));
            check(same,"VALUE:"+field);
        }
    }

    static JsonNode compute(YearMonth month,JsonNode account,Instant cutoff,JsonNode source) {
        String accountId=s(account,"id");
        Map<String,JsonNode> events=new HashMap<>(),all=new HashMap<>(),wishes=new HashMap<>();
        for(JsonNode wish:rows(source,"wish"))check(wishes.put(s(wish,"id"),wish)==null,"DUPLICATE_WISH");
        for(JsonNode e:rows(source,"ledger_event")) {
            check(all.put(s(e,"id"),e)==null,"DUPLICATE_EVENT");
            if(s(e,"account_id").equals(accountId) && !t(e,"occurred_at").isAfter(cutoff))events.put(s(e,"id"),e);
        }
        Map<String,String> roots=new HashMap<>();Set<String> parents=new HashSet<>();
        for(var entry:events.entrySet()) {
            JsonNode cursor=entry.getValue();Set<String> seen=new HashSet<>();
            if(cursor.hasNonNull("correction_of_event_id"))check(parents.add(s(cursor,"correction_of_event_id")),"BRANCHED_CORRECTION");
            while(cursor.hasNonNull("correction_of_event_id")) {
                check(seen.add(s(cursor,"id")),"CYCLIC_CORRECTION");
                JsonNode parent=events.get(s(cursor,"correction_of_event_id"));check(parent!=null,"MISSING_CORRECTION_PARENT");
                check(!t(parent,"occurred_at").isAfter(t(cursor,"occurred_at")),"CORRECTION_TIME");cursor=parent;
            }
            roots.put(entry.getKey(),s(cursor,"id"));
        }
        Map<String,Map<String,Long>> amounts=new HashMap<>();Set<String> pairs=new HashSet<>();
        for(JsonNode effect:rows(source,"ledger_wish_effect"))if(s(effect,"account_id").equals(accountId)) {
            String event=s(effect,"event_id"),wish=s(effect,"wish_id");
            JsonNode original=all.get(event);check(original!=null && s(original,"account_id").equals(accountId),"EFFECT_EVENT");
            if(t(original,"occurred_at").isAfter(cutoff))continue;
            check(wishes.containsKey(wish)&&s(wishes.get(wish),"account_id").equals(accountId),"EFFECT_OWNER");
            check(pairs.add(event+":"+wish),"DUPLICATE_EFFECT");
            amounts.computeIfAbsent(roots.get(event),ignored->new HashMap<>()).merge(wish,number(effect,"wish_delta"),Math::addExact);
        }
        Instant start=month.atDay(1).atStartOfDay(SEOUL).toInstant(),end=month.plusMonths(1).atDay(1).atStartOfDay(SEOUL).toInstant();
        Instant middle=month.atDay(16).atStartOfDay(SEOUL).toInstant();
        long deposits=0,total=0,first=0,transfers=0,abandons=0,visits=0;Set<LocalDate> days=new TreeSet<>();
        for(var chain:amounts.entrySet()) {
            JsonNode root=events.get(chain.getKey());String type=s(root,"event_type");
            var nonzero=chain.getValue().values().stream().filter(x->x!=0).toList();
            if(nonzero.isEmpty())continue;
            long accountDelta=0;
            for(var e:events.entrySet())if(roots.get(e.getKey()).equals(chain.getKey()))accountDelta=Math.addExact(accountDelta,number(e.getValue(),"account_delta"));
            if(type.equals("WISH_TRANSFER"))check(nonzero.size()==2&&Math.addExact(nonzero.get(0),nonzero.get(1))==0&&accountDelta==0,"TRANSFER_CHAIN");
            else check(nonzero.size()==1&&Set.of("WISH_DEPOSIT","WISH_WITHDRAWAL","WISH_COMPLETION_RETURN","WISH_ABANDONMENT_RETURN","WISH_DELETION_RETURN").contains(type),"CHAIN_SHAPE");
            Instant at=t(root,"occurred_at");if(!within(at,start,end))continue;
            if(type.equals("WISH_TRANSFER")){transfers++;continue;}
            if(!Set.of("WISH_DEPOSIT","WISH_WITHDRAWAL").contains(type))continue;
            long delta=nonzero.getFirst();
            if(delta>0){deposits++;days.add(at.atZone(SEOUL).toLocalDate());}
            total=Math.addExact(total,delta);if(at.isBefore(middle))first=Math.addExact(first,delta);
        }
        for(JsonNode wish:wishes.values())if(s(wish,"account_id").equals(accountId)&&s(wish,"state").equals("ABANDONED")
            &&wish.hasNonNull("abandoned_at")&&within(t(wish,"abandoned_at"),start,end)&&!t(wish,"abandoned_at").isAfter(cutoff))abandons++;
        for(JsonNode visit:rows(source,"behavior_event"))if(s(visit,"academy_id").equals(s(account,"academy_id"))
            &&s(visit,"actor_id").equals(s(account,"student_id"))&&s(visit,"event_type").equals("PROFILE_VISIT")
            &&within(t(visit,"occurred_at"),start,end)&&!t(visit,"received_at").isAfter(cutoff))visits++;
        check(total>=-9007199254740991L&&total<=9007199254740991L,"SAFE_INTEGER");
        List<LocalDate> dates=new ArrayList<>(days);List<Long> gaps=new ArrayList<>();
        for(int i=1;i<dates.size();i++)gaps.add(ChronoUnit.DAYS.between(dates.get(i-1),dates.get(i)));
        Double regularity=null;
        if(!gaps.isEmpty()) {double mean=gaps.stream().mapToLong(Long::longValue).average().orElseThrow();
            regularity=Math.sqrt(gaps.stream().mapToDouble(g->(g-mean)*(g-mean)).average().orElseThrow());}
        var result=JSON.createObjectNode().put("deposit_count",deposits).put("total_savings",total)
            .put("avg_amount",deposits==0?0.0:(double)total/deposits).put("abandon_count",abandons).put("transfer_count",transfers).put("visit_count",visits);
        if(regularity==null)result.putNull("regularity_std");else result.put("regularity_std",regularity);
        if(total>0)result.put("pace_bias",((double)total-2.0*first)/total);else result.putNull("pace_bias");
        return result;
    }
    private static JsonNode rows(JsonNode source,String table){JsonNode rows=source.get(table);check(rows!=null&&rows.isArray(),"SOURCE_TABLE:"+table);return rows;}
    private static long number(JsonNode row,String field){JsonNode n=row.get(field);check(n!=null&&n.isIntegralNumber()&&n.canConvertToLong(),"INTEGER");return n.asLong();}
    private static boolean within(Instant at,Instant start,Instant end){return !at.isBefore(start)&&at.isBefore(end);}
    private static String s(JsonNode row,String field){return row.get(field).asString();}
    private static Instant t(JsonNode row,String field){return Instant.parse(s(row,field));}
    private static void check(boolean ok,String rule){if(!ok)throw new IllegalArgumentException("FEED_METRIC_"+rule);}
}
