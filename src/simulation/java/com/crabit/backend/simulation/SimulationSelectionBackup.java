package com.crabit.backend.simulation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only, local replay backup. No restore operation or target connection is accepted. */
public final class SimulationSelectionBackup {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public record Backup(String digest, byte[] bytes, long rows) {
        public Backup { bytes=bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    private SimulationSelectionBackup() {}

    static Backup capture(SimulationRelationalState.Export export, Map<String,List<JsonNode>> selection,
                          SimulationPreservationFingerprint.Snapshot fingerprint) {
        var boundary=SimulationSemanticGraphBoundary.inspect(export,selection);
        var primary=new TreeMap<String,List<String>>();
        export.catalog().keys().stream().filter(SimulationRelationalState.Key::primary)
            .forEach(k->primary.put(k.table(),k.columns()));
        var selectedRows=new TreeMap<String,List<JsonNode>>();
        long count=0;
        for(String table:export.state().tables().keySet()) {
            var chosen=new HashSet<String>();
            for(JsonNode key:selection.getOrDefault(table,List.of()))chosen.add(key(key,primary.get(table)));
            var rows=new ArrayList<JsonNode>();
            for(JsonNode row:export.state().tables().get(table))
                if(primary.containsKey(table) && chosen.contains(key(row,primary.get(table))))rows.add(row.deepCopy());
            rows.sort(Comparator.comparing(r->key(r,primary.get(table))));
            selectedRows.put(table,rows);count+=rows.size();
        }
        if(count==0)throw new IllegalArgumentException("SELECTION_BACKUP_EMPTY");
        var root=JSON.createObjectNode();
        root.put("schemaVersion",1);root.put("schemaKind","simulation-local-selection-backup");
        root.put("datasetId",export.state().datasetId());root.put("catalogDigest",export.state().catalogDigest());
        root.put("selectionDigest",boundary.foreignKeys().selectionDigest());
        root.set("catalog",JSON.valueToTree(export.catalog()));
        root.set("beforeFingerprint",JSON.valueToTree(fingerprint));
        root.set("boundary",JSON.valueToTree(boundary));root.set("tables",JSON.valueToTree(selectedRows));
        root.put("rows",count);root.put("readyForApplication",false);root.put("restoreSupported",false);
        // Preserve JSON stored in SQL text columns as text, including whitespace and original UUIDs.
        byte[] bytes=SimulationBundleReader.canonical(root).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>SimulationBundleReader.MAX_ARTIFACT_BYTES)throw new IllegalStateException("SELECTION_BACKUP_SIZE");
        return new Backup(SimulationBundleReader.digest(bytes),bytes,count);
    }
    private static String key(JsonNode row,List<String> columns) {
        var key=JSON.createObjectNode();for(String column:columns)key.set(column,row.get(column));
        return SimulationBundleReader.canonical(key);
    }
}
