package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationRelationalNormalizerTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String DATASET="sha256:"+"c".repeat(64);
    private record Fixture(SimulationRelationalState.Export export,JsonNode mapping,String uuid) {}
    private Fixture fixture() {
        String uuid=UUID.randomUUID().toString();
        var rows=new TreeMap<String,List<JsonNode>>();
        var columns=new TreeMap<String,List<SimulationRelationalState.Column>>();
        for(String table:SimulationRelationalState.TABLES) {rows.put(table,List.of());columns.put(table,List.of());}
        var student=JSON.createObjectNode().put("id",uuid).put("name",uuid).put("number",123)
            .put("created_at","2026-06-01T00:00:00+09:00");
        rows.put("student",List.of(student));
        columns.put("student",List.of(new SimulationRelationalState.Column("id","uuid",false),
            new SimulationRelationalState.Column("name","text",false),new SimulationRelationalState.Column("number","int8",false),
            new SimulationRelationalState.Column("created_at","timestamptz",false)));
        var state=new SimulationRelationalState.State(1,"simulation-relational-state",DATASET,DATASET,rows);
        var catalog=new SimulationRelationalState.Catalog(columns,List.of(),List.of());
        var map=JSON.createObjectNode().put("datasetId",DATASET);
        map.putArray("entries").addObject().put("entityKind","STUDENT").put("logicalId","s1").put("replayUuid",uuid);
        return new Fixture(new SimulationRelationalState.Export(catalog,state),map,uuid);
    }
    @Test void convertsOnlyTypedIdentityAndKeepsFreeTextTimeMoneyAndOriginalEvidence() {
        var f=fixture();String original=SimulationBundleReader.canonical(JSON.valueToTree(f.export()));
        var n=SimulationRelationalNormalizer.normalize(f.export(),f.mapping());
        var row=n.get("tables").get("student").get(0);
        assertThat(row.get("id").asString()).isEqualTo("STUDENT:s1");
        assertThat(row.get("name").asString()).isEqualTo(f.uuid());
        assertThat(row.get("number").intValue()).isEqualTo(123);
        assertThat(row.get("created_at").asString()).isEqualTo("2026-06-01T00:00:00+09:00");
        assertThat(SimulationBundleReader.canonical(JSON.valueToTree(f.export()))).isEqualTo(original);
        assertThat(n.get("readyForApplication").booleanValue()).isFalse();
    }
    @Test void rejectsMissingAndConflictingIdentityMappingsInsteadOfMaskingThem() {
        var f=fixture();((ObjectNode)f.mapping()).putArray("entries");
        assertThatThrownBy(()->SimulationRelationalNormalizer.normalize(f.export(),f.mapping())).hasMessageStartingWith("RELATIONAL_NORMALIZATION_UNKNOWN_UUID");
        var g=fixture();((tools.jackson.databind.node.ArrayNode)g.mapping().get("entries")).addObject()
            .put("entityKind","STUDENT").put("logicalId","other").put("replayUuid",g.uuid());
        assertThatThrownBy(()->SimulationRelationalNormalizer.normalize(g.export(),g.mapping())).hasMessage("RELATIONAL_NORMALIZATION_IDENTITY_COLLISION");
    }
    @Test void refusesDifferentDatasetIdentityEvidence() {
        var f=fixture();((ObjectNode)f.mapping()).put("datasetId","sha256:"+"b".repeat(64));
        assertThatThrownBy(()->SimulationRelationalNormalizer.normalize(f.export(),f.mapping())).hasMessage("RELATIONAL_NORMALIZATION_DATASET");
    }
    @Test void canonicalRowOrderDoesNotDependOnCaptureOrderAndChangedMoneyRemainsVisible() {
        var f=fixture();var rows=new TreeMap<>(f.export().state().tables());
        var a=rows.get("student").getFirst().deepCopy();var b=a.deepCopy();((ObjectNode)b).put("number",456);
        rows.put("student",List.of(a,b));var state=f.export().state();
        var first=new SimulationRelationalState.Export(f.export().catalog(),new SimulationRelationalState.State(1,state.schemaKind(),DATASET,DATASET,rows));
        rows.put("student",List.of(b,a));
        var second=new SimulationRelationalState.Export(f.export().catalog(),new SimulationRelationalState.State(1,state.schemaKind(),DATASET,DATASET,rows));
        assertThat(SimulationRelationalNormalizer.normalize(first,f.mapping())).isEqualTo(SimulationRelationalNormalizer.normalize(second,f.mapping()));
        assertThat(SimulationRelationalNormalizer.normalize(first,f.mapping())).isNotEqualTo(SimulationRelationalNormalizer.normalize(f.export(),f.mapping()));
    }
}
