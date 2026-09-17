package com.crabit.backend.simulation;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Independent raw-row comparison at closure. Does not call production snapshot/metric helpers. */
public final class SimulationRecapPeriodVerifier {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private SimulationRecapPeriodVerifier() {}
    public record Verification(int effectiveTransactions,int wishes,long periodDeposits,long periodNetSavings,
                               long receivedVisits,long outgoingVisits) {}

    static JsonNode capture(JdbcTemplate jdbc,UUID account,UUID academy) {
        var source=JSON.createObjectNode();
        for(String table:List.of("ledger_event","ledger_wish_effect","wish")) {
            var rows=jdbc.query("SELECT to_jsonb(t)::text FROM "+table+" t WHERE account_id=?",(r,n)->JSON.readTree(r.getString(1)),account);
            source.set(table,JSON.valueToTree(rows));
        }
        source.set("peer_source",SimulationRecapPeerVerifier.capture(jdbc,academy));
        source.set("story_source",SimulationRecapStoryVerifier.capture(jdbc,academy));
        source.set("behavior_event",JSON.valueToTree(jdbc.query("SELECT to_jsonb(t)::text FROM behavior_event t WHERE academy_id=?",
            (r,n)->JSON.readTree(r.getString(1)),academy)));
        return source;
    }
    record Tx(String root,String wish,Instant at,long amount,String type) {}
    public static Verification verify(JsonNode request,JsonNode source) {
        String account=str(request,"card_balance_account_id"),student=str(request,"student_id"),academy=str(request,"academy_id");
        JsonNode period=request.get("period"),input=request.get("input");
        Instant start=LocalDate.parse(str(period,"start_date")).atStartOfDay(SEOUL).toInstant();
        Instant end=LocalDate.parse(str(period,"end_date_exclusive")).atStartOfDay(SEOUL).toInstant();
        check(str(period,"timezone").equals("Asia/Seoul") && time(request,"snapshot_at").equals(end),"CLOSURE_TIME");
        Map<String,JsonNode> events=new HashMap<>();Map<String,String> children=new HashMap<>();
        for(JsonNode row:source.get("ledger_event")) {
            check(str(row,"account_id").equals(account),"LEDGER_OWNER");
            check(time(row,"occurred_at").isBefore(end),"FUTURE_LEDGER");
            check(events.put(str(row,"id"),row)==null,"DUPLICATE_LEDGER");
            if(nonnull(row,"correction_of_event_id"))check(children.put(str(row,"correction_of_event_id"),str(row,"id"))==null,"BRANCHED_CORRECTION");
        }
        Map<String,String> roots=new HashMap<>();
        for(String id:events.keySet()) {
            JsonNode row=events.get(id);Set<String> seen=new HashSet<>();
            while(nonnull(row,"correction_of_event_id")) {
                check(seen.add(str(row,"id")),"CYCLIC_CORRECTION");
                row=events.get(str(row,"correction_of_event_id"));check(row!=null,"MISSING_CORRECTION");
            }
            roots.put(id,str(row,"id"));
        }
        Map<String,Map<String,Long>> sums=new HashMap<>();Set<String> effects=new HashSet<>();
        for(JsonNode row:source.get("ledger_wish_effect")) {
            String event=str(row,"event_id"),wish=str(row,"wish_id");
            check(str(row,"account_id").equals(account) && roots.containsKey(event),"EFFECT_OWNER_OR_EVENT");
            check(effects.add(event+":"+wish),"DUPLICATE_EFFECT");
            sums.computeIfAbsent(roots.get(event),k->new HashMap<>()).merge(wish,row.get("wish_delta").asLong(),Math::addExact);
        }
        List<Tx> expected=new ArrayList<>();Map<String,Long> saved=new HashMap<>();
        for(var root:sums.entrySet())for(var effect:root.getValue().entrySet()) {
            long delta=effect.getValue();if(delta==0)continue;
            JsonNode event=events.get(root.getKey());String type=switch(str(event,"event_type")) {
                case "WISH_TRANSFER" -> delta>0?"TRANSFER_IN":"TRANSFER_OUT";
                case "WISH_DEPOSIT","WISH_WITHDRAWAL" -> delta>0?"DEPOSIT":"WITHDRAWAL";
                case "WISH_COMPLETION_RETURN" -> "COMPLETION_RETURN";
                case "WISH_ABANDONMENT_RETURN" -> "ABANDONMENT_RETURN";
                case "WISH_DELETION_RETURN" -> "DELETION_RETURN";
                default -> throw new IllegalArgumentException("RECAP_PERIOD_UNKNOWN_LEDGER_TYPE");
            };
            expected.add(new Tx(root.getKey(),effect.getKey(),time(event,"occurred_at"),delta<0?Math.negateExact(delta):delta,type));
            saved.merge(effect.getKey(),delta,Math::addExact);
        }
        List<Tx> actual=new ArrayList<>();
        for(JsonNode tx:input.get("effective_transactions"))actual.add(new Tx(str(tx,"root_event_id"),str(tx,"wish_id"),time(tx,"occurred_at"),tx.get("amount").asLong(),str(tx,"type")));
        check(bag(actual).equals(bag(expected)),"EFFECTIVE_TRANSACTIONS");
        Map<String,JsonNode> wishes=new HashMap<>();
        for(JsonNode w:source.get("wish")) {
            check(str(w,"account_id").equals(account),"WISH_OWNER");
            check(time(w,"created_at").isBefore(end),"FUTURE_WISH");
            for(String field:List.of("completed_at","abandoned_at","deleted_at"))if(nonnull(w,field))check(time(w,field).isBefore(end),"FUTURE_WISH_LIFECYCLE");
            check(wishes.put(str(w,"id"),w)==null,"DUPLICATE_WISH");
        }
        check(wishes.keySet().containsAll(saved.keySet()),"MISSING_EFFECT_WISH");
        Set<String> seenWishes=new HashSet<>();
        for(JsonNode w:input.get("wishes")) {
            String id=str(w,"wish_id");JsonNode raw=wishes.get(id);
            check(raw!=null && seenWishes.add(id),"WISH_SET");
            check(str(w,"title").equals(str(raw,"purpose")) && str(w,"status").equals(str(raw,"state"))
                && w.get("target_amount").asLong()==raw.get("target_amount").asLong()
                && time(w,"created_at").equals(time(raw,"created_at"))
                && w.get("saved_amount_at_period_end").asLong()==Math.max(0,saved.getOrDefault(id,0L)),"WISH_VALUES");
            String closure=nonnull(raw,"completed_at")?"completed_at":"abandoned_at";
            check(Objects.equals(optionalTime(w,"closed_at"),optionalTime(raw,closure))
                && Objects.equals(optionalTime(w,"deleted_at"),optionalTime(raw,"deleted_at")),"WISH_LIFECYCLE");
            check(w.get("is_representative").asBoolean()==(nonnull(input,"representative_wish_id") && id.equals(str(input,"representative_wish_id"))),"REPRESENTATIVE");
        }
        check(seenWishes.equals(wishes.keySet()),"WISH_SET");
        check(!nonnull(input,"representative_wish_id") || wishes.containsKey(str(input,"representative_wish_id")),"REPRESENTATIVE");
        long received=0,previous=0,outgoing=0;Set<String> visitors=new HashSet<>();
        Instant month=LocalDate.parse(str(period,"end_date_exclusive")).minusDays(1).withDayOfMonth(1).atStartOfDay(SEOUL).toInstant();
        for(JsonNode visit:source.get("behavior_event")) {
            check(str(visit,"academy_id").equals(academy),"VISIT_ACADEMY");
            if(!str(visit,"event_type").equals("PROFILE_VISIT"))continue;
            Instant at=time(visit,"occurred_at");if(!at.isBefore(end)||time(visit,"received_at").isAfter(end))continue;
            if(str(visit,"target_id").equals(student)) {
                if(!at.isBefore(start)) {received++;visitors.add(str(visit,"actor_id"));}
                else if(!at.isBefore(start.minusSeconds(7*86400)))previous++;
            }
            if(str(visit,"actor_id").equals(student)&&!at.isBefore(month))outgoing++;
        }
        JsonNode visits=input.get("visit_metrics");
        check(visits.get("received_visit_count").asLong()==received && visits.get("unique_received_visitor_count").asLong()==visitors.size()
            && visits.get("previous_week_received_visit_count").asLong()==previous && visits.get("monthly_outgoing_visit_count").asLong()==outgoing,"VISIT_METRICS");
        long deposits=0,net=0;
        for(Tx tx:expected)if(!tx.at.isBefore(start)) {
            if(tx.type.equals("DEPOSIT")){deposits++;net=Math.addExact(net,tx.amount);}
            if(tx.type.equals("WITHDRAWAL"))net=Math.subtractExact(net,tx.amount);
        }
        return new Verification(expected.size(),wishes.size(),deposits,net,received,outgoing);
    }
    private static Map<Tx,Integer> bag(List<Tx> rows){Map<Tx,Integer> bag=new HashMap<>();for(Tx row:rows)bag.merge(row,1,Integer::sum);return bag;}
    private static String str(JsonNode row,String field){return row.get(field).asString();}
    private static Instant time(JsonNode row,String field){return Instant.parse(str(row,field));}
    private static boolean nonnull(JsonNode row,String field){return row.has(field)&&!row.get(field).isNull();}
    private static Instant optionalTime(JsonNode row,String field){return nonnull(row,field)?time(row,field):null;}
    private static void check(boolean ok,String code){if(!ok)throw new IllegalArgumentException("RECAP_PERIOD_"+code);}
}
