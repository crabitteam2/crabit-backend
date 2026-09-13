package com.crabit.backend.simulation;

import com.crabit.backend.wish.SharedCardIdGenerator;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Replays recorded identity allocation, never card state or recommendation results. */
final class SimulationSharedCardIds extends SharedCardIdGenerator {
    private final Map<String,UUID> byEvent;
    private String current;
    private boolean allocated;

    private SimulationSharedCardIds(Map<String,UUID> byEvent) { this.byEvent=Map.copyOf(byEvent); }

    static SimulationSharedCardIds recorded(JsonNode schema,String dataset,JsonNode mapping,List<JsonNode> events) {
        SimulationBundleReader.validate(schema.get("$defs").get("idMap"),mapping,"idMap");
        check(mapping.path("datasetId").asString().equals(dataset),"DATASET");
        Map<String,JsonNode> commands=new HashMap<>();
        for(JsonNode event:events)check(commands.putIfAbsent(event.path("eventId").asString(),event)==null,"EVENT_DUPLICATE");
        Map<String,UUID> ids=new HashMap<>();Set<UUID> unique=new HashSet<>();
        for(JsonNode entry:mapping.get("entries")) {
            if(!entry.get("entityKind").asString().equals("SHARED_CARD"))continue;
            String logical=entry.get("logicalId").asString();JsonNode event=commands.get(logical);
            check(event!=null && event.path("outcome").path("status").asString().equals("APPLIED"),"EVENT");
            check(Set.of("SHARE","VISIBILITY_CHANGE","COMPLETE","ABANDON","DEPOSIT","WITHDRAW").contains(event.path("kind").asString()),"KIND");
            check(event.path("command").path("accountId").equals(entry.get("ownerAccountId")),"OWNER");
            UUID id=UUID.fromString(entry.get("replayUuid").asString());
            check(ids.putIfAbsent(logical,id)==null && unique.add(id),"DUPLICATE");
        }
        return new SimulationSharedCardIds(ids);
    }

    void begin(String event) { check(current==null,"NESTED");current=event;allocated=false; }
    void finish() {
        try { check(allocated==byEvent.containsKey(current),"ALLOCATION_MISMATCH"); }
        finally { current=null;allocated=false; }
    }
    @Override public UUID nextId() {
        check(current!=null && byEvent.containsKey(current) && !allocated,"UNEXPECTED_ALLOCATION");
        allocated=true;return byEvent.get(current);
    }
    int size() { return byEvent.size(); }
    private static void check(boolean ok,String code) { if(!ok)throw new IllegalArgumentException("REPLAY_CARD_ID_"+code); }
}
