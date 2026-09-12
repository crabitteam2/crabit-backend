package com.crabit.backend.simulation;

import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Comparison of an already verified backend replay, not a complete dataset or import artifact. */
final class SimulationBackendProjection {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private SimulationBackendProjection() {}

    static JsonNode capture(JsonNode schema,String dataset,String manifest,JsonNode students,List<JsonNode> commands,
        JsonNode mapping,JsonNode cash,JsonNode relational,JsonNode responses) {
        for(JsonNode part:List.of(mapping,cash,relational,responses))
            check(part.hasNonNull("datasetId") && part.get("datasetId").asString().equals(dataset),"DATASET");
        check(relational.path("allRuntimeValuesNormalized").asBoolean()
            && relational.path("opaqueRuntimeFields").isArray() && relational.get("opaqueRuntimeFields").isEmpty(),"OPAQUE_RUNTIME_FIELDS");
        check(new HashSet<>(relational.path("tables").propertyNames()).equals(new HashSet<>(SimulationRelationalState.TABLES)),"TABLE_SET");
        check(commands.size()==responses.path("events").size(),"RESULT_SET");
        Set<String> eventIds=new HashSet<>();long previous=0;
        for(int i=0;i<commands.size();i++) {
            JsonNode command=commands.get(i),result=responses.get("events").get(i);
            check(eventIds.add(command.get("eventId").asString()) && command.get("sequence").longValue()>previous,"COMMAND_ORDER");
            previous=command.get("sequence").longValue();
            for(String field:List.of("eventId","sequence","occurredAt","kind"))
                check(command.get(field).equals(result.get(field)),"RESULT_BINDING:"+field);
            check(command.get("outcome").get("status").equals(result.get("status")),"RESULT_STATUS");
        }
        var tables=relational.get("tables").deepCopy();
        JsonNode datasets=tables.get("demo_simulation_dataset");
        check(datasets.size()==1,"DATASET_ROW");
        ObjectNode row=(ObjectNode)datasets.get(0);
        check(row.path("dataset_id").asString().equals(dataset) && row.path("manifest_digest").asString().equals(manifest),"MANIFEST_BINDING");
        // Original manifest checksum depends on raw UUID-bearing evidence. Preserve it in the
        // raw export and observation; use its verified dataset identity only in this projection.
        row.put("manifest_digest","DATASET_MANIFEST:"+dataset);
        var out=JSON.createObjectNode().put("schemaVersion",1).put("schemaKind","simulation-backend-logical-projection")
            .put("normalizationVersion",1).put("datasetId",dataset).put("canonicalSchemaDigest",digest(schema))
            .put("fullDatasetValidationPerformed",false).put("readyForApplication",false);
        out.putArray("transportNormalizations").add("idMap.entries[].replayUuid -> typed logical identity")
            .add("tables.demo_simulation_dataset[].manifest_digest -> verified dataset identity");
        out.set("students",students.deepCopy());
        out.set("commands",JSON.valueToTree(commands));
        out.set("identities",SimulationReplayIdentityMap.logicalProjection(mapping));
        out.set("cash",cash.deepCopy());
        out.set("catalogDigest",relational.get("catalogDigest"));
        out.set("tables",tables);
        out.set("responses",responses.deepCopy());
        return out;
    }
    static String digest(JsonNode value) {
        return SimulationBundleReader.digest(com.crabit.backend.recap.SimulationFeedCanonicalJson.encode(value));
    }
    private static void check(boolean ok,String code) {
        if(!ok)throw new IllegalStateException("BACKEND_PROJECTION_"+code);
    }
}
