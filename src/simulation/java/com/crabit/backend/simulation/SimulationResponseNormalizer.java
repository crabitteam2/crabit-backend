package com.crabit.backend.simulation;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Typed response projection only. Raw bytes, free text, amounts, array order and times stay intact. */
final class SimulationResponseNormalizer {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Map<String,Map<String,String>> reverse=new HashMap<>();
    private final Map<String,String> recapDigests;
    SimulationResponseNormalizer(Map<String,UUID> identities) { this(identities,Map.of()); }
    SimulationResponseNormalizer(Map<String,UUID> identities,Map<String,String> recapDigests) {
        this.recapDigests=Map.copyOf(recapDigests);
        identities.forEach((key,uuid)->{
            int split=key.indexOf(':');
            if(split<1)throw new IllegalArgumentException("NORMALIZATION_IDENTITY_KEY");
            var kind=reverse.computeIfAbsent(key.substring(0,split),ignored->new HashMap<>());
            if(kind.putIfAbsent(uuid.toString(),key.substring(split+1))!=null)
                throw new IllegalArgumentException("NORMALIZATION_IDENTITY_NOT_BIJECTIVE");
        });
    }
    JsonNode normalize(String command,byte[] raw,JsonNode cursor) {
        return visit(command,SimulationBundleReader.parse(raw),"",cursor);
    }
    private JsonNode visit(String command,JsonNode node,String path,JsonNode cursor) {
        if(node.isArray()) {
            var out=JSON.createArrayNode();
            for(JsonNode item:node)out.add(visit(command,item,path+"/*",cursor));
            return out;
        }
        if(!node.isObject())return node.deepCopy();
        ObjectNode out=JSON.createObjectNode();
        for(String field:new TreeSet<>(node.propertyNames())) {
            JsonNode value=node.get(field);String kind=null;
            if(field.equals("nextCursor") && !value.isNull()) {
                if(cursor==null)throw new IllegalStateException("NORMALIZATION_CURSOR_UNVERIFIED");
                out.set(field,visit(command,cursor,"/cursor",null));continue;
            }
            if(field.equals("inputDigest") && Set.of("CLOSE_WEEK","CLOSE_MONTH").contains(command)) {
                String digest=recapDigests.get(value.asString());
                if(digest==null)throw new IllegalStateException("NORMALIZATION_RECAP_DIGEST_UNVERIFIED");
                out.put(field,digest);continue;
            }
            kind=switch(field) {
                case "recommendationResultId" -> "FEED_RECOMMENDATION";
                case "generationId" -> Set.of("CLOSE_WEEK","CLOSE_MONTH").contains(command)?"RECAP_GENERATION":null;
                case "studentId","actorStudentId","ownerStudentId","ownerId" -> "STUDENT";
                case "accountId","cardBalanceAccountId" -> "ACCOUNT";
                case "academyId" -> "ACADEMY";
                case "sharedCardId" -> "SHARED_CARD";
                case "resultContextId" -> "FEED_CONTEXT";
                case "observationId" -> "BALANCE_OBSERVATION";
                case "sessionId" -> path.equals("/cursor")?"FEED_SESSION":null;
                case "stateId" -> path.equals("/cursor")?"FEED_PAGE_STATE":null;
                case "eventId" -> Set.of("PROFILE_VISIT","IMPRESSION","CLICK").contains(command)?"BEHAVIOR_EVENT":"LEDGER_ROOT";
                case "id" -> Set.of("/wish","/sourceWish","/destinationWish").contains(path)?"WISH":null;
                default -> null;
            };
            if(kind!=null && !value.isNull()) {
                String logical=reverse.getOrDefault(kind,Map.of()).get(value.asString());
                if(logical==null)throw new IllegalStateException("NORMALIZATION_IDENTITY_UNKNOWN:"+kind+":"+path+"/"+field);
                out.put(field,kind+":"+logical);
            } else out.set(field,visit(command,value,path+"/"+field,cursor));
        }
        return out;
    }
}
