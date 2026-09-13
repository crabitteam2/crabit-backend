package com.crabit.backend.simulation;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verifies original HTTP bytes against persisted ranking before creating a comparison projection. */
final class SimulationFeedNormalization {
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    record Verified(JsonNode exchanges,Map<String,String> recommendationIds) {}
    static Verified verify(List<JsonNode> events,Path root,SimulationRelationalState.Export state,Map<String,UUID> identities) throws IOException {
        var output=JSON.createArrayNode();Map<String,String> recommendations=new HashMap<>();
        Map<String,Map<String,String>> ids=new HashMap<>();
        identities.forEach((key,id)->{int p=key.indexOf(':');ids.computeIfAbsent(key.substring(0,p),k->new HashMap<>()).put(id.toString(),key);});
        if(root==null)return new Verified(output,Map.of());
        for(JsonNode event:events) {
            if(!event.get("kind").asString().equals("FEED_QUERY"))continue;
            Path folder=root.resolve("event-"+event.get("sequence").asLong());
            Path pageFile=folder.resolve("page.json");if(!Files.exists(pageFile))continue;
            JsonNode page=JSON.readTree(Files.readAllBytes(pageFile));
            // Continuations do not invent another exchange; failed transport remains raw evidence only.
            if(!Files.exists(folder.resolve("request.json")) || !Files.exists(folder.resolve("response.json")))continue;
            JsonNode http=JSON.readTree(Files.readAllBytes(folder.resolve("http.json")));
            if(http.get("status").asInt()!=200)continue;
            byte[] bytes=Files.readAllBytes(folder.resolve("request.json"));
            JsonNode request=JSON.readTree(bytes);
            String requestId=request.get("request_id").asString(),contextId=request.get("context_id").asString();
            var rows=state.state().tables().get("feed_page_context").stream()
                .filter(r->r.get("id").asString().equals(contextId)).toList();
            check(rows.size()==1,"CONTEXT");JsonNode row=rows.getFirst();
            // A 200 response can still be rejected by the production deadline/protocol checks.
            if(!row.get("ranking_outcome").asString().equals("RECOMMENDATION"))continue;
            JsonNode response=JSON.readTree(Files.readAllBytes(folder.resolve("response.json")));
            check(response.propertyNames().equals(Set.of("schema_version","request_id","context_id","input_digest","model_version","ordered_card_ids")),"RESPONSE_FIELDS");
            check(response.get("schema_version").isIntegralNumber() && response.get("schema_version").bigIntegerValue().equals(java.math.BigInteger.ONE),"VERSION");
            check(response.get("input_digest").asString().equals(SimulationBundleReader.digest(bytes)),"INPUT_DIGEST");
            check(response.get("request_id").equals(request.get("request_id")) && response.get("context_id").equals(request.get("context_id")),"RESPONSE_BINDING");
            check(row.get("recommendation_request_id").asString().equals(requestId)
                && row.get("viewer_id").equals(request.get("viewer_id")) && row.get("academy_id").equals(request.get("academy_id"))
                && row.get("model_version").equals(response.get("model_version"))
                && row.get("ranked_card_ids").equals(response.get("ordered_card_ids")),"STORAGE_BINDING");
            check(page.get("page").get("recommendationResultId").asString().equals(requestId)
                && page.get("page").get("sortSource").asString().equals("RECOMMENDATION"),"PAGE_BINDING");
            Set<String> candidates=new HashSet<>();
            for(var candidate:request.get("candidates"))check(candidates.add(candidate.get("card_id").asString()),"DUPLICATE_CANDIDATE");
            Set<String> ranked=new HashSet<>();
            for(var card:response.get("ordered_card_ids"))check(candidates.contains(card.asString()) && ranked.add(card.asString()),"RANKED_CANDIDATE");
            check(ranked.size()==Math.min(20,candidates.size()),"RANKED_COUNT");
            String logical="FEED_RECOMMENDATION:"+event.get("eventId").asString();
            check(logical.equals(ids.getOrDefault("FEED_RECOMMENDATION",Map.of()).get(requestId)),"COMMAND_BINDING");
            check(recommendations.putIfAbsent(requestId,logical)==null,"DUPLICATE_EXCHANGE");
            var normalRequest=typed(request,ids,logical,null);
            String logicalDigest="logical:"+SimulationBundleReader.digest(com.crabit.backend.recap.SimulationFeedCanonicalJson.encode(normalRequest));
            var exchange=JSON.createObjectNode().put("eventId",event.get("eventId").asString());
            exchange.set("request",normalRequest);exchange.set("response",typed(response,ids,logical,logicalDigest));output.add(exchange);
        }
        for(JsonNode row:state.state().tables().get("feed_page_context"))
            if(row.hasNonNull("recommendation_request_id"))check(recommendations.containsKey(row.get("recommendation_request_id").asString()),"UNVERIFIED_STORED_RECOMMENDATION");
        return new Verified(output,Map.copyOf(recommendations));
    }
    private static JsonNode typed(JsonNode node,Map<String,Map<String,String>> ids,String logical,String digest) {
        if(node.isArray()){var a=JSON.createArrayNode();for(var x:node)a.add(typed(x,ids,logical,digest));return a;}
        if(!node.isObject())return node.deepCopy();
        var result=JSON.createObjectNode();
        for(String field:new TreeSet<>(node.propertyNames())) {
            JsonNode value=node.get(field);
            if(field.equals("request_id"))result.put(field,logical);
            else if(field.equals("context_id"))result.put(field,"FEED_SESSION:"+logical.substring(logical.indexOf(':')+1));
            else if(field.equals("input_digest")){check(digest!=null,"DIGEST_REQUIRED");result.put(field,digest);}
            else if(field.equals("ordered_card_ids")){var a=result.putArray(field);for(var x:value)a.add(ref(ids,"SHARED_CARD",x));}
            else {
                String kind=switch(field){case "viewer_id","author_id"->"STUDENT";case "academy_id"->"ACADEMY";case "card_id"->"SHARED_CARD";default->null;};
                if(kind!=null && !value.isNull())result.put(field,ref(ids,kind,value));
                else result.set(field,typed(value,ids,logical,digest));
            }
        }
        return result;
    }
    private static String ref(Map<String,Map<String,String>> ids,String kind,JsonNode value) {
        String result=ids.getOrDefault(kind,Map.of()).get(value.asString());check(result!=null,"UNKNOWN_ID:"+kind);return result;
    }
    private static void check(boolean ok,String code){if(!ok)throw new IllegalArgumentException("FEED_NORMALIZATION_"+code);}
}
