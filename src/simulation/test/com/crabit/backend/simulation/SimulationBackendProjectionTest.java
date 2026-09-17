package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationBackendProjectionTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String DATASET="sha256:"+"a".repeat(64),MANIFEST="sha256:"+"b".repeat(64);
    private final ObjectNode schema=JSON.createObjectNode().put("schemaVersion",1);
    private final ObjectNode mapping=part(),cash=part(),relational=part(),responses=part();
    private final JsonNode students=JSON.valueToTree(List.of(Map.of("logicalStudentId","s1","syntheticDisplayName","001, free text")));
    private final List<JsonNode> commands=new ArrayList<>();
    SimulationBackendProjectionTest() {
        mapping.putArray("entries").addObject().put("entityKind","STUDENT").put("logicalId","s1").put("replayUuid",UUID.randomUUID().toString());
        cash.putArray("balances").addObject().put("accountId","a1").put("amountKrw",10000);
        relational.put("allRuntimeValuesNormalized",true).put("catalogDigest",DATASET);relational.putArray("opaqueRuntimeFields");
        var tables=relational.putObject("tables");for(String table:SimulationRelationalState.TABLES)tables.putArray(table);
        ((tools.jackson.databind.node.ArrayNode)tables.get("demo_simulation_dataset")).addObject().put("dataset_id",DATASET).put("manifest_digest",MANIFEST);
        var command=JSON.createObjectNode().put("eventId","e1").put("sequence",1).put("occurredAt","2026-06-01T00:00:00Z").put("kind","FEED_QUERY");
        command.putObject("command").put("limit",2);command.putObject("outcome").put("status","APPLIED");commands.add(command);
        var result=command.deepCopy();result.remove("command");result.remove("outcome");result.put("status","APPLIED");
        result.putObject("response").putArray("cards").add("SHARED_CARD:a").add("SHARED_CARD:b");responses.putArray("events").add(result);
    }
    private static ObjectNode part(){return JSON.createObjectNode().put("datasetId",DATASET);}
    private JsonNode capture(){return SimulationBackendProjection.capture(schema,DATASET,MANIFEST,students,commands,mapping,cash,relational,responses);}
    @Test void preservesOriginalEvidenceAndNormalizesOnlyBoundManifestAndMappedUuid() {
        String original=SimulationBundleReader.canonical(relational),ids=SimulationBundleReader.canonical(mapping);
        var first=capture();
        ((ObjectNode)mapping.get("entries").get(0)).put("replayUuid",UUID.randomUUID().toString());
        assertThat(capture()).isEqualTo(first);
        assertThat(SimulationBundleReader.canonical(relational)).isEqualTo(original);
        assertThat(ids).contains("replayUuid");
        String other="sha256:"+"c".repeat(64);
        ((ObjectNode)relational.get("tables").get("demo_simulation_dataset").get(0)).put("manifest_digest",other);
        assertThatThrownBy(this::capture).hasMessage("BACKEND_PROJECTION_MANIFEST_BINDING");
        assertThat(SimulationBackendProjection.capture(schema,DATASET,other,students,commands,mapping,cash,relational,responses)).isEqualTo(first);
        assertThat(first.get("readyForApplication").booleanValue()).isFalse();
    }
    @Test void refusesOpaqueRuntimeValuesMissingTablesAndForeignDataset() {
        relational.putArray("opaqueRuntimeFields").add("recap_generation.response_payload");
        assertThatThrownBy(this::capture).hasMessage("BACKEND_PROJECTION_OPAQUE_RUNTIME_FIELDS");
        relational.putArray("opaqueRuntimeFields");relational.put("allRuntimeValuesNormalized",false);
        assertThatThrownBy(this::capture).hasMessage("BACKEND_PROJECTION_OPAQUE_RUNTIME_FIELDS");
        relational.put("allRuntimeValuesNormalized",true);((ObjectNode)relational.get("tables")).remove("wish");
        assertThatThrownBy(this::capture).hasMessage("BACKEND_PROJECTION_TABLE_SET");
        ((ObjectNode)relational.get("tables")).putArray("wish");cash.put("datasetId",MANIFEST);
        assertThatThrownBy(this::capture).hasMessage("BACKEND_PROJECTION_DATASET");
    }
    @Test void changedMoneyTextSchemaOrRankingCannotBeHiddenByCanonicalization() {
        String original=SimulationBackendProjection.digest(capture());
        var money=(ObjectNode)cash.get("balances").get(0);money.put("amountKrw",9999);
        assertThat(SimulationBackendProjection.digest(capture())).isNotEqualTo(original);money.put("amountKrw",10000);
        ((ObjectNode)students.get(0)).put("syntheticDisplayName","changed");
        assertThat(SimulationBackendProjection.digest(capture())).isNotEqualTo(original);((ObjectNode)students.get(0)).put("syntheticDisplayName","001, free text");
        schema.put("schemaVersion",2);assertThat(SimulationBackendProjection.digest(capture())).isNotEqualTo(original);schema.put("schemaVersion",1);
        ((ObjectNode)responses.get("events").get(0).get("response")).putArray("cards").add("SHARED_CARD:b").add("SHARED_CARD:a");
        assertThat(SimulationBackendProjection.digest(capture())).isNotEqualTo(original);
    }
    @Test void rejectsMissingReorderedOrMismatchedActualResults() {
        ObjectNode result=(ObjectNode)responses.get("events").get(0);
        result.put("status","REJECTED");assertThatThrownBy(this::capture).hasMessage("BACKEND_PROJECTION_RESULT_STATUS");result.put("status","APPLIED");
        result.put("eventId","different");assertThatThrownBy(this::capture).hasMessage("BACKEND_PROJECTION_RESULT_BINDING:eventId");result.put("eventId","e1");
        responses.putArray("events");assertThatThrownBy(this::capture).hasMessage("BACKEND_PROJECTION_RESULT_SET");
    }
}
