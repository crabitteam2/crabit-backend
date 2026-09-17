package com.crabit.backend.recommendation;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Checks public composition guarantees independently of the Python scoring implementation. */
public final class SimulationFeedCompositionVerifier {
    private SimulationFeedCompositionVerifier() {}
    public static Map<String,Object> verify(JsonNode request, JsonNode response) {
        check(response.path("schema_version").asInt()==1 && response.path("model_version").asString().equals("feed-rules-v1"),"VERSION");
        check(Objects.equals(request.get("request_id"),response.get("request_id"))
            && Objects.equals(request.get("context_id"),response.get("context_id")),"IDENTITY");
        var candidates=new LinkedHashMap<String,JsonNode>();
        for(var c:request.path("candidates"))check(candidates.put(c.path("card_id").asString(),c)==null,"DUPLICATE_CANDIDATE");
        var ordered=response.path("ordered_card_ids");
        check(ordered.isArray() && ordered.size()==Math.min(20,candidates.size()),"COUNT");
        var seen=new HashSet<String>();var selected=new ArrayList<JsonNode>();
        for(var id:ordered) {
            check(id.isTextual() && seen.add(id.asString()) && candidates.containsKey(id.asString()),"MEMBERSHIP");
            selected.add(candidates.get(id.asString()));
        }
        Instant now=Instant.parse(request.path("recommendation_at").asString());
        boolean hasRecent=candidates.values().stream().anyMatch(c->recent(c,now));
        check(!hasRecent || selected.stream().anyMatch(c->recent(c,now)),"RECENT_COMPLETION");
        long available=candidates.values().stream().filter(SimulationFeedCompositionVerifier::role).count();
        long required=Math.min(2,Math.min(available,Math.min(10,selected.size())));
        long actual=selected.subList(0,Math.min(10,selected.size())).stream().filter(SimulationFeedCompositionVerifier::role).count();
        check(actual>=required,"TOP_TEN_ROLE_MODELS");
        return Map.of("compositionGuaranteesVerified",true,"candidateCount",candidates.size(),"selectedCount",selected.size(),
            "recentCompletionRequired",hasRecent,"requiredTopTenRoleModels",required,"rankingAlgorithmVerified",false);
    }
    private static boolean recent(JsonNode c,Instant now) {
        if(!c.path("state").asString().equals("COMPLETED") || !c.hasNonNull("closed_at"))return false;
        Instant closed=Instant.parse(c.get("closed_at").asString());
        return !closed.isAfter(now) && !closed.isBefore(now.minusSeconds(172800));
    }
    private static boolean role(JsonNode c) {
        if(!c.path("state").asString().equals("COMPLETED"))return false;
        var month=c.path("author_previous_month");
        if(!month.path("coverage").asString().equals("COMPLETE"))return false;
        var m=month.path("values");long count=m.path("deposit_count").asLong();
        return (count>=8 && m.hasNonNull("regularity_std") && m.get("regularity_std").asDouble()<4)
            || (count>=5 && m.path("avg_amount").asDouble()<2000);
    }
    private static void check(boolean valid,String code) {
        if(!valid)throw new IllegalArgumentException("FEED_COMPOSITION_"+code);
    }
}
