package com.crabit.backend.recommendation;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Independent raw-row candidate and monthly observation-window check, simulation only. */
public final class SimulationFeedInputVerifier {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private SimulationFeedInputVerifier() {}
    public static JsonNode capture(JdbcTemplate jdbc) {
        var source=JSON.createObjectNode();
        for(String table:List.of("card_balance_account","academy_membership","wish","shared_card","student_follow","student_block","behavior_collection","ledger_event","ledger_wish_effect","behavior_event","feed_visit_evidence","representative_wish_selection"))
            source.set(table,JSON.valueToTree(jdbc.query("SELECT to_jsonb(t)::text FROM "+table+" t",(r,n)->JSON.readTree(r.getString(1)))));
        return source;
    }
    public static Map<String,Object> verify(JsonNode request,JsonNode source) {
        String viewer=s(request,"viewer_id"),academy=s(request,"academy_id");
        Instant at=t(request,"recommendation_at");
        check(s(request,"timezone").equals("Asia/Seoul"),"TIMEZONE");
        Map<String,JsonNode> accounts=index(source,"card_balance_account"),wishes=index(source,"wish");
        var viewers=accounts.values().stream().filter(a->s(a,"student_id").equals(viewer)&&s(a,"academy_id").equals(academy)&&nil(a,"closed_at")).toList();
        check(viewers.size()==1,"VIEWER_ACCOUNT");
        JsonNode collection=source.get("behavior_collection");check(collection.size()==1,"COLLECTION");
        Instant started=t(collection.get(0),"started_at");
        int metricMonths=month(request.get("viewer_previous_month"),previous(at),viewers.getFirst(),started,at,source);
        List<JsonNode> eligible=new ArrayList<>();
        for(JsonNode card:source.get("shared_card")) {
            JsonNode wish=wishes.get(s(card,"wish_id"));check(wish!=null,"CARD_WISH");
            JsonNode account=accounts.get(s(wish,"account_id"));check(account!=null,"WISH_ACCOUNT");
            String author=s(account,"student_id"),visibility=s(card,"visibility");
            if(!s(wish,"academy_id").equals(academy)||!nil(wish,"deleted_at")||!nil(account,"closed_at")
                ||s(wish,"state").equals("ABANDONED")||author.equals(viewer))continue;
            boolean member=false,follow=false,block=false;
            for(JsonNode row:source.get("academy_membership"))if(s(row,"student_id").equals(author)&&s(row,"academy_id").equals(academy)&&nil(row,"left_at"))member=true;
            for(JsonNode row:source.get("student_follow"))if(s(row,"source_id").equals(viewer)&&s(row,"target_id").equals(author)&&s(row,"academy_id").equals(academy)&&nil(row,"ended_at"))follow=true;
            for(JsonNode row:source.get("student_block"))if(nil(row,"released_at")&&((s(row,"blocker_id").equals(viewer)&&s(row,"blocked_id").equals(author))||(s(row,"blocker_id").equals(author)&&s(row,"blocked_id").equals(viewer))))block=true;
            if(member&&!block&&(visibility.equals("ACADEMY")||(visibility.equals("FOLLOWERS")&&follow)))eligible.add(card);
        }
        eligible.sort(Comparator.<JsonNode,Instant>comparing(c->t(c,"updated_at")).thenComparing(c->s(c,"id")).reversed());
        eligible=eligible.subList(0,Math.min(100,eligible.size()));
        JsonNode actual=request.get("candidates");check(actual.size()==eligible.size(),"CANDIDATE_COUNT");
        for(int i=0;i<eligible.size();i++) {
            JsonNode card=eligible.get(i),candidate=actual.get(i),wish=wishes.get(s(card,"wish_id")),account=accounts.get(s(wish,"account_id"));
            check(s(candidate,"card_id").equals(s(card,"id"))&&s(candidate,"author_id").equals(s(account,"student_id")),"CANDIDATE_ORDER_OR_ID");
            check(t(candidate,"created_at").equals(t(wish,"created_at"))&&t(candidate,"content_updated_at").equals(t(card,"updated_at")),"CANDIDATE_TIME");
            check(Objects.equals(candidate.get("target_date"),wish.get("target_date")),"TARGET_DATE");
            Instant closed=nil(wish,"completed_at")?null:t(wish,"completed_at");
            check(Objects.equals(nil(candidate,"closed_at")?null:t(candidate,"closed_at"),closed),"CLOSURE");
            check(s(candidate,"state").equals(closed==null?s(wish,"state"):"COMPLETED"),"STATE");
            metricMonths+=month(candidate.get("author_previous_month"),previous(closed==null?at:closed),account,started,at,source);
        }
        SimulationFeedSimilarityVerifier.verify(request,source);
        SimulationFeedVisitVerifier.verify(request,source);
        return Map.of("categoryAndSimilaritiesVerified",true,"visitSignalsVerified",true,"candidateCount",eligible.size(),"candidateCompletenessVerified",true,"monthlyWindowsVerified",true,"monthlyMetricValuesVerified",true,"completeMetricMonthsVerified",metricMonths,"rankingAlgorithmVerified",false);
    }
    private static int month(JsonNode actual,YearMonth month,JsonNode account,Instant collection,Instant at,JsonNode source) {
        Instant from=month.atDay(1).atStartOfDay(SEOUL).toInstant(),to=month.plusMonths(1).atDay(1).atStartOfDay(SEOUL).toInstant();
        Instant earliest=Collections.max(List.of(at.minusSeconds(90L*86400).plusSeconds(300),t(account,"opened_at"),collection));
        String coverage=!from.isBefore(earliest)&&!to.isAfter(at)?"COMPLETE":from.isBefore(at)&&to.isAfter(earliest)&&earliest.isBefore(at)?"PARTIAL":"UNOBSERVED";
        check(s(actual,"month").equals(month.toString()),"MONTH");
        check(s(actual,"coverage").equals(coverage),"COVERAGE");
        check(s(actual,"metrics_version").equals("core-metrics-v1"),"METRICS_VERSION");
        check(coverage.equals("COMPLETE")?!nil(actual,"values")&&actual.get("values").isObject():nil(actual,"values"),"VALUES_OBSERVABILITY");
        if(coverage.equals("COMPLETE")) {
            SimulationFeedMetricVerifier.verify(actual.get("values"),month,account,at,source);return 1;
        }
        return 0;
    }
    private static YearMonth previous(Instant at){return YearMonth.from(at.atZone(SEOUL)).minusMonths(1);}
    private static Map<String,JsonNode> index(JsonNode source,String table){var result=new HashMap<String,JsonNode>();for(JsonNode row:source.get(table))check(result.put(s(row,"id"),row)==null,"DUPLICATE_ROW");return result;}
    private static boolean nil(JsonNode n,String field){return !n.hasNonNull(field);}
    private static String s(JsonNode n,String field){return n.get(field).asString();}
    private static Instant t(JsonNode n,String field){return Instant.parse(s(n,field));}
    private static void check(boolean ok,String code){if(!ok)throw new IllegalArgumentException("FEED_INPUT_"+code);}
}
