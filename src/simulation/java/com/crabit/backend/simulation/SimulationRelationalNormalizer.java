package com.crabit.backend.simulation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Typed relational comparison projection. Original rows and opaque fingerprints remain evidence. */
public final class SimulationRelationalNormalizer {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final SimulationRelationalState.Export export;
    private final Map<String,String> ids=new HashMap<>();
    private final Map<String,String> reverse=new HashMap<>();
    private final Set<String> opaque=new TreeSet<>();
    private final Map<String,String> historyVersions=new HashMap<>();
    private SimulationIdempotencyVerifier.Verified fingerprints;
    private final com.crabit.backend.recap.SimulationRecapNormalization recaps;
    private static final Map<String,List<String>> DERIVED=Map.ofEntries(
        Map.entry("academy_membership",List.of("student_id","academy_id","joined_at")),
        Map.entry("student_follow",List.of("academy_id","source_id","target_id")),
        Map.entry("student_block",List.of("blocker_id","blocked_id")),
        Map.entry("historical_balance_checkpoint",List.of("account_id","revision")),
        Map.entry("balance_adjustment_case_event",List.of("adjustment_case_id","sequence_number")),
        Map.entry("mismatch_notification_outbox",List.of("adjustment_case_id")),
        Map.entry("feed_page_context",List.of("viewer_id","academy_id","created_at","ranking_outcome","ranked_card_ids")),
        Map.entry("feed_page_state",List.of("context_id","ranked_offset","latest_updated_at","latest_card_id","returned_card_ids")));
    private SimulationRelationalNormalizer(SimulationRelationalState.Export export,JsonNode mapping) {
        this.export=export;
        check(mapping.get("datasetId").asString().equals(export.state().datasetId()),"DATASET");
        for(JsonNode e:mapping.get("entries"))bind(e.get("replayUuid").asString(),e.get("entityKind").asString()+":"+e.get("logicalId").asString());
        Map<String,UUID> recapIds=new HashMap<>();
        for(JsonNode e:mapping.get("entries"))recapIds.put(e.get("entityKind").asString()+":"+e.get("logicalId").asString(),UUID.fromString(e.get("replayUuid").asString()));
        recaps=new com.crabit.backend.recap.SimulationRecapNormalization(recapIds);
    }
    /** Call only after verifying the trusted catalog, relational graph and typed identity map. */
    public static JsonNode normalize(SimulationRelationalState.Export export,JsonNode mapping) {
        return normalize(export,mapping,null);
    }
    public static JsonNode normalize(SimulationRelationalState.Export export,JsonNode mapping,SimulationIdempotencyVerifier.Verified fingerprints) {
        return normalize(export,mapping,fingerprints,Map.of());
    }
    static JsonNode normalize(SimulationRelationalState.Export export,JsonNode mapping,SimulationIdempotencyVerifier.Verified fingerprints,Map<String,String> recommendations) {
        var n=new SimulationRelationalNormalizer(export,mapping);n.fingerprints=fingerprints;
        recommendations.forEach(n::bind);n.derive();n.deriveHistoryVersions();
        var result=JSON.createObjectNode().put("schemaVersion",1).put("schemaKind","simulation-normalized-relational-state")
            .put("datasetId",export.state().datasetId()).put("catalogDigest",export.state().catalogDigest());
        var tables=result.putObject("tables");
        for(String table:new TreeSet<>(export.state().tables().keySet())) {
            List<JsonNode> rows=new ArrayList<>();
            for(JsonNode row:export.state().tables().get(table))rows.add(n.row(table,row));
            rows.sort(Comparator.comparing(row->new String(com.crabit.backend.recap.SimulationFeedCanonicalJson.encode(row),StandardCharsets.UTF_8)));tables.set(table,JSON.valueToTree(rows));
        }
        result.set("opaqueRuntimeFields",JSON.valueToTree(n.opaque));
        result.put("allRuntimeValuesNormalized",n.opaque.isEmpty());result.put("readyForApplication",false);
        return result;
    }
    private void derive() {
        // Historical rows retain removed relationship identities as well as live rows.
        for(String table:List.of("academy_membership","student_follow","student_block","historical_balance_checkpoint",
            "balance_adjustment_case_event","mismatch_notification_outbox","feed_page_context","feed_page_state")) {
            var rows=new ArrayList<>(export.state().tables().get(table));
            for(JsonNode h:export.state().tables().get("feed_source_history"))
                if(h.get("source_kind").asString().equals(table))rows.add(h.get("payload"));
            for(JsonNode row:rows) {
                String uuid=row.get("id").asString();if(ids.containsKey(uuid))continue;
                var basis=JSON.createArrayNode();
                for(String field:DERIVED.get(table)) {
                    JsonNode value=row.get(field);check(value!=null,"DERIVED_COLUMN:"+table+"/"+field);
                    basis.add(value(table,field,value));
                }
                bind(uuid,"ROW:"+table+":"+SimulationBundleReader.digest(SimulationBundleReader.canonical(basis).getBytes(StandardCharsets.UTF_8)).substring(7));
            }
        }
        for(JsonNode row:export.state().tables().get("behavior_impression")) {
            var basis=JSON.createArrayNode();for(String field:List.of("actor_id","context_id","position","card_id"))basis.add(value("behavior_impression",field,row.get(field)));
            // Distinct accepted impression identities may occupy the same page slot.
            // A canonical logical behavior event gives stable provenance without UUID ordering.
            var events=new ArrayList<String>();
            for(JsonNode event:export.state().tables().get("behavior_event"))
                if(event.get("actor_id").equals(row.get("actor_id")) && event.get("impression_id").equals(row.get("impression_id")))
                    events.add(ref(event.get("event_id")).asString());
            check(!events.isEmpty(),"IMPRESSION_WITHOUT_EVENT");
            Collections.sort(events);basis.add(events.getFirst());
            String uuid=row.get("impression_id").asString();
            if(!ids.containsKey(uuid))bind(uuid,"ROW:behavior_impression:"+SimulationBundleReader.digest(SimulationBundleReader.canonical(basis).getBytes(StandardCharsets.UTF_8)).substring(7));
        }
    }
    private void deriveHistoryVersions() {
        var ordered=new ArrayList<>(export.state().tables().get("feed_source_history"));
        ordered.sort(Comparator.comparingLong(row->row.get("version").asLong()));
        Map<String,Integer> counts=new HashMap<>();
        for(JsonNode row:ordered) {
            String source=row.get("source_kind").asString()+":"+ref(row.get("source_id")).asString();
            int ordinal=counts.merge(source,1,Integer::sum);
            check(historyVersions.putIfAbsent(row.get("version").asString(),"HISTORY:"+source+":"+ordinal)==null,"HISTORY_VERSION_DUPLICATE");
        }
    }
    private JsonNode historyVersion(String version) {
        String logical=historyVersions.get(version);check(logical!=null,"UNKNOWN_HISTORY_VERSION");
        return JSON.getNodeFactory().stringNode(logical);
    }
    private void bind(String uuid,String logical) {
        UUID.fromString(uuid);String prior=ids.putIfAbsent(uuid,logical),other=reverse.putIfAbsent(logical,uuid);
        check((prior==null||prior.equals(logical))&&(other==null||other.equals(uuid)),"IDENTITY_COLLISION");
    }
    private JsonNode ref(JsonNode node) {
        if(node.isNull())return node.deepCopy();check(node.isString(),"UUID_TYPE");
        String logical=ids.get(node.asString());check(logical!=null,"UNKNOWN_UUID:"+node.asString());return JSON.getNodeFactory().stringNode(logical);
    }
    private ObjectNode row(String table,JsonNode row) {
        var out=JSON.createObjectNode();
        Set<String> columns=new HashSet<>();for(var c:export.catalog().columns().get(table))columns.add(c.name());
        check(columns.equals(new HashSet<>(row.propertyNames())),"COLUMN_SET:"+table);
        if(table.equals("recap_generation"))return (ObjectNode)recaps.stored(row);
        for(String field:new TreeSet<>(columns)) {
            if(table.equals("feed_source_history") && field.equals("payload"))out.set(field,row(row.get("source_kind").asString(),row.get(field)));
            else out.set(field,value(table,field,row.get(field)));
        }
        return out;
    }
    private JsonNode value(String table,String field,JsonNode node) {
        if(node.isNull())return node.deepCopy();
        if(table.equals("feed_source_history") && field.equals("version"))return historyVersion(node.asString());
        var column=export.catalog().columns().get(table).stream().filter(c->c.name().equals(field)).findFirst().orElseThrow();
        if(column.type().equals("uuid"))return ref(node);
        if(column.type().equals("jsonb")) {
            if(table.equals("feed_source_history") && field.equals("payload"))throw new IllegalStateException("RELATIONAL_NORMALIZATION_HISTORY_CONTEXT");
            if(Set.of("ranked_card_ids","returned_card_ids","item_ids").contains(field)) {
                var out=JSON.createArrayNode();for(JsonNode item:node)out.add(ref(item));return out;
            }
            if(field.equals("active_wishes")) {
                var out=new ArrayList<JsonNode>();for(JsonNode item:node) {var copy=item.deepCopy();((ObjectNode)copy).set("wishId",ref(item.get("wishId")));out.add(copy);}
                out.sort(Comparator.comparing(x->x.get("wishId").asString()));return JSON.valueToTree(out);
            }
            if(field.equals("wish_idempotency_records")) {
                var out=JSON.createObjectNode();
                for(String key:new TreeSet<>(node.propertyNames()))out.set(key,idempotency(node.get(key)));
                return out;
            }
            if(field.equals("source_versions")) {
                var normalized=new ArrayList<String>();
                for(JsonNode item:node) {
                    String value=item.asString();int split=value.indexOf(':');check(split>0,"HISTORY_REFERENCE");
                    String prefix=value.substring(0,split);
                    if(Set.of("wish","card","account").contains(prefix))
                        value=prefix+":"+historyVersion(value.substring(split+1)).asString();
                    else check(Set.of("baseline","history").contains(prefix),"HISTORY_REFERENCE_KIND");
                    normalized.add(value);
                }
                Collections.sort(normalized);return JSON.valueToTree(normalized);
            }
            if(field.equals("category_ids"))return node.deepCopy();
            if(node.isEmpty())return node.deepCopy();
            // Preserve opaque JSON exactly; never erase it to force cross-run equality.
            opaque.add(table+"."+field);return node.deepCopy();
        }
        if(table.equals("balance_observation") && Set.of("provider_reference","simulation_source_ref").contains(field) && node.asString().startsWith("cash:")) {
            String[] parts=node.asString().split(":");check(parts.length==3,"PROVIDER_REFERENCE");
            return JSON.getNodeFactory().stringNode("cash:"+ref(JSON.getNodeFactory().stringNode(parts[1])).asString()+":"+parts[2]);
        }
        return node.deepCopy();
    }
    private JsonNode idempotency(JsonNode record) {
        var out=record.deepCopy();String operation=record.get("operation").asString();
        ((ObjectNode)out).set("targetId",ref(record.get("targetId")));
        ((ObjectNode)out).set("eventId",ref(record.get("eventId")));
        for(String field:List.of("snapshot","destinationSnapshot"))if(record.hasNonNull(field)) {
            var snapshot=record.get(field).deepCopy();
            for(String id:List.of("id","accountId","cardBalanceAccountId","academyId"))if(snapshot.has(id))((ObjectNode)snapshot).set(id,ref(snapshot.get(id)));
            ((ObjectNode)out).set(field,snapshot);
        }
        // Replace only a fingerprint reconciled against the exact original record; otherwise retain it as opaque.
        if(fingerprints!=null)((ObjectNode)out).put("requestFingerprint",fingerprints.normalizedFingerprint(record));
        else if(record.hasNonNull("requestFingerprint"))opaque.add("student.wish_idempotency_records.requestFingerprint");
        check(!operation.isEmpty(),"IDEMPOTENCY_OPERATION");return out;
    }
    private static void check(boolean ok,String code) {if(!ok)throw new IllegalStateException("RELATIONAL_NORMALIZATION_"+code);}
}
