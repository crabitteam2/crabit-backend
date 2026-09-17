package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationRelationalStagingIT {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final SimulationRecapDispatcherIT f=new SimulationRecapDispatcherIT();

    @Test void actualDomainGraphStagesExactlyAndSqlFailureLeavesPublicRowsSequencesAndSchemaIntact() throws Exception {
        try(var d=new SimulationCommandDispatcher(f.schema(),f.DATASET,f.DATASET,f.people())) {
            d.execute(f.event("JOIN",f.START,Map.of("studentId",f.ACTOR,"accountId",f.ACCOUNT,"academyId","academy-1","grade",3),"APPLIED"));
            d.execute(f.event("GRANT",f.START.plusSeconds(1),Map.of("accountId",f.ACCOUNT,"amountKrw",20000,"cashEntryId","grant","budgetMonth","2026-06","scheduledAt",f.START.toString()),"APPLIED"));
            var create=new HashMap<String,Object>();create.put("accountId",f.ACCOUNT);create.put("wishId","wish");create.put("idempotencyKey","create");
            create.put("purpose","한글 '); DROP TABLE student; --");create.put("targetAmount",5000);create.put("startDate","2026-06-01");create.put("targetDate","2026-08-31");create.put("photoId",null);
            d.execute(f.event("CREATE",f.START.plusSeconds(2),create,"APPLIED"));
            d.execute(f.event("DEPOSIT",f.START.plusSeconds(3),Map.of("accountId",f.ACCOUNT,"wishId","wish","amount",4000,"expectedVersion",0,"idempotencyKey","deposit"),"APPLIED"));
            var export=d.relationalState();var before=d.preservationFingerprint();
            byte[] source=JSON.writeValueAsBytes(export.state()),saved=source.clone();
            var report=d.stageRelationalState(source);
            assertThat(report.tables()).isEqualTo(38);assertThat(report.rows()).isEqualTo(d.verifyRelationalState(export).rows());
            assertThat(report.foreignKeys()).isEqualTo(export.catalog().foreignKeys().size()).isPositive();
            assertThat(report.sourceDigest()).isEqualTo(report.readBackDigest());
            assertThat(report.publicRowsWritten()).isFalse();assertThat(report.readyForApplication()).isFalse();
            assertThat(d.preservationFingerprint()).isEqualTo(before);
            // These pass typed input and FK checks but fail actual PostgreSQL varchar/CHECK constraints.
            for(boolean length:List.of(true,false)) {
                ObjectNode bad=(ObjectNode)JSON.readTree(source);
                ObjectNode wish=(ObjectNode)bad.get("tables").get("wish").get(0);
                if(length)wish.put("purpose","가".repeat(201));else wish.put("visibility","INVALID");
                byte[] malformed=JSON.writeValueAsBytes(bad);
                assertThatCode(()->d.readRelationalState(malformed)).doesNotThrowAnyException();
                assertThatThrownBy(()->d.stageRelationalState(malformed)).isInstanceOf(DataIntegrityViolationException.class);
                assertThat(d.preservationFingerprint()).isEqualTo(before);
                assertThat(d.relationalState().state()).isEqualTo(export.state());
            }
            // Offset spelling is transport formatting; PostgreSQL preserves the same microsecond instant.
            ObjectNode offset=(ObjectNode)JSON.readTree(source);
            for(var entry:export.catalog().columns().entrySet())for(var node:offset.get("tables").get(entry.getKey())) {
                ObjectNode row=(ObjectNode)node;
                for(var column:entry.getValue())if(column.type().equals("timestamptz") && !row.get(column.name()).isNull())
                    row.put(column.name(),java.time.OffsetDateTime.parse(row.get(column.name()).asString())
                        .withOffsetSameInstant(java.time.ZoneOffset.ofHours(9)).toString());
            }
            assertThat(d.stageRelationalState(JSON.writeValueAsBytes(offset))).isEqualTo(report);
            // This is a SQL transport probe, not a valid replay/domain version. int8 must not pass through double.
            ObjectNode wide=(ObjectNode)JSON.readTree(source);
            ((ObjectNode)wide.get("tables").get("wish").get(0)).put("version",Long.MAX_VALUE);
            var wideReport=d.stageRelationalState(JSON.writeValueAsBytes(wide));
            assertThat(wideReport.sourceDigest()).isEqualTo(wideReport.readBackDigest()).isNotEqualTo(report.sourceDigest());
            // A failed transaction cannot leak staging tables or poison the next invocation.
            assertThat(d.stageRelationalState(source)).isEqualTo(report);
            assertThat(d.preservationFingerprint()).isEqualTo(before);assertThat(source).isEqualTo(saved);
            Path out=Path.of("build/simulation-relational-staging");Files.createDirectories(out);
            Files.write(out.resolve("observation.json"),JSON.writeValueAsBytes(Map.of("commands",4,"report",report,
                "sqlConstraintFailuresRolledBack",2,"repeatAfterFailure",true,"publicFingerprintPreserved",true,"sourceBytesPreserved",true)));
        }
    }
    @Test void postgresRejectsMissingStagedParentEvenWhenPublicParentExistsAndRollsBackAllTemporaryDdl() {
        try(var db=new SimulationPostgresClock()) {
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(db.dataSource());
            UUID student=UUID.randomUUID(),academy=UUID.randomUUID();
            jdbc.update("INSERT INTO student(id,nickname,age) VALUES (?, 'parent',9)",student);
            jdbc.update("INSERT INTO academy(id,name) VALUES (?, 'academy')",academy);
            jdbc.update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,clock_timestamp())",UUID.randomUUID(),student,academy);
            var export=SimulationRelationalState.capture(jdbc,f.DATASET);
            var before=SimulationPreservationFingerprint.capture(db);
            var tables=new TreeMap<>(export.state().tables());tables.put("student",List.of());
            var missing=new SimulationRelationalState.Export(export.catalog(),new SimulationRelationalState.State(
                1,"simulation-relational-state",f.DATASET,export.state().catalogDigest(),tables));
            var tx=new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.dataSource()));
            // Bypass the Java graph verifier deliberately: prove PostgreSQL itself rejects the reference.
            assertThatThrownBy(()->tx.execute(ignored->SimulationRelationalStaging.stage(jdbc,missing)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().isInstanceOf(java.sql.SQLException.class)
                .satisfies(failure->assertThat(((java.sql.SQLException)failure).getSQLState()).isEqualTo("23503"));
            assertThat(SimulationPreservationFingerprint.capture(db)).isEqualTo(before);
            var report=tx.execute(ignored->SimulationRelationalStaging.stage(jdbc,export));
            assertThat(report.foreignKeys()).isEqualTo(export.catalog().foreignKeys().size()).isPositive();
            assertThat(report.sourceDigest()).isEqualTo(report.readBackDigest());
            assertThat(SimulationPreservationFingerprint.capture(db)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relnamespace=pg_my_temp_schema() AND relname LIKE 'simulation_stage_%'",Long.class)).isZero();
        }
    }

    @Test void stagingRequiresAnOwnedTransaction() {
        assertThatThrownBy(()->SimulationRelationalStaging.stage(new org.springframework.jdbc.core.JdbcTemplate(),null))
            .hasMessage("RELATIONAL_STAGING_TRANSACTION_REQUIRED");
    }
}
