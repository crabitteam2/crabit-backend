package com.crabit.backend.recommendation;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Checks historical interest flags; current candidate authorization is verified separately. */
public final class SimulationFeedVisitVerifier {
    private SimulationFeedVisitVerifier() {}
    public static void verify(JsonNode request,JsonNode source) {
        JsonNode visits=source.get("behavior_event"),evidence=source.get("feed_visit_evidence");
        check(visits!=null&&visits.isArray()&&evidence!=null&&evidence.isArray(),"SOURCE");
        Instant at=Instant.parse(request.get("recommendation_at").asString()),from=at.minus(Duration.ofDays(90));
        String viewer=request.get("viewer_id").asString(),academy=request.get("academy_id").asString();
        Map<String,JsonNode> observations=new HashMap<>();
        for(var row:evidence)check(observations.put(key(row),row)==null,"DUPLICATE_EVIDENCE");
        Set<String> authors=new HashSet<>(),categories=new HashSet<>(),seen=new HashSet<>();
        for(var row:visits) {
            if(!row.get("event_type").asString().equals("PROFILE_VISIT")||!row.get("actor_id").asString().equals(viewer)
                ||!row.get("academy_id").asString().equals(academy))continue;
            Instant occurred=Instant.parse(row.get("occurred_at").asString()),received=Instant.parse(row.get("received_at").asString());
            if(occurred.isBefore(from)||occurred.isAfter(at)||received.isAfter(at))continue;
            check(seen.add(key(row)),"DUPLICATE_VISIT");authors.add(row.get("target_id").asString());
            var detail=observations.get(key(row));
            if(detail!=null&&detail.get("evidence_status").asString().equals("COMPLETE")) {
                JsonNode ids=detail.get("category_ids");check(ids!=null&&ids.isArray(),"CATEGORIES");
                for(var id:ids){check(id.isString(),"CATEGORY");categories.add(id.asString());}
            }
        }
        for(var candidate:request.get("candidates")) {
            flag(candidate,"visited_author_before",authors.contains(candidate.get("author_id").asString()));
            flag(candidate,"visited_category_before",categories.contains(candidate.get("category_id").asString()));
        }
    }
    private static String key(JsonNode row){return row.get("actor_id").asString()+":"+row.get("event_id").asString();}
    private static void flag(JsonNode candidate,String field,boolean expected) {
        var actual=candidate.get(field);check(actual!=null&&actual.isBoolean()&&actual.asBoolean()==expected,field);
    }
    private static void check(boolean valid,String code){if(!valid)throw new IllegalArgumentException("FEED_VISIT_"+code);}
}
