package com.crabit.backend.simulation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact-row FK cut analysis over a verified local export. Never expands scope or authorizes writes. */
public final class SimulationGraphBoundary {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Partition(long selectedRows, long retainedRows, String selectedDigest, String retainedDigest) {}
    public record Crossing(String table, List<String> columns, String target, List<String> targetColumns,
                           String direction, String sourceKeyDigest, String targetKeyDigest) {}
    public record Report(String datasetId, String catalogDigest, String selectionDigest,
                         Map<String,Partition> partitions, List<Crossing> crossings,
                         boolean foreignKeyClosed, boolean readyForApplication) {
        public Report { partitions=Collections.unmodifiableMap(new TreeMap<>(partitions)); crossings=List.copyOf(crossings); }
    }
    private SimulationGraphBoundary() {}

    // Package-only: callers use the dispatcher, which verifies current-runtime catalog and complete state first.
    static Report inspect(SimulationRelationalState.Export export, Map<String,List<JsonNode>> selection) {
        var catalog=export.catalog(); var tables=export.state().tables();
        check(tables.keySet().containsAll(selection.keySet()),"UNKNOWN_TABLE");
        var keys=new TreeMap<String,List<String>>();
        for(var key:catalog.keys()) if(key.primary()) {
            check(keys.putIfAbsent(key.table(),key.columns())==null,"PRIMARY_KEY_CATALOG");
        }
        var selected=new TreeMap<String,Set<String>>(); var rowKeys=new IdentityHashMap<JsonNode,String>();
        var partitions=new TreeMap<String,Partition>();
        for(String table:tables.keySet()) {
            List<String> primary=keys.get(table);
            var requested=selection.getOrDefault(table,List.of());
            check(primary!=null || requested.isEmpty(),"PRIMARY_KEY_REQUIRED");
            var available=new HashSet<String>();
            for(var row:tables.get(table)) {
                String key=primary==null?null:canonicalKey(row,primary);
                if(key!=null)check(available.add(key),"DUPLICATE_PRIMARY_KEY");
                rowKeys.put(row,key);
            }
            var chosen=new TreeSet<String>();
            for(var key:requested) {
                check(key.isObject() && new HashSet<>(key.propertyNames()).equals(new HashSet<>(primary)),"KEY_COLUMNS");
                String value=canonicalKey(key,primary);
                check(available.contains(value),"MISSING_ROW");
                check(chosen.add(value),"DUPLICATE_SELECTION");
            }
            selected.put(table,chosen);
            var take=new ArrayList<String>(); var keep=new ArrayList<String>();
            for(var row:tables.get(table))
                (rowKeys.get(row)!=null && chosen.contains(rowKeys.get(row))?take:keep).add(hash(SimulationBundleReader.canonical(row)));
            Collections.sort(take); Collections.sort(keep);
            partitions.put(table,new Partition(take.size(),keep.size(),hash(JSON.writeValueAsString(take)),hash(JSON.writeValueAsString(keep))));
        }
        var crossings=new ArrayList<Crossing>();
        for(var fk:catalog.foreignKeys()) {
            check(Set.of("s","f").contains(fk.match()),"MATCH_TYPE");
            var parents=new HashMap<List<JsonNode>,JsonNode>();
            for(var parent:tables.get(fk.target())) {
                var values=tuple(parent,fk.targetColumns());
                // SQL unique keys permit repeated NULL tuples; those cannot be referenced.
                if(values.stream().noneMatch(JsonNode::isNull))
                    check(parents.putIfAbsent(values,parent)==null,"AMBIGUOUS_PARENT");
            }
            for(var row:tables.get(fk.table())) {
                var values=tuple(row,fk.columns()); long nulls=values.stream().filter(JsonNode::isNull).count();
                if(nulls>0) { check(fk.match().equals("s") || nulls==values.size(),"PARTIAL_NULL_KEY"); continue; }
                JsonNode parent=parents.get(values); check(parent!=null,"DANGLING_REFERENCE");
                boolean from=rowKeys.get(row)!=null && selected.get(fk.table()).contains(rowKeys.get(row));
                boolean to=rowKeys.get(parent)!=null && selected.get(fk.target()).contains(rowKeys.get(parent));
                if(from!=to) {
                    check(rowKeys.get(row)!=null && rowKeys.get(parent)!=null,"BOUNDARY_PRIMARY_KEY_REQUIRED");
                    crossings.add(new Crossing(fk.table(),fk.columns(),fk.target(),fk.targetColumns(),
                        from?"SELECTED_TO_RETAINED":"RETAINED_TO_SELECTED",hash(rowKeys.get(row)),hash(rowKeys.get(parent))));
                }
            }
        }
        crossings.sort(Comparator.comparing(c->SimulationBundleReader.canonical(JSON.valueToTree(c))));
        String digest=hash(SimulationBundleReader.canonical(JSON.valueToTree(selected)));
        return new Report(export.state().datasetId(),export.state().catalogDigest(),digest,partitions,crossings,crossings.isEmpty(),false);
    }
    private static String canonicalKey(JsonNode row,List<String> columns) {
        var key=JSON.createObjectNode();
        for(String column:columns) {check(row.hasNonNull(column),"NULL_PRIMARY_KEY");key.set(column,row.get(column));}
        return SimulationBundleReader.canonical(key);
    }
    private static List<JsonNode> tuple(JsonNode row,List<String> columns) {return columns.stream().map(row::get).toList();}
    private static String hash(String value) {return SimulationBundleReader.digest(value.getBytes(StandardCharsets.UTF_8));}
    private static void check(boolean condition,String code) {if(!condition)throw new IllegalStateException("GRAPH_BOUNDARY_"+code);}
}
