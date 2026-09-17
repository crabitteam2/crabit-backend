package com.crabit.backend.recommendation;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Records and checks actual behavior page rows, including requests that never call Python. */
public final class SimulationFeedPageVerifier {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private SimulationFeedPageVerifier() {}
    public static JsonNode capture(JdbcTemplate jdbc,JsonNode page) {
        UUID id=UUID.fromString(page.path("resultContextId").asString());
        var source=JSON.createObjectNode();
        source.set("contexts",JSON.valueToTree(jdbc.query("SELECT to_jsonb(t)::text FROM behavior_result_context t WHERE id=?",(r,n)->JSON.readTree(r.getString(1)),id)));
        source.set("items",JSON.valueToTree(jdbc.query("SELECT to_jsonb(t)::text FROM behavior_result_item t WHERE context_id=? ORDER BY position",(r,n)->JSON.readTree(r.getString(1)),id)));
        return source;
    }
    public static Map<String,Object> verify(JsonNode page,JsonNode source) {
        var contexts=source.path("contexts");check(contexts.isArray() && contexts.size()==1,"CONTEXT");
        var context=contexts.get(0);String id=page.path("resultContextId").asString();
        check(context.path("id").asString().equals(id),"IDENTITY");
        check(Instant.parse(context.path("created_at").asString()).equals(Instant.parse(page.path("createdAt").asString())),"CREATED_AT");
        var actual=page.path("items");var rows=source.path("items");
        check(actual.isArray() && rows.isArray() && actual.size()==rows.size(),"COUNT");
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);
            check(row.path("context_id").asString().equals(id) && row.path("position").asInt()==i
                && row.path("card_id").asString().equals(actual.get(i).path("sharedCardId").asString()),"ITEM_ORDER");
        }
        return Map.of("persistedPageVerified",true,"itemCount",rows.size(),"rankingInputReconstructed",false);
    }
    private static void check(boolean valid,String code){if(!valid)throw new IllegalArgumentException("FEED_PAGE_"+code);}
}
