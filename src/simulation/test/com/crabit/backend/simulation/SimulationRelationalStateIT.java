package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationRelationalStateIT {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String DATASET="sha256:"+"c".repeat(64);
    private JsonNode read(String path) throws Exception { return JSON.readTree(Files.readAllBytes(Path.of(path))); }
    private JsonNode event(int sequence,String kind,Map<String,?> command) {
        var e=JSON.createObjectNode();e.put("eventId","event-"+sequence);e.put("sequence",sequence);
        e.put("actorStudentId","student-3-00");e.put("occurredAt",SimulationCashOracle.START.plusSeconds(sequence-1).toString());
        e.put("kind",kind);e.putArray("causes");e.set("command",JSON.valueToTree(command));
        e.putObject("outcome").put("status","APPLIED").put("resultRef","raw/result-"+sequence+".json");
        e.putArray("artifactRefs").add("raw/result-"+sequence+".json");return e;
    }
    private SimulationRelationalState.Export change(SimulationRelationalState.Export original,Consumer<Map<String,List<JsonNode>>> change) {
        var rows=new TreeMap<String,List<JsonNode>>();original.state().tables().forEach((k,v)->rows.put(k,new ArrayList<>(v.stream().map(JsonNode::deepCopy).toList())));
        change.accept(rows);var state=original.state();
        return new SimulationRelationalState.Export(original.catalog(),new SimulationRelationalState.State(1,state.schemaKind(),DATASET,state.catalogDigest(),rows));
    }
    @Test void committedGraphRejectsMissingCompositeParentsDuplicatesCatalogSubstitutionAndUnjoinedPopulation() throws Exception {
        var schema=read("api/demo-simulation-v1.schema.json");
        try(var d=new SimulationCommandDispatcher(schema,DATASET,DATASET,read("src/test/resources/simulation/bundle-contract-valid/students.json"))) {
            assertThatThrownBy(d::relationalState).hasMessage("RELATIONAL_EXPORT_REQUIRES_SUCCESSFUL_REPLAY");
            d.execute(event(1,"JOIN",Map.of("studentId","student-3-00","accountId","account-3-00","academyId","academy-1","grade",3)));
            d.execute(event(2,"GRANT",Map.of("accountId","account-3-00","cashEntryId","grant","amountKrw",10000,"budgetMonth","2026-06","scheduledAt",SimulationCashOracle.START.toString())));
            var create=new HashMap<String,Object>();create.put("accountId","account-3-00");create.put("wishId","wish");create.put("idempotencyKey","create");
            create.put("purpose","synthetic");create.put("targetAmount",5000);create.put("startDate",null);create.put("targetDate",null);create.put("photoId",null);
            d.execute(event(3,"CREATE",create));
            d.execute(event(4,"DEPOSIT",Map.of("accountId","account-3-00","wishId","wish","amount",4000,"expectedVersion",0,"idempotencyKey","deposit")));
            var export=d.relationalState();var verified=d.verifyRelationalState(export);
            assertThat(verified.tables()).isEqualTo(38);assertThat(verified.foreignKeys()).isGreaterThan(40);assertThat(verified.references()).isGreaterThan(10);
            assertThat(export.state().tables().get("student")).hasSize(1);
            assertThat(export.state().tables().get("balance_adjustment_case")).isNotNull();
            assertThat(export.state().tables().get("historical_balance_checkpoint")).isNotEmpty();
            assertThat(export.state().tables()).doesNotContainKeys("relationship_cursor_key","flyway_schema_history");
            assertThatThrownBy(()->d.verifyRelationalState(change(export,rows->rows.get("balance_observation").clear())))
                .hasMessage("RELATIONAL_DANGLING_REFERENCE");
            assertThatThrownBy(()->d.verifyRelationalState(change(export,rows->((ObjectNode)rows.get("ledger_wish_effect").getFirst()).put("account_id",UUID.randomUUID().toString()))))
                .hasMessage("RELATIONAL_DANGLING_REFERENCE");
            assertThatThrownBy(()->d.verifyRelationalState(change(export,rows->rows.get("wish").add(rows.get("wish").getFirst().deepCopy()))))
                .hasMessage("RELATIONAL_DUPLICATE_KEY");
            assertThatThrownBy(()->d.verifyRelationalState(change(export,rows->((ObjectNode)rows.get("wish").getFirst()).remove("purpose"))))
                .hasMessage("RELATIONAL_COLUMNS");
            assertThatThrownBy(()->d.verifyRelationalState(change(export,rows->((ObjectNode)rows.get("wish").getFirst()).putNull("account_id"))))
                .hasMessage("RELATIONAL_NULL_COLUMN");
            assertThatThrownBy(()->d.verifyRelationalState(change(export,rows->rows.put("relationship_cursor_key",List.of()))))
                .hasMessage("RELATIONAL_TABLE_SET");
            assertThatThrownBy(()->d.verifyRelationalState(change(export,rows->rows.remove("wish_photo"))))
                .hasMessage("RELATIONAL_TABLE_SET");
            assertThatThrownBy(()->d.verifyRelationalState(change(export,rows->{var student=rows.get("student").getFirst().deepCopy();
                ((ObjectNode)student).put("id",UUID.randomUUID().toString());rows.get("student").add(student);})))
                .hasMessage("RELATIONAL_POPULATION");
            var noFks=new SimulationRelationalState.Catalog(export.catalog().columns(),export.catalog().keys(),List.of());
            assertThatThrownBy(()->d.verifyRelationalState(new SimulationRelationalState.Export(noFks,export.state())))
                .hasMessage("RELATIONAL_CATALOG_CHANGED");
            // Verification never edits the captured evidence or DB.
            assertThat(d.verifyRelationalState(export)).isEqualTo(verified);
            assertThat(d.relationalState().state()).isEqualTo(export.state());
            var financial=SimulationCheckpointVerifier.verify(export);
            assertThat(financial.accounts()).isEqualTo(1);
            assertThat(financial.ledgerApplications()).isEqualTo(export.state().tables().get("ledger_event").size());
            assertThat(financial.activeWishFacts()).isPositive();
            assertThatThrownBy(()->SimulationCheckpointVerifier.verify(change(export,rows->rows.get("historical_ledger_application").clear())))
                .hasMessage("CHECKPOINT_APPLICATION_SET");
            assertThatThrownBy(()->SimulationCheckpointVerifier.verify(change(export,rows->rows.get("historical_balance_checkpoint").removeIf(r->r.get("revision").longValue()==1))))
                .hasMessage("CHECKPOINT_REVISION_CHAIN");
            assertThatThrownBy(()->SimulationCheckpointVerifier.verify(change(export,rows->{
                var checkpoint=rows.get("historical_balance_checkpoint").stream().filter(r->r.get("active_wish_allocation").longValue()>0).findFirst().orElseThrow();
                ((ObjectNode)checkpoint.get("active_wishes").get(0)).put("amount",3999);
                ((ObjectNode)checkpoint).put("active_wish_allocation",3999);
                if(checkpoint.hasNonNull("representative_wish_id"))((ObjectNode)checkpoint).put("representative_amount",3999);
            }))).hasMessage("CHECKPOINT_ACTIVE_LEDGER_AMOUNT");
            assertThatThrownBy(()->SimulationCheckpointVerifier.verify(change(export,rows->{
                var checkpoint=rows.get("historical_balance_checkpoint").stream().filter(r->r.hasNonNull("latest_observation_id")).findFirst().orElseThrow();
                ((ObjectNode)checkpoint).put("observation_lookup_version",99);
            }))).hasMessage("CHECKPOINT_OBSERVATION_BINDING");
            assertThatThrownBy(()->SimulationCheckpointVerifier.verify(change(export,rows->{
                var checkpoint=rows.get("historical_balance_checkpoint").stream().filter(r->!r.get("active_wishes").isEmpty()).findFirst().orElseThrow();
                ((ObjectNode)checkpoint.get("active_wishes").get(0)).put("wishId",UUID.randomUUID().toString());
            }))).hasMessage("CHECKPOINT_ACTIVE_REFERENCE");
            assertThat(SimulationCheckpointVerifier.verify(export)).isEqualTo(financial);
            var domain=d.verifyRelationalDomain(export);
            assertThat(domain.people()).isEqualTo(1);
            assertThat(domain.timestamps()).isGreaterThan(10);
            assertThat(domain.fullDatasetValidationPerformed()).isFalse();
            assertThatThrownBy(()->d.verifyRelationalDomain(change(export,rows->((ObjectNode)rows.get("wish").getFirst())
                .put("created_at",SimulationCashOracle.END.toString())))).hasMessageStartingWith("RELATIONAL_DOMAIN_TIME_RANGE");
            assertThatThrownBy(()->d.verifyRelationalDomain(change(export,rows->((ObjectNode)rows.get("wish").getFirst())
                .put("updated_at",SimulationCashOracle.START.plusSeconds(4).toString())))).hasMessageStartingWith("RELATIONAL_DOMAIN_TIME_RANGE");
            assertThatThrownBy(()->d.verifyRelationalDomain(change(export,rows->((ObjectNode)rows.get("academy_membership").getFirst())
                .put("joined_at",SimulationCashOracle.START.minusSeconds(1).toString())))).hasMessage("RELATIONAL_DOMAIN_MEMBERSHIP_BINDING");
            assertThatThrownBy(()->d.verifyRelationalDomain(change(export,rows->((ObjectNode)rows.get("demo_simulation_account").getFirst())
                .put("logical_student_id","another-student")))).hasMessage("RELATIONAL_DOMAIN_SIMULATION_IDENTITY");
            assertThatThrownBy(()->d.verifyRelationalDomain(change(export,rows->rows.get("feed_source_history").removeIf(r->r.get("source_kind").asString().equals("wish")))))
                .hasMessage("RELATIONAL_DOMAIN_HISTORY_MISSING_SOURCE");
            assertThatThrownBy(()->d.verifyRelationalDomain(change(export,rows->{
                var history=rows.get("feed_source_history").stream().filter(r->r.get("source_kind").asString().equals("wish") && r.get("valid_to").isNull()).findFirst().orElseThrow();
                ((ObjectNode)history.get("payload")).put("purpose","forged historical purpose");
            }))).hasMessage("RELATIONAL_DOMAIN_HISTORY_CURRENT_SNAPSHOT");
            assertThatThrownBy(()->d.verifyRelationalDomain(change(export,rows->{
                var history=rows.get("feed_source_history").stream().filter(r->r.get("source_kind").asString().equals("wish") && !r.get("valid_to").isNull()).findFirst().orElseThrow();
                ((ObjectNode)history).put("valid_to",SimulationCashOracle.START.plusSeconds(2).plusNanos(1000).toString());
            }))).hasMessage("RELATIONAL_DOMAIN_HISTORY_INTERVAL_CHAIN");
            assertThatThrownBy(()->d.verifyRelationalDomain(change(export,rows->{
                var history=rows.get("feed_source_history").getFirst();
                ((ObjectNode)history.get("payload")).put("id",UUID.randomUUID().toString());
            }))).hasMessage("RELATIONAL_DOMAIN_HISTORY_IDENTITY");
            assertThat(d.verifyRelationalDomain(export)).isEqualTo(domain);
            assertThat(d.relationalState().state()).isEqualTo(export.state());
            Files.writeString(Path.of("build/relational-domain-observation-f6d498.json"),JSON.writeValueAsString(domain));

        }
    }
    @Test void lateEnrollmentRejectsPreJoinWishEvenInsideGlobalDatasetWindow() throws Exception {
        var schema=read("api/demo-simulation-v1.schema.json");
        var people=read("src/test/resources/simulation/bundle-contract-valid/students.json");
        JsonNode late=people.get(20);String actor=late.get("logicalStudentId").asString(),account=late.get("logicalAccountId").asString();
        var joined=java.time.Instant.parse(late.get("joinedAt").asString());
        try(var d=new SimulationCommandDispatcher(schema,DATASET,DATASET,people)) {
            var join=(ObjectNode)event(1,"JOIN",Map.of("studentId",actor,"accountId",account,"academyId","academy-1","grade",3));
            join.put("actorStudentId",actor);join.put("occurredAt",joined.toString());d.execute(join);
            var create=new HashMap<String,Object>();create.put("accountId",account);create.put("wishId","late-wish");create.put("idempotencyKey","create");
            create.put("purpose","synthetic");create.put("targetAmount",5000);create.put("startDate",null);create.put("targetDate",null);create.put("photoId",null);
            var command=(ObjectNode)event(2,"CREATE",create);command.put("actorStudentId",actor);command.put("occurredAt",joined.plusSeconds(1).toString());d.execute(command);
            var export=d.relationalState();assertThat(d.verifyRelationalDomain(export).people()).isEqualTo(1);
            var forged=change(export,rows->((ObjectNode)rows.get("wish").getFirst()).put("created_at",joined.minusSeconds(1).toString()));
            assertThat(d.verifyRelationalState(forged).tables()).isEqualTo(38);
            assertThatThrownBy(()->d.verifyRelationalDomain(forged)).hasMessage("RELATIONAL_DOMAIN_BEFORE_ENROLLMENT");
            assertThat(d.verifyRelationalDomain(export).replayThrough()).isEqualTo(joined.plusSeconds(1));
        }
    }

}
