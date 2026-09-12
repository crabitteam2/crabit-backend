package com.crabit.backend.simulation;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Independent peer comparison from closure-time rows. Never calls production metric helpers. */
public final class SimulationRecapPeerVerifier {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private SimulationRecapPeerVerifier() {}
    public record Verification(int peers,int achievementRates,String representativeWishId) {}

    static JsonNode capture(JdbcTemplate jdbc,UUID academy) {
        var source=JSON.createObjectNode();
        source.set("accounts",JSON.valueToTree(jdbc.query("SELECT to_jsonb(a)::text FROM card_balance_account a WHERE academy_id=?",
            (r,n)->JSON.readTree(r.getString(1)),academy)));
        source.set("students",JSON.valueToTree(jdbc.query("SELECT to_jsonb(s)::text FROM student s WHERE EXISTS (SELECT 1 FROM card_balance_account a WHERE a.student_id=s.id AND a.academy_id=?)",
            (r,n)->JSON.readTree(r.getString(1)),academy)));
        source.set("memberships",JSON.valueToTree(jdbc.query("SELECT to_jsonb(m)::text FROM academy_membership m WHERE academy_id=?",
            (r,n)->JSON.readTree(r.getString(1)),academy)));
        for(String table:List.of("wish","ledger_event","ledger_wish_effect","representative_wish_selection"))
            source.set(table,JSON.valueToTree(jdbc.query("SELECT to_jsonb(t)::text FROM "+table+" t JOIN card_balance_account a ON a.id=t.account_id WHERE a.academy_id=?",
                (r,n)->JSON.readTree(r.getString(1)),academy)));
        return source;
    }

