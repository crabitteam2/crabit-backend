package com.crabit.backend.recap;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Independent recap-1 story projection check; never rewrites the Python response. */
public final class SimulationRecapResultVerifier {
    private SimulationRecapResultVerifier() {}
    public record Verified(boolean weekly, int storiesVerified, String algorithmVersion) {}
    public static Verified verify(JsonNode request, JsonNode view) {
        require("recap-1".equals(request.path("algorithm_version").asString()), "ALGORITHM");
        if ("MONTHLY".equals(request.path("kind").asString())) return new Verified(false, 0, "recap-1");
        require("WEEKLY".equals(request.path("kind").asString()), "KIND");
        var candidates=request.path("input").path("success_story_candidates");
        var stories=view.path("page3_academy_success_stories").path("stories");
        require(candidates.isArray() && stories.isArray() && candidates.size()<=5
            && candidates.size()==stories.size(), "SELECTION");
        Set<String> seen=new HashSet<>();
        for(int i=0;i<candidates.size();i++) {
            var candidate=candidates.get(i);var story=stories.get(i);
            String id=candidate.path("wish_id").asString();
            require(id!=null && seen.add(id) && Objects.equals(candidate.get("wish_id"),story.get("wish_id")), "SELECTION");
            require(Objects.equals(title(candidate),story.path("type_title").asString()), "AUTHOR_TYPE");
        }
        return new Verified(true,stories.size(),"recap-1");
    }
    // These are recap-1's versioned thresholds. A new algorithm needs an explicit new verifier.
    private static String title(JsonNode candidate) {
        var m=candidate.path("author_previous_month");
        if(!m.has("metrics_version")) return candidate.path("type_title").asString();
        require("core-metrics-v1".equals(m.path("metrics_version").asString()), "AUTHOR_METRICS_VERSION");
        long count=m.path("deposit_count").asLong();
        if(count>=8 && m.hasNonNull("regularity_std") && m.get("regularity_std").asDouble()<4.0) return "불도저형 토끼";
        if(count>=5 && m.path("avg_amount").asDouble()<2000) return "꾸준형 토끼";
        if(count<5 && m.hasNonNull("pace_bias") && m.get("pace_bias").asDouble()>0.3) return "단기 집중형 토끼";
        return "탐색형 토끼";
    }
    private static void require(boolean valid,String code) {
        if(!valid) throw new IllegalArgumentException("RECAP_RESULT_"+code);
    }
}
