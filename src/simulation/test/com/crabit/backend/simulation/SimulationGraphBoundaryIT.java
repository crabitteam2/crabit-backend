package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SimulationGraphBoundaryIT {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String DATASET="sha256:"+"d".repeat(64);
    private static JsonNode read(String path) throws Exception {return JSON.readTree(Files.readAllBytes(Path.of(path)));}
    private static JsonNode join(int sequence,String student,String account) {
        var e=JSON.createObjectNode();e.put("eventId","event-"+sequence);e.put("sequence",sequence);
        e.put("actorStudentId",student);e.put("occurredAt",SimulationCashOracle.START.toString());
        e.put("kind","JOIN");e.putArray("causes");
        e.set("command",JSON.valueToTree(Map.of("studentId",student,"accountId",account,"academyId","academy-1","grade",3)));
        e.putObject("outcome").put("status","APPLIED").put("resultRef","raw/result-"+sequence+".json");
        e.putArray("artifactRefs").add("raw/result-"+sequence+".json");return e;
    }
    private static JsonNode key(JsonNode row,List<String> columns) {
        var k=JSON.createObjectNode();for(String c:columns)k.set(c,row.get(c));return k;
    }
    @Test void exactRowsDetectBothForeignKeyDirectionsAndPreserveDatabaseAndEvidence() throws Exception {
        try(var d=new SimulationCommandDispatcher(read("api/demo-simulation-v1.schema.json"),DATASET,DATASET,
                read("src/test/resources/simulation/bundle-contract-valid/students.json"))) {
            d.execute(join(1,"student-3-00","account-3-00"));
            d.execute(join(2,"student-3-01","account-3-01"));
            var create=(tools.jackson.databind.node.ObjectNode)join(3,"student-3-00","account-3-00");
            create.put("kind","CREATE");
            create.put("occurredAt",SimulationCashOracle.START.plusSeconds(1).toString());
            var command=new HashMap<String,Object>();
            command.put("accountId","account-3-00");command.put("wishId","wish-1");command.put("idempotencyKey","create-1");
            command.put("purpose","graph cut");command.put("targetAmount",5000);
            command.put("startDate",null);command.put("targetDate",null);command.put("photoId",null);
            create.set("command",JSON.valueToTree(command));d.execute(create);
            var export=d.relationalState();var before=d.preservationFingerprint();
            String raw=JSON.writeValueAsString(export);
            var primary=new TreeMap<String,List<String>>();
            export.catalog().keys().stream().filter(SimulationRelationalState.Key::primary)
                .forEach(k->primary.put(k.table(),k.columns()));
            var all=new TreeMap<String,List<JsonNode>>();
            export.state().tables().forEach((table,rows)->{
                if(!rows.isEmpty())all.put(table,rows.stream().map(r->key(r,primary.get(table))).toList());
            });
            var full=d.inspectGraphBoundary(export,all);
            assertThat(full.foreignKeyClosed()).isTrue();assertThat(full.readyForApplication()).isFalse();
            assertThat(full.partitions().values()).allSatisfy(p->assertThat(p.retainedRows()).isZero());
            var empty=d.inspectGraphBoundary(export,Map.of());
            assertThat(empty.foreignKeyClosed()).isTrue();assertThat(empty.readyForApplication()).isFalse();
            assertThat(empty.partitions().values()).allSatisfy(p->assertThat(p.selectedRows()).isZero());
            JsonNode student=all.get("student").getFirst();
            var one=d.inspectGraphBoundary(export,Map.of("student",List.of(student)));
            assertThat(one.foreignKeyClosed()).isFalse();
            assertThat(one.partitions().get("student").selectedRows()).isEqualTo(1);
            assertThat(one.partitions().get("student").retainedRows()).isEqualTo(1);
            assertThat(one.crossings()).anySatisfy(c->{assertThat(c.table()).isEqualTo("card_balance_account");
                assertThat(c.target()).isEqualTo("student");assertThat(c.direction()).isEqualTo("RETAINED_TO_SELECTED");});
            var accountOnly=d.inspectGraphBoundary(export,Map.of("card_balance_account",all.get("card_balance_account")));
            assertThat(accountOnly.crossings()).anySatisfy(c->{assertThat(c.table()).isEqualTo("card_balance_account");
                assertThat(c.target()).isEqualTo("student");assertThat(c.direction()).isEqualTo("SELECTED_TO_RETAINED");});
            assertThat(accountOnly.crossings()).anySatisfy(c->{assertThat(c.columns()).hasSizeGreaterThan(1);
                assertThat(c.direction()).isEqualTo("RETAINED_TO_SELECTED");});
            var reversed=new TreeMap<String,List<JsonNode>>();all.forEach((t,rows)->{var r=new ArrayList<>(rows);Collections.reverse(r);reversed.put(t,r);});
            assertThat(d.inspectGraphBoundary(export,reversed)).isEqualTo(full);
            assertThat(JSON.writeValueAsString(one)).doesNotContain(student.get("id").asString());
            assertThat(JSON.writeValueAsString(export)).isEqualTo(raw);
            assertThat(d.preservationFingerprint()).isEqualTo(before);
            Path output=Path.of("build/simulation-graph-boundary");Files.createDirectories(output);
            Files.writeString(output.resolve("student-cut.json"),JSON.writeValueAsString(one));
            Files.writeString(output.resolve("account-cut.json"),JSON.writeValueAsString(accountOnly));
            Files.writeString(output.resolve("full-cut.json"),JSON.writeValueAsString(full));
        }
    }
    @Test void unknownMissingDuplicateMalformedOrUnverifiedSelectionsFailClosed() throws Exception {
        try(var d=new SimulationCommandDispatcher(read("api/demo-simulation-v1.schema.json"),DATASET,DATASET,
                read("src/test/resources/simulation/bundle-contract-valid/students.json"))) {
            d.execute(join(1,"student-3-00","account-3-00"));var e=d.relationalState();
            JsonNode key=key(e.state().tables().get("student").getFirst(),List.of("id"));
            assertThatThrownBy(()->d.inspectGraphBoundary(e,Map.of("not_a_table",List.of())))
                .hasMessage("GRAPH_BOUNDARY_UNKNOWN_TABLE");
            assertThatThrownBy(()->d.inspectGraphBoundary(e,Map.of("student",List.of(key,key))))
                .hasMessage("GRAPH_BOUNDARY_DUPLICATE_SELECTION");
            assertThatThrownBy(()->d.inspectGraphBoundary(e,Map.of("student",List.of(JSON.valueToTree(Map.of("id",UUID.randomUUID().toString()))))))
                .hasMessage("GRAPH_BOUNDARY_MISSING_ROW");
            assertThatThrownBy(()->d.inspectGraphBoundary(e,Map.of("student",List.of(JSON.valueToTree(Map.of("id",key.get("id"),"nickname","extra"))))))
                .hasMessage("GRAPH_BOUNDARY_KEY_COLUMNS");
            var nullKey=JSON.createObjectNode().putNull("id");
            assertThatThrownBy(()->d.inspectGraphBoundary(e,Map.of("student",List.of(nullKey))))
                .hasMessage("GRAPH_BOUNDARY_NULL_PRIMARY_KEY");
            var forged=new SimulationRelationalState.Catalog(e.catalog().columns(),e.catalog().keys(),List.of());
            assertThatThrownBy(()->d.inspectGraphBoundary(new SimulationRelationalState.Export(forged,e.state()),Map.of()))
                .hasMessage("RELATIONAL_CATALOG_CHANGED");
            assertThat(d.inspectGraphBoundary(e,Map.of()).foreignKeyClosed()).isTrue();
        }
    }
}