    public static Verification verify(JsonNode request,JsonNode source) {
        String academy=str(request,"academy_id"),viewer=str(request,"student_id"),account=str(request,"card_balance_account_id");
        Instant end=LocalDate.parse(str(request.get("period"),"end_date_exclusive")).atStartOfDay(SEOUL).toInstant();
        check(end.equals(time(request,"snapshot_at")),"CLOSURE_TIME");
        Map<String,JsonNode> students=index(source.get("students")),accounts=index(source.get("accounts"));
        JsonNode own=accounts.get(account);check(own!=null && str(own,"student_id").equals(viewer) && str(own,"academy_id").equals(academy),"VIEWER_IDENTITY");
        Set<String> active=new HashSet<>();
        for(JsonNode m:source.get("memberships")) {
            check(str(m,"academy_id").equals(academy),"MEMBERSHIP_ACADEMY");
            check(!time(m,"joined_at").isAfter(end),"FUTURE_MEMBERSHIP");
            if(!nonnull(m,"left_at"))active.add(str(m,"student_id"));
        }
        check(active.contains(viewer) && !nonnull(own,"closed_at"),"VIEWER_INACTIVE");
        Map<String,List<JsonNode>> wishes=group(source.get("wish"),accounts,"account_id");
        Map<String,String> representatives=new HashMap<>();
        for(JsonNode selection:source.get("representative_wish_selection")) {
            String a=str(selection,"account_id"),w=str(selection,"wish_id");
            check(accounts.containsKey(a) && representatives.put(a,w)==null,"REPRESENTATIVE_SELECTION");
            check(wishes.getOrDefault(a,List.of()).stream().anyMatch(x->str(x,"id").equals(w)),"REPRESENTATIVE_OWNER");
        }
        for(var entry:wishes.entrySet()) {
            for(JsonNode w:entry.getValue())check(time(w,"created_at").isBefore(end),"FUTURE_WISH");
            if(!representatives.containsKey(entry.getKey()))entry.getValue().stream()
                .filter(w->!nonnull(w,"deleted_at") && str(w,"state").equals("IN_PROGRESS"))
                .min(Comparator.comparing((JsonNode w)->time(w,"created_at")).thenComparing(w->str(w,"id")))
                .ifPresent(w->representatives.put(entry.getKey(),str(w,"id")));
        }
        String representative=representatives.get(account);
        check(Objects.equals(representative,nullable(request.get("input"),"representative_wish_id")),"VIEWER_REPRESENTATIVE");
        Map<String,List<JsonNode>> events=group(source.get("ledger_event"),accounts,"account_id");
        Map<String,List<JsonNode>> effects=group(source.get("ledger_wish_effect"),accounts,"account_id");
        JsonNode viewerRow=students.get(viewer);check(viewerRow!=null,"VIEWER_STUDENT");
        var habits=new ArrayList<Integer>();var rates=new ArrayList<Double>();
        Instant start=end.atZone(SEOUL).toLocalDate().minusWeeks(52).atStartOfDay(SEOUL).toInstant();
        for(String id:new TreeSet<>(accounts.keySet())) {
            JsonNode a=accounts.get(id),student=students.get(str(a,"student_id"));
            check(str(a,"academy_id").equals(academy) && student!=null,"ACCOUNT_IDENTITY");
            if(!str(viewerRow,"age_provenance").equals("PROVIDED") || str(a,"student_id").equals(viewer)
                || nonnull(a,"closed_at") || !active.contains(str(a,"student_id")) || !str(student,"age_provenance").equals("PROVIDED")
                || Math.abs(student.get("age").asLong()-viewerRow.get("age").asLong())>2)continue;
            var ledger=index(JSON.valueToTree(events.getOrDefault(id,List.of())));
            Map<String,String> roots=new HashMap<>();Set<String> parents=new HashSet<>();
            for(var e:ledger.entrySet()) {
                check(time(e.getValue(),"occurred_at").isBefore(end),"FUTURE_LEDGER");
                if(nonnull(e.getValue(),"correction_of_event_id"))check(parents.add(str(e.getValue(),"correction_of_event_id")),"BRANCHED_CORRECTION");
                JsonNode cursor=e.getValue();Set<String> seen=new HashSet<>();
                while(nonnull(cursor,"correction_of_event_id")) {
                    check(seen.add(str(cursor,"id")),"CYCLIC_CORRECTION");
                    JsonNode parent=ledger.get(str(cursor,"correction_of_event_id"));check(parent!=null,"MISSING_CORRECTION");
                    check(!time(parent,"occurred_at").isAfter(time(cursor,"occurred_at")),"CORRECTION_TIME");cursor=parent;
                }
                roots.put(e.getKey(),str(cursor,"id"));
            }
            Map<String,Map<String,Long>> totals=new HashMap<>();Set<String> pairs=new HashSet<>();
            for(JsonNode effect:effects.getOrDefault(id,List.of())) {
                String e=str(effect,"event_id"),w=str(effect,"wish_id");
                check(roots.containsKey(e) && pairs.add(e+":"+w),"EFFECT_EVENT");
                check(wishes.getOrDefault(id,List.of()).stream().anyMatch(x->str(x,"id").equals(w)),"EFFECT_WISH");
                totals.computeIfAbsent(roots.get(e),k->new HashMap<>()).merge(w,effect.get("wish_delta").asLong(),Math::addExact);
            }
            Set<LocalDate> weeks=new HashSet<>();long saved=0;
            for(var root:totals.entrySet())for(var effect:root.getValue().entrySet()) {
                JsonNode e=ledger.get(root.getKey());long delta=effect.getValue();String type=str(e,"event_type");
                if(delta>0 && Set.of("WISH_DEPOSIT","WISH_WITHDRAWAL","WISH_TRANSFER").contains(type) && !time(e,"occurred_at").isBefore(start))
                    weeks.add(time(e,"occurred_at").atZone(SEOUL).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
                if(effect.getKey().equals(representatives.get(id)))saved=Math.addExact(saved,delta);
            }
            habits.add(weeks.size());String selected=representatives.get(id);
            JsonNode wish=wishes.getOrDefault(id,List.of()).stream().filter(w->str(w,"id").equals(selected)).findFirst().orElse(null);
            if(wish!=null && !nonnull(wish,"deleted_at") && wish.get("target_amount").asLong()>0)
                rates.add(((double)Math.max(0,saved)/wish.get("target_amount").asLong())*100.0);
        }
        JsonNode actual=request.get("input").get("peer_metrics");
        check(actual!=null && JSON.valueToTree(habits).equals(actual.get("habit_active_weeks")),"HABIT_WEEKS");
        JsonNode actualRates=actual.get("achievement_rates");check(actualRates!=null && actualRates.size()==rates.size(),"ACHIEVEMENT_RATES");
        for(int i=0;i<rates.size();i++)check(actualRates.get(i).isNumber() && Double.compare(actualRates.get(i).asDouble(),rates.get(i))==0,"ACHIEVEMENT_RATES");
        return new Verification(habits.size(),rates.size(),representative);
    }
    private static Map<String,JsonNode> index(JsonNode rows){Map<String,JsonNode> out=new HashMap<>();for(JsonNode r:rows)check(out.put(str(r,"id"),r)==null,"DUPLICATE_ID");return out;}
    private static Map<String,List<JsonNode>> group(JsonNode rows,Map<String,JsonNode> accounts,String field){Map<String,List<JsonNode>> out=new HashMap<>();for(JsonNode r:rows){String a=str(r,field);check(accounts.containsKey(a),"FOREIGN_ACCOUNT");out.computeIfAbsent(a,k->new ArrayList<>()).add(r);}return out;}
    private static String str(JsonNode n,String k){return n.get(k).asString();}
    private static boolean nonnull(JsonNode n,String k){return n.has(k)&&!n.get(k).isNull();}
    private static String nullable(JsonNode n,String k){return nonnull(n,k)?str(n,k):null;}
    private static Instant time(JsonNode n,String k){return Instant.parse(str(n,k));}
    private static void check(boolean ok,String code){if(!ok)throw new IllegalArgumentException("RECAP_PEER_"+code);}
}
