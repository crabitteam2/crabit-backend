package com.crabit.backend.simulation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit non-FK reference cut over verified replay evidence. Never selects extra rows or writes a DB. */
public final class SimulationSemanticGraphBoundary {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> HISTORY=Set.of("academy_membership","card_balance_account","shared_card","student_block","student_follow","wish");
    public record Crossing(String table,String reference,String target,String direction,String sourceKeyDigest,String targetKeyDigest) {}
    public record Report(SimulationGraphBoundary.Report foreignKeys,long checkedReferences,List<Crossing> crossings,
                         List<String> coverage,List<String> remainingCoverage,boolean coveredReferencesClosed,
                         boolean readyForApplication) {
        public Report {crossings=List.copyOf(crossings);coverage=List.copyOf(coverage);remainingCoverage=List.copyOf(remainingCoverage);}
    }
    private final SimulationRelationalState.Export export;
    private final Map<String,Set<String>> selected=new HashMap<>();
    private final Map<String,List<String>> primary=new HashMap<>();
    private final Map<String,Map<String,List<JsonNode>>> indices=new HashMap<>();
    private final List<Crossing> crossings=new ArrayList<>();
    private long references;
    private SimulationSemanticGraphBoundary(SimulationRelationalState.Export export,Map<String,List<JsonNode>> selection) {
        this.export=export;
        export.catalog().keys().stream().filter(SimulationRelationalState.Key::primary).forEach(k->primary.put(k.table(),k.columns()));
        export.state().tables().keySet().forEach(t->{var keys=new HashSet<String>();
            selection.getOrDefault(t,List.of()).forEach(r->keys.add(key(t,r)));selected.put(t,keys);});
    }
    // Caller verifies both catalog/SQL references and source-history domain integrity first.
    static Report inspect(SimulationRelationalState.Export export,Map<String,List<JsonNode>> selection) {
        var sql=SimulationGraphBoundary.inspect(export,selection);
        var n=new SimulationSemanticGraphBoundary(export,selection);n.inspect();
        n.crossings.sort(Comparator.comparing(c->SimulationBundleReader.canonical(JSON.valueToTree(c))));
        return new Report(sql,n.references,n.crossings,List.of("feed source identity/version chain and payload FK",
            "feed visit participants, accepted event and exact source versions","feed page viewer, academy and card arrays",
            "behavior card references including removed shares","checkpoint active wish references",
            "student idempotency target, source/destination snapshot and ledger event",
            "recap stored JSON typed generation, student, account, academy, wish and ledger references",
            "simulation observation cash sequence, complete cash prefix and current cache"),
            List.of("unsupported media", "non-reference product invariants and exact replacement scope"),
            sql.foreignKeyClosed() && n.crossings.isEmpty(),false);
    }
    private void inspect() {
        providerReferences();
        idempotency();
        recaps();
        var histories=new TreeMap<String,List<JsonNode>>();
        for(JsonNode row:rows("feed_source_history")) {
            String table=row.get("source_kind").asString();check(HISTORY.contains(table),"HISTORY_KIND");
            String id=row.get("source_id").asString();
            histories.computeIfAbsent(table+":"+id,k->new ArrayList<>()).add(row);
            for(JsonNode current:find(table,"id",row.get("source_id")))edge("feed_source_history",row,"source_id",table,current);
            JsonNode payload=row.get("payload");
            for(var fk:export.catalog().foreignKeys())if(fk.table().equals(table)) {
                var values=fk.columns().stream().map(payload::get).toList();
                if(values.stream().anyMatch(JsonNode::isNull))continue;
                var targets=rows(fk.target()).stream().filter(p->fk.targetColumns().stream().map(p::get).toList().equals(values)).toList();
                check(targets.size()==1,"HISTORY_PARENT");
                edge("feed_source_history",row,"payload."+String.join(",",fk.columns()),fk.target(),targets.getFirst());
            }
        }
        // Adjacent links bind the entire version chain without quadratic all-pairs edges.
        for(var chain:histories.values()) {
            chain.sort(Comparator.comparingLong(r->r.get("version").longValue()));
            for(int i=1;i<chain.size();i++)edge("feed_source_history",chain.get(i),"previous_version","feed_source_history",chain.get(i-1));
        }
        for(JsonNode row:rows("feed_visit_evidence")) {
            ref("feed_visit_evidence",row,"actor_id","student",row.get("actor_id"));
            ref("feed_visit_evidence",row,"target_author_id","student",row.get("target_author_id"));
            ref("feed_visit_evidence",row,"academy_id","academy",row.get("academy_id"));
            var events=find("behavior_event","event_id",row.get("event_id")).stream().filter(e->e.get("actor_id").equals(row.get("actor_id"))).toList();
            check(events.size()==1 && events.getFirst().get("event_type").asString().equals("PROFILE_VISIT")
                && events.getFirst().get("target_id").equals(row.get("target_author_id"))
                && events.getFirst().get("academy_id").equals(row.get("academy_id")),"VISIT_EVENT");
            edge("feed_visit_evidence",row,"actor_id,event_id","behavior_event",events.getFirst());
            check(row.get("source_versions").isArray(),"SOURCE_VERSIONS");
            for(JsonNode source:row.get("source_versions")) {
                check(source.isString(),"SOURCE_VERSION_TYPE");String value=source.asString();int split=value.indexOf(':');
                check(split>0,"SOURCE_VERSION");String kind=value.substring(0,split),version=value.substring(split+1);
                if(kind.equals("baseline")) {
                    check(rows("feed_history_collection").size()==1,"HISTORY_COLLECTION");
                    check(java.time.Instant.parse(version).equals(java.time.OffsetDateTime.parse(rows("feed_history_collection").getFirst().get("started_at").asString()).toInstant()),"HISTORY_BASELINE");
                    edge("feed_visit_evidence",row,"source_versions.baseline","feed_history_collection",rows("feed_history_collection").getFirst());
                } else if(kind.equals("history")) {
                    check(version.matches("0|[1-9][0-9]*"),"HISTORY_HIGH_WATER");
                    long high;
                    try {high=Long.parseLong(version);}catch(NumberFormatException e){throw new IllegalStateException("SEMANTIC_GRAPH_HISTORY_HIGH_WATER");}
                    check(high==0 || !find("feed_source_history","version",JSON.getNodeFactory().numberNode(high)).isEmpty(),"HISTORY_HIGH_WATER");
                    for(JsonNode h:rows("feed_source_history"))if(h.get("version").longValue()<=high)
                        edge("feed_visit_evidence",row,"source_versions.history","feed_source_history",h);
                } else {
                    String target=switch(kind){case "wish"->"wish";case "card"->"shared_card";case "account"->"card_balance_account";default->null;};
                    check(target!=null && version.matches("[1-9][0-9]*"),"SOURCE_VERSION_KIND");
                    var match=rows("feed_source_history").stream().filter(h->h.get("version").asString().equals(version)
                        && h.get("source_kind").asString().equals(target)).toList();
                    check(match.size()==1,"SOURCE_VERSION_TARGET");edge("feed_visit_evidence",row,"source_versions."+kind,"feed_source_history",match.getFirst());
                }
            }
        }
        for(JsonNode row:rows("feed_page_context")) {
            ref("feed_page_context",row,"viewer_id","student",row.get("viewer_id"));
            ref("feed_page_context",row,"academy_id","academy",row.get("academy_id"));
            cards("feed_page_context",row,"ranked_card_ids");
        }
        for(JsonNode row:rows("feed_page_state")) {cards("feed_page_state",row,"returned_card_ids");ref("feed_page_state",row,"latest_card_id","shared_card",row.get("latest_card_id"));}
        for(JsonNode row:rows("feed_page_transition"))cards("feed_page_transition",row,"item_ids");
        for(String table:List.of("behavior_result_item","behavior_impression","behavior_event"))
            for(JsonNode row:rows(table))ref(table,row,"card_id","shared_card",row.get("card_id"));
        for(JsonNode row:rows("historical_balance_checkpoint")) {
            check(row.get("active_wishes").isArray(),"ACTIVE_WISHES");
            for(JsonNode wish:row.get("active_wishes")) {
                check(wish.hasNonNull("wishId"),"ACTIVE_WISH_ID");
                ref("historical_balance_checkpoint",row,"active_wishes.wishId","wish",wish.get("wishId"));
            }
        }
    }
    /** A cash sequence denotes the entire immutable prefix, not only its final transaction. */
    private void providerReferences() {
        var ledgers=new HashMap<String,NavigableMap<Long,JsonNode>>();
        for(JsonNode cash:rows("demo_simulation_cash_event")) {
            check(cash.get("sequence").isIntegralNumber() && cash.get("sequence").canConvertToLong(),"CASH_SEQUENCE");
            long sequence=cash.get("sequence").longValue();
            check(sequence>0 && ledgers.computeIfAbsent(cash.get("account_id").asString(),k->new TreeMap<>())
                .putIfAbsent(sequence,cash)==null,"CASH_SEQUENCE");
        }
        var balances=new HashMap<String,Map<Long,Long>>();
        for(JsonNode account:rows("demo_simulation_account")) {
            String id=account.get("account_id").asString();
            var ledger=ledgers.getOrDefault(id,new TreeMap<>());var sums=new HashMap<Long,Long>();sums.put(0L,0L);
            long expected=0,total=0;JsonNode previous=null;java.time.Instant previousTime=null;
            for(var entry:ledger.entrySet()) {
                JsonNode cash=entry.getValue();check(entry.getKey()==++expected,"CASH_SEQUENCE");
                check(cash.get("dataset_id").equals(account.get("dataset_id")),"CASH_DATASET");
                long amount=cash.get("amount_krw").longValue();String kind=cash.get("kind").asString();
                check(amount>0 && amount<=9007199254740991L && Set.of("GRANT","PURCHASE").contains(kind),"CASH_AMOUNT");
                total=Math.addExact(total,kind.equals("GRANT")?amount:-amount);
                check(total>=0 && total<=9007199254740991L,"CASH_BALANCE");sums.put(expected,total);
                var at=java.time.OffsetDateTime.parse(cash.get("occurred_at").asString()).toInstant();
                check(previousTime==null || !at.isBefore(previousTime),"CASH_TIME");previousTime=at;
                if(previous!=null)edge("demo_simulation_cash_event",cash,"previous_sequence","demo_simulation_cash_event",previous);
                previous=cash;
            }
            check(account.get("cash_sequence").longValue()==expected && account.get("card_funds").longValue()==total,"CASH_CACHE");
            if(previous!=null)edge("demo_simulation_account",account,"cash_sequence","demo_simulation_cash_event",previous);
            balances.put(id,sums);
        }
        for(JsonNode observation:rows("balance_observation")) {
            String kind=observation.get("source_kind").asString();
            if(observation.get("status").asString().equals("FAILED")) {
                check(kind.equals("PROVIDER") && observation.get("simulation_dataset_id").isNull()
                    && observation.get("simulation_source_ref").isNull(),"FAILED_PROVIDER_SOURCE");continue;
            }
            check(observation.get("status").asString().equals("SUCCEEDED") && kind.equals("SIMULATION")
                && observation.get("simulation_dataset_id").asString().equals(export.state().datasetId())
                && observation.get("simulation_source_ref").isString(),"PROVIDER_SOURCE");
            String id=observation.get("account_id").asString(),ref=observation.get("simulation_source_ref").asString();
            String prefix="cash:"+id+":";
            check(ref.startsWith(prefix) && ref.substring(prefix.length()).matches("0|[1-9][0-9]*"),"PROVIDER_REFERENCE");
            long sequence;
            try {sequence=Long.parseLong(ref.substring(prefix.length()));}
            catch(NumberFormatException ex){throw new IllegalStateException("SEMANTIC_GRAPH_PROVIDER_SEQUENCE");}
            var ledger=ledgers.getOrDefault(id,new TreeMap<>());Long sum=balances.getOrDefault(id,Map.of()).get(sequence);
            check(sum!=null,"PROVIDER_SEQUENCE");
            check(observation.get("actual_card_balance").isIntegralNumber()
                && observation.get("actual_card_balance").longValue()==sum,"PROVIDER_BALANCE");
            var at=java.time.OffsetDateTime.parse(observation.get("observed_at").asString()).toInstant();
            if(sequence>0) {
                JsonNode cash=ledger.get(sequence);
                check(!java.time.OffsetDateTime.parse(cash.get("occurred_at").asString()).toInstant().isAfter(at),"PROVIDER_TIME");
                edge("balance_observation",observation,"simulation_source_ref.cash_sequence","demo_simulation_cash_event",cash);
            }
            var next=ledger.higherEntry(sequence);
            check(next==null || !java.time.OffsetDateTime.parse(next.getValue().get("occurred_at").asString()).toInstant().isBefore(at),"PROVIDER_STALE_SEQUENCE");
            var accounts=find("demo_simulation_account","account_id",observation.get("account_id"));
            check(accounts.size()==1,"PROVIDER_ACCOUNT");
            edge("balance_observation",observation,"simulation_source_ref.account","demo_simulation_account",accounts.getFirst());
        }
    }
    private void idempotency() {
        for(JsonNode row:rows("student")) {
            JsonNode records=row.get("wish_idempotency_records");
            check(records!=null && records.isObject(),"IDEMPOTENCY_RECORDS");
            // Never expose arbitrary idempotency keys in the diagnostic labels.
            for(JsonNode record:records) {
                check(record.isObject() && record.hasNonNull("operation") && record.get("operation").isString(),"IDEMPOTENCY_RECORD");
                String operation=record.get("operation").asString();
                check(Set.of("CREATE","DEPOSIT","WITHDRAW","TRANSFER","COMPLETE","ABANDON","DELETE").contains(operation),"IDEMPOTENCY_OPERATION");
                String target=Set.of("CREATE","TRANSFER").contains(operation)?"card_balance_account":"wish";
                requiredRef("student",row,"wish_idempotency_records.targetId",target,record.get("targetId"));
                snapshot(row,record.get("snapshot"),"snapshot");
                if(operation.equals("TRANSFER"))snapshot(row,record.get("destinationSnapshot"),"destinationSnapshot");
                else check(!record.hasNonNull("destinationSnapshot"),"IDEMPOTENCY_DESTINATION");
                ref("student",row,"wish_idempotency_records.eventId","ledger_event",record.get("eventId"));
                for(String photo:List.of("photoReplayState","destinationPhotoReplayState"))if(record.hasNonNull(photo)) {
                    JsonNode state=record.get(photo);
                    check(state.isObject() && state.hasNonNull("kind") && Set.of("NO_PHOTO","PHOTO_REVOKED").contains(state.get("kind").asString())
                        && !state.hasNonNull("photoId"),"UNSUPPORTED_MEDIA");
                }
            }
        }
    }
    private void snapshot(JsonNode row,JsonNode snapshot,String field) {
        check(snapshot!=null && snapshot.isObject(),"IDEMPOTENCY_SNAPSHOT");
        requiredRef("student",row,"wish_idempotency_records."+field+".id","wish",snapshot.get("id"));
        requiredRef("student",row,"wish_idempotency_records."+field+".cardBalanceAccountId","card_balance_account",snapshot.get("cardBalanceAccountId"));
        check(!snapshot.hasNonNull("photo"),"UNSUPPORTED_MEDIA");
    }
    private void recaps() {
        for(JsonNode row:rows("recap_generation"))for(String column:List.of("request_json","view_json","internal_metrics_json")) {
            JsonNode encoded=row.get(column);if(encoded==null || encoded.isNull())continue;
            check(encoded.isString(),"RECAP_JSON_TYPE");JsonNode parsed;
            try {parsed=SimulationBundleReader.parse(encoded.asString().getBytes(StandardCharsets.UTF_8));}
            catch(RuntimeException e){throw new IllegalStateException("SEMANTIC_GRAPH_RECAP_JSON_INVALID");}
            check(parsed!=null && parsed.isObject(),"RECAP_JSON_OBJECT");
            recapReferences(row,parsed,column,0);
        }
    }
    private void recapReferences(JsonNode row,JsonNode value,String path,int depth) {
        check(depth<=64,"RECAP_JSON_DEPTH");
        if(value.isArray()) {for(JsonNode v:value)recapReferences(row,v,path+"[]",depth+1);return;}
        if(!value.isObject())return;
        for(String field:value.propertyNames()) {
            JsonNode child=value.get(field);
            String target=switch(field) {
                case "generation_id" -> "recap_generation";
                case "student_id" -> "student";
                case "account_id","card_balance_account_id" -> "card_balance_account";
                case "academy_id" -> "academy";
                case "wish_id","representative_wish_id" -> "wish";
                case "root_event_id" -> "ledger_event";
                default -> null;
            };
            if(target!=null)ref("recap_generation",row,path+"."+field,target,child);
            else recapReferences(row,child,path+"."+field,depth+1);
        }
    }
    private void requiredRef(String table,JsonNode row,String label,String target,JsonNode value) {
        check(value!=null && !value.isNull(),"REFERENCE_REQUIRED");ref(table,row,label,target,value);
    }
    private void cards(String table,JsonNode row,String column) {
        check(row.get(column).isArray(),"CARD_ARRAY");
        for(JsonNode value:row.get(column)) {check(value.isString(),"CARD_ID");ref(table,row,column,"shared_card",value);}
    }
    private void ref(String table,JsonNode row,String label,String target,JsonNode value) {
        if(value==null || value.isNull())return;
        check(value.isString(),"REFERENCE_TYPE");
        List<JsonNode> current=find(target,"id",value);
        check(current.size()<=1,"REFERENCE_AMBIGUOUS");
        for(JsonNode parent:current)edge(table,row,label,target,parent);
        // Removed shared cards are valid historical identities. Bind to a retained source version.
        if(target.equals("shared_card")) {
            var history=rows("feed_source_history").stream().filter(h->h.get("source_kind").asString().equals(target)
                && h.get("source_id").equals(value)).min(Comparator.comparingLong(h->h.get("version").longValue()));
            check(!current.isEmpty() || history.isPresent(),"REFERENCE_MISSING");
            history.ifPresent(h->edge(table,row,label+".history","feed_source_history",h));
        } else check(current.size()==1,"REFERENCE_MISSING");
    }
    private List<JsonNode> find(String table,String column,JsonNode value) {
        var index=indices.computeIfAbsent(table+"."+column,k->{var map=new HashMap<String,List<JsonNode>>();
            for(JsonNode row:rows(table))map.computeIfAbsent(SimulationBundleReader.canonical(row.get(column)),x->new ArrayList<>()).add(row);return map;});
        return index.getOrDefault(SimulationBundleReader.canonical(value),List.of());
    }
    private void edge(String table,JsonNode row,String label,String target,JsonNode parent) {
        references++;String from=key(table,row),to=key(target,parent);
        boolean a=selected.get(table).contains(from),b=selected.get(target).contains(to);
        if(a!=b)crossings.add(new Crossing(table,label,target,a?"SELECTED_TO_RETAINED":"RETAINED_TO_SELECTED",hash(from),hash(to)));
    }
    private List<JsonNode> rows(String table) {return export.state().tables().get(table);}
    private String key(String table,JsonNode row) {
        check(primary.containsKey(table),"PRIMARY_KEY");var key=JSON.createObjectNode();
        for(String column:primary.get(table))key.set(column,row.get(column));return SimulationBundleReader.canonical(key);
    }
    private static String hash(String text) {return SimulationBundleReader.digest(text.getBytes(StandardCharsets.UTF_8));}
    private static void check(boolean ok,String code) {if(!ok)throw new IllegalStateException("SEMANTIC_GRAPH_"+code);}
}
