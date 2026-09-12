package com.crabit.backend.simulation;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Independently checks the complete closure-time candidate selection, not Python ranking or author metrics. */
public final class SimulationRecapStoryVerifier {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private SimulationRecapStoryVerifier() {}
    public record Verification(int eligibleBeforeLimit,int selected) {}
    static JsonNode capture(JdbcTemplate jdbc,UUID academy) {
        var out=JSON.createObjectNode();
        out.set("cards",JSON.valueToTree(jdbc.query("SELECT to_jsonb(c)::text FROM shared_card c JOIN wish w ON w.id=c.wish_id WHERE w.academy_id=?",(r,n)->JSON.readTree(r.getString(1)),academy)));
        out.set("follows",JSON.valueToTree(jdbc.query("SELECT to_jsonb(f)::text FROM student_follow f WHERE academy_id=?",(r,n)->JSON.readTree(r.getString(1)),academy)));
        out.set("blocks",JSON.valueToTree(jdbc.query("SELECT to_jsonb(b)::text FROM student_block b WHERE EXISTS (SELECT 1 FROM card_balance_account a WHERE a.academy_id=? AND (a.student_id=b.blocker_id OR a.student_id=b.blocked_id))",(r,n)->JSON.readTree(r.getString(1)),academy)));
        return out;
    }
    public static Verification verify(JsonNode request,JsonNode peers,JsonNode source) {
        String viewer=str(request,"student_id"),academy=str(request,"academy_id");
        Instant start=LocalDate.parse(str(request.get("period"),"start_date")).atStartOfDay(SEOUL).toInstant();
        Instant end=LocalDate.parse(str(request.get("period"),"end_date_exclusive")).atStartOfDay(SEOUL).toInstant();
        check(end.equals(time(request,"snapshot_at")),"CLOSURE_TIME");
        Map<String,JsonNode> accounts=index(peers.get("accounts")),wishes=index(peers.get("wish"));
        Set<String> active=new HashSet<>(),followed=new HashSet<>(),blocked=new HashSet<>();
        for(JsonNode row:peers.get("memberships"))if(str(row,"academy_id").equals(academy)&&!nonnull(row,"left_at"))active.add(str(row,"student_id"));
        for(JsonNode row:source.get("follows"))if(str(row,"academy_id").equals(academy)&&str(row,"source_id").equals(viewer)&&!nonnull(row,"ended_at"))followed.add(str(row,"target_id"));
        for(JsonNode row:source.get("blocks"))if(!nonnull(row,"released_at")) {
            if(str(row,"blocker_id").equals(viewer))blocked.add(str(row,"blocked_id"));
            if(str(row,"blocked_id").equals(viewer))blocked.add(str(row,"blocker_id"));
        }
        var eligible=new ArrayList<JsonNode>();Set<String> seen=new HashSet<>();
        for(JsonNode card:source.get("cards")) {
            JsonNode wish=wishes.get(str(card,"wish_id"));check(wish!=null,"CARD_WISH");
            check(seen.add(str(wish,"id")),"DUPLICATE_CARD");
            JsonNode account=accounts.get(str(wish,"account_id"));check(account!=null,"WISH_ACCOUNT");
            String owner=str(account,"student_id"),visibility=str(card,"visibility");
            if(!str(wish,"academy_id").equals(academy)||!str(account,"academy_id").equals(academy)||owner.equals(viewer)
                ||nonnull(wish,"deleted_at")||nonnull(account,"closed_at")||!active.contains(owner)||blocked.contains(owner)
                ||!(visibility.equals("ACADEMY")||visibility.equals("FOLLOWERS")&&followed.contains(owner))
                ||!str(wish,"state").equals("COMPLETED")||!nonnull(wish,"completed_at"))continue;
            if(!inPeriod(time(wish,"completed_at"),start,end)||!inPeriod(time(card,"updated_at"),start,end))continue;
            check(str(card,"kind").equals("COMPLETION"),"COMPLETION_KIND");
            eligible.add(wish);
        }
        // PostgreSQL UUID byte order is unsigned lexical UUID text order, not Java UUID.compareTo.
        eligible.sort(Comparator.comparing((JsonNode w)->time(w,"completed_at")).thenComparing(w->str(w,"id")));
        List<String> expected=eligible.stream().limit(5).map(w->str(w,"id")).toList();
        JsonNode candidates=request.get("input").get("success_story_candidates");
        check(candidates!=null&&candidates.isArray(),"CANDIDATES");var actual=new ArrayList<String>();
        for(JsonNode candidate:candidates) {
            check(str(candidate,"type_title").equals("ACADEMY_SUCCESS"),"TYPE_TITLE");
            actual.add(str(candidate,"wish_id"));
        }
        check(actual.equals(expected),"CANDIDATE_SELECTION");
        return new Verification(eligible.size(),expected.size());
    }
    private static Map<String,JsonNode> index(JsonNode rows){var out=new HashMap<String,JsonNode>();for(JsonNode r:rows)check(out.put(str(r,"id"),r)==null,"DUPLICATE_ID");return out;}
    private static boolean inPeriod(Instant at,Instant start,Instant end){return !at.isBefore(start)&&at.isBefore(end);}
    private static boolean nonnull(JsonNode row,String field){return row.has(field)&&!row.get(field).isNull();}
    private static String str(JsonNode row,String field){return row.get(field).asString();}
    private static Instant time(JsonNode row,String field){return Instant.parse(str(row,field));}
    private static void check(boolean ok,String code){if(!ok)throw new IllegalArgumentException("RECAP_STORY_"+code);}
}
