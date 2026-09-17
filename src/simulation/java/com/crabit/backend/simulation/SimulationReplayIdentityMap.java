package com.crabit.backend.simulation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Typed identities from verified committed replay state. Never allocates target/application UUIDs. */
public final class SimulationReplayIdentityMap {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> KINDS=Set.of("ACADEMY","STUDENT","ACCOUNT","WISH","LEDGER_ROOT",
        "LEDGER_EFFECT","BALANCE_OBSERVATION","ADJUSTMENT_CASE","SHARED_CARD","FEED_CONTEXT","BEHAVIOR_EVENT","RECAP_GENERATION");
    private final Map<String,List<JsonNode>> tables;
    private final Map<String,Map<String,String>> logical=new HashMap<>();
    private final SortedMap<String,JsonNode> entries=new TreeMap<>();
    private final Map<String,String> accountByStudent=new HashMap<>();
    private final Map<String,String> owners=new HashMap<>();

    private SimulationReplayIdentityMap(SimulationRelationalState.Export export,Map<String,UUID> identities) {
        tables=export.state().tables();
        identities.forEach((key,uuid)->{
            int split=key.indexOf(':');check(split>0,"KEY");String kind=key.substring(0,split),id=key.substring(split+1);
            if(!KINDS.contains(kind))return; // Membership and signed pagination remain diagnostic identities.
            check(id.matches("[A-Za-z0-9:_-]{1,160}"),"LOGICAL_ID");
            check(logical.computeIfAbsent(kind,k->new HashMap<>()).putIfAbsent(uuid.toString(),id)==null,"UUID_BIJECTION");
        });
    }
    /** Requires the runtime's relational, temporal, checkpoint and behavior verification first. */
    public static JsonNode capture(SimulationRelationalState.Export export,Map<String,UUID> identities,JsonNode schema) {
        var builder=new SimulationReplayIdentityMap(export,identities);builder.collect();
        var result=JSON.createObjectNode();result.put("schemaVersion",1);result.put("schemaKind","demo-simulation-id-map");
        result.put("datasetId",export.state().datasetId());result.set("entries",JSON.valueToTree(builder.entries.values()));
        SimulationBundleReader.validate(schema.get("$defs").get("idMap"),result,"idMap");
        return result;
    }
    /** Logical identity coverage only, not a replacement for normalized relational state. */
    public static JsonNode logicalProjection(JsonNode mapping) {
        var result=mapping.deepCopy();
        for(JsonNode entry:result.get("entries"))((tools.jackson.databind.node.ObjectNode)entry).remove("replayUuid");
        return result;
    }
    private void collect() {
        for(JsonNode row:rows("academy"))add("ACADEMY",text(row,"id"),null,null);
        for(JsonNode row:rows("student"))add("STUDENT",text(row,"id"),null,null);
        for(JsonNode row:rows("card_balance_account")) {
            String uuid=text(row,"id"),owner=known("ACCOUNT",uuid);
            add("ACCOUNT",uuid,owner,null);
            check(accountByStudent.putIfAbsent(text(row,"student_id"),owner)==null,"STUDENT_MULTIPLE_ACCOUNTS");
        }
        for(JsonNode row:rows("wish"))addOwned("WISH",row,"id",null);
        for(JsonNode row:rows("ledger_event"))addOwned("LEDGER_ROOT",row,"id",List.of(owner(row),text(row,"application_order")));
        for(JsonNode row:rows("balance_observation")) {
            check(row.hasNonNull("account_lookup_version"),"OBSERVATION_VERSION_REQUIRED");
            addOwned("BALANCE_OBSERVATION",row,"id",List.of(owner(row),text(row,"account_lookup_version")));
        }
        for(JsonNode row:rows("ledger_wish_effect"))addOwned("LEDGER_EFFECT",row,"id",
            List.of(known("LEDGER_ROOT",text(row,"event_id")),known("WISH",text(row,"wish_id"))));
        for(JsonNode row:rows("balance_adjustment_case"))addOwned("ADJUSTMENT_CASE",row,"id",
            List.of(owner(row),known("BALANCE_OBSERVATION",text(row,"opening_balance_observation_id"))));
        // Current cards may have disappeared after a privacy transition. Retained trigger history proves their owner.
        var cards=new TreeMap<String,JsonNode>();
        for(JsonNode history:rows("feed_source_history"))if(text(history,"source_kind").equals("shared_card")) {
            JsonNode row=history.get("payload");String uuid=text(row,"id");JsonNode prior=cards.putIfAbsent(uuid,row);
            check(prior==null || prior.get("wish_id").equals(row.get("wish_id")),"CARD_HISTORY_OWNER");
        }
        for(JsonNode row:rows("shared_card")) {
            JsonNode prior=cards.putIfAbsent(text(row,"id"),row);
            check(prior==null || prior.get("wish_id").equals(row.get("wish_id")),"CARD_HISTORY_OWNER");
        }
        for(JsonNode row:cards.values()) {
            String wish=text(row,"wish_id");known("WISH",wish);
            String owner=owners.get("WISH:"+wish);check(owner!=null,"CARD_WISH_OWNER");
            add("SHARED_CARD",text(row,"id"),owner,null);
        }
        for(JsonNode row:rows("behavior_result_context"))add("FEED_CONTEXT",text(row,"id"),actorOwner(row),null);
        for(JsonNode row:rows("behavior_event"))add("BEHAVIOR_EVENT",text(row,"event_id"),actorOwner(row),null);
        for(JsonNode row:rows("recap_generation"))addOwned("RECAP_GENERATION",row,"id",null);
        // Every executed derived identity must have a current or retained row; preallocated population is deliberately excluded.
        for(String kind:KINDS)if(!Set.of("ACADEMY","STUDENT","ACCOUNT").contains(kind))
            for(String uuid:logical.getOrDefault(kind,Map.of()).keySet())check(owners.containsKey(kind+":"+uuid),"UNPERSISTED_IDENTITY:"+kind);
    }
    private void addOwned(String kind,JsonNode row,String key,List<String> basis) { add(kind,text(row,key),owner(row),basis); }
    private String actorOwner(JsonNode row) {
        String owner=accountByStudent.get(text(row,"actor_id"));check(owner!=null,"ACTOR_ACCOUNT");return owner;
    }
    private String owner(JsonNode row) { return known("ACCOUNT",text(row,"account_id")); }
    private void add(String kind,String uuid,String owner,List<String> basis) {
        UUID.fromString(uuid);
        String id=logical.getOrDefault(kind,Map.of()).get(uuid);
        if(id==null) {
            check(basis!=null,"UNKNOWN_IDENTITY:"+kind);
            String canonical=SimulationBundleReader.canonical(JSON.valueToTree(basis));
            id="auto:"+SimulationBundleReader.digest(canonical.getBytes(StandardCharsets.UTF_8)).substring(7);
            logical.computeIfAbsent(kind,k->new HashMap<>()).put(uuid,id);
        }
        String key=kind+":"+id;
        var row=JSON.createObjectNode();row.put("entityKind",kind);row.put("logicalId",id);row.put("replayUuid",uuid);
        if(owner==null)row.putNull("ownerAccountId");else row.put("ownerAccountId",owner);
        check(entries.putIfAbsent(key,row)==null,"LOGICAL_BIJECTION:"+kind);
        owners.put(kind+":"+uuid,owner);
    }
    private String known(String kind,String uuid) {
        String id=logical.getOrDefault(kind,Map.of()).get(uuid);check(id!=null,"UNKNOWN_REFERENCE:"+kind);return id;
    }
    private List<JsonNode> rows(String table) { return Objects.requireNonNull(tables.get(table),table); }
    private static String text(JsonNode row,String field) { check(row.hasNonNull(field),"REQUIRED_FIELD:"+field);return row.get(field).asString(); }
    private static void check(boolean ok,String code) { if(!ok)throw new IllegalStateException("IDENTITY_EXPORT_"+code); }
}
