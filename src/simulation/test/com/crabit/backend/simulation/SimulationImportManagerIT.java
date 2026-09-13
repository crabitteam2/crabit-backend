package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationImportManagerIT {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String D="sha256:"+"c".repeat(64), CODE="a".repeat(40);
    @Test void actualImportPreservesProviderOwnerAndDryRunRollbackThenRestoresOriginalGraph() throws Exception {
        var schema=JSON.readTree(Files.readAllBytes(Path.of("api/demo-simulation-v1.schema.json")));
        var people=JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/bundle-contract-valid/students.json")));
        var personas=JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/bundle-contract-valid/personas.json")));
        var f=new SimulationFeedContinuationIT();
        byte[] relational;
        try(var source=new SimulationCommandDispatcher(schema,D,D,people)) {
            var ordered=new ArrayList<JsonNode>();people.forEach(ordered::add);
            ordered.sort(Comparator.comparing(p->p.get("joinedAt").asString()));int n=0;
            for(var p:ordered) {
                var e=f.event(++n,"JOIN",p.get("logicalStudentId").asString(),0,Map.of("studentId",p.get("logicalStudentId").asString(),"accountId",p.get("logicalAccountId").asString(),"academyId","academy-1","grade",p.get("grade").asInt()));
                e.put("occurredAt",p.get("joinedAt").asString());source.execute(e);
            }
            int seconds=(int)Duration.between(SimulationCashOracle.START,Instant.parse(ordered.getLast().get("joinedAt").asString())).toSeconds();
            for(String suffix:List.of("00","01")) {
                String actor="student-3-"+suffix,account="account-3-"+suffix,wish="wish-"+suffix;
                source.execute(f.event(++n,"GRANT",actor,++seconds,Map.of("accountId",account,"amountKrw",20000,"cashEntryId","grant-"+suffix,"budgetMonth","2026-07","scheduledAt",SimulationCashOracle.START.plusSeconds(seconds).toString())));
                var create=new HashMap<String,Object>();create.put("accountId",account);create.put("wishId",wish);create.put("idempotencyKey","create-"+suffix);create.put("purpose","books");create.put("targetAmount",10000);create.put("startDate",null);create.put("targetDate",null);create.put("photoId",null);
                source.execute(f.event(++n,"CREATE",actor,++seconds,create));
                source.execute(f.event(++n,"DEPOSIT",actor,++seconds,Map.of("accountId",account,"wishId",wish,"amount",5000,"expectedVersion",0,"idempotencyKey","deposit-"+suffix)));
                if(suffix.equals("01")) {
                    source.execute(f.event(++n,"SHARE",actor,++seconds,Map.of("accountId",account,"wishId",wish,"expectedVersion",1,"visibility","ACADEMY")));
                    source.execute(f.event(++n,"VISIBILITY_CHANGE",actor,++seconds,Map.of("accountId",account,"wishId",wish,"expectedVersion",2,"visibility","PRIVATE")));
                    source.execute(f.event(++n,"SHARE",actor,++seconds,Map.of("accountId",account,"wishId",wish,"expectedVersion",3,"visibility","ACADEMY")));
                }
            }
            for(String suffix:List.of("00","01")) {
                String actor="student-3-"+suffix,account="account-3-"+suffix;
                source.execute(f.event(++n,"PURCHASE",actor,++seconds,Map.of("accountId",account,"cashEntryId","purchase-"+suffix,"amountKrw",17000)));
                source.execute(f.event(++n,"BALANCE_LOOKUP",actor,++seconds,Map.of("accountId",account,"observationRef","raw/mismatch-"+suffix+".json")));
            }
            assertThat(source.relationalState().state().tables().get("mismatch_notification_outbox")).hasSize(2);
            relational=JSON.writeValueAsBytes(source.relationalState().state());
        }
        try(var target=new SimulationPostgresClock()) {
            var j=new JdbcTemplate(target.dataSource());
            j.update("INSERT INTO academy(id,name) VALUES ('00000000-0000-0000-0000-000000000101','Current academy')");
            j.update("INSERT INTO student(id,nickname,age,age_provenance) VALUES ('00000000-0000-0000-0000-000000000201','Current Owner',12,'PROVIDED')");
            j.update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES ('00000000-0000-0000-0000-000000000501','00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-000000000101',clock_timestamp())");
            j.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES ('00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-000000000101',clock_timestamp())");
            var seedTx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(target.dataSource()));
            seedTx.execute(ignored->{
                UUID event=UUID.randomUUID();
                j.update("INSERT INTO ledger_event(id,account_id,event_type,account_delta,occurred_at) VALUES (?,'00000000-0000-0000-0000-000000000301','CARD_BALANCE_CHANGE',23456,clock_timestamp())",event);
                j.update("""
                    INSERT INTO balance_observation(id,account_id,status,lookup_method,actual_card_balance,
                    first_successful,previous_successful_balance,observed_at,balance_change_event_id,balance_change_event_type,balance_change_event_delta)
                    VALUES (?,'00000000-0000-0000-0000-000000000301','SUCCEEDED','USER_REQUESTED',23456,true,0,clock_timestamp(),?,'CARD_BALANCE_CHANGE',23456)
                    """,UUID.randomUUID(),event);
                return null;
            });
            j.update("INSERT INTO wish(id,account_id,academy_id,purpose,target_amount,wish_amount,state,visibility,created_at,version) VALUES (?,'00000000-0000-0000-0000-000000000301','00000000-0000-0000-0000-000000000101','Current Owner goal',50000,0,'IN_PROGRESS','PRIVATE',clock_timestamp(),0)",UUID.randomUUID());
            j.execute("CREATE ROLE ordinary_demo NOLOGIN");
            var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(target.dataSource()));
            assertThatThrownBy(()->tx.execute(s->{j.execute("SET LOCAL ROLE ordinary_demo");return j.queryForObject("SELECT demo_import_graph('{}',false)",String.class);}))
                .rootCause().hasMessageContaining("permission denied");
            tx.execute(s->{
                j.execute("SET LOCAL ROLE ordinary_demo");
                j.execute("CREATE TEMP TABLE crabit_demo_import_scope(name text,transaction_id bigint,old_rows jsonb,new_rows jsonb) ON COMMIT DROP");
                j.update("INSERT INTO crabit_demo_import_scope VALUES ('ledger_event',txid_current(),'[{}]','[{}]')");
                assertThat(j.queryForObject("SELECT demo_import_row_allowed('ledger_event','DELETE','{}',null)",Boolean.class)).isFalse();
                return null;
            });
            var manager=new SimulationImportManager(target.dataSource());var initial=manager.inspect("local-test");
            var plan=manager.prepare(relational,people,personas,schema,D,"local-test",D,CODE);
            var request=(ObjectNode)plan.request();
            assertThat(request.get("after").get("mismatch_notification_outbox")).hasSize(1);
            var outsideNotification=request.deepCopy();
            ((ObjectNode)outsideNotification.get("after").get("mismatch_notification_outbox").get(0))
                .put("adjustment_case_id",UUID.randomUUID().toString());
            assertThatThrownBy(()->manager.dryRun(outsideNotification)).rootCause()
                .hasMessageContaining("IMPORT_ROW_OUTSIDE_TARGET: mismatch_notification_outbox");
            assertThat(manager.inspect("local-test").get("snapshot")).isEqualTo(initial.get("snapshot"));
            var tampered=request.deepCopy();
            for(var student:tampered.get("after").get("student"))if(student.get("id").asString().equals("00000000-0000-0000-0000-000000000201"))
                ((ObjectNode)student).put("nickname","Synthetic overwrite");
            assertThatThrownBy(()->manager.dryRun(tampered)).rootCause().hasMessageContaining("OWNER_GRAPH_DRIFT");
            assertThat(manager.inspect("local-test").get("snapshot")).isEqualTo(initial.get("snapshot"));
            var historyTamper=request.deepCopy();boolean historyChanged=false;
            for(var row:historyTamper.get("after").get("feed_source_history"))if(row.get("source_kind").asString().equals("wish") && row.get("payload").get("account_id").asString().equals("00000000-0000-0000-0000-000000000301")) {
                ((ObjectNode)row.get("payload")).put("purpose","Synthetic history overwrite");historyChanged=true;
            }
            assertThat(historyChanged).isTrue();
            assertThatThrownBy(()->manager.dryRun(historyTamper)).rootCause().hasMessageContaining("OWNER_GRAPH_DRIFT");
            assertThat(manager.inspect("local-test").get("snapshot")).isEqualTo(initial.get("snapshot"));
            var dry=manager.dryRun(request);
            assertThat(dry.get("status").asString()).isEqualTo("DRY_RUN_READY");
            assertThat(manager.inspect("local-test").get("snapshot")).isEqualTo(initial.get("snapshot"));
            request.set("expectedAfterFingerprint",dry.get("afterFingerprint"));
            var applied=manager.execute(request);
            assertThat(applied.get("status").asString()).isEqualTo("APPLIED");
            assertThat(applied.get("authoritativeReadBack").asBoolean()).isTrue();
            assertThat(applied.get("externalConsoleVerified").asBoolean()).isFalse();
            assertThat(j.queryForObject("SELECT actual_card_balance FROM balance_observation WHERE account_id='00000000-0000-0000-0000-000000000301'",Long.class)).isEqualTo(23456L);
            assertThat(j.queryForObject("SELECT source_kind FROM balance_observation WHERE account_id='00000000-0000-0000-0000-000000000301'",String.class)).isEqualTo("PROVIDER");
            assertThat(j.queryForObject("SELECT count(*) FROM balance_observation WHERE source_kind='SIMULATION'",Long.class)).isEqualTo(2);
            assertThat(j.queryForObject("SELECT count(*) FROM mismatch_notification_outbox",Long.class)).isEqualTo(1);
            assertThat(j.queryForObject("SELECT count(*) FROM wish WHERE account_id='00000000-0000-0000-0000-000000000301'",Long.class)).isEqualTo(1);
            var ownerProvider=org.mockito.Mockito.mock(com.crabit.backend.balance.DemoHttpCardBalanceProvider.class);
            var provider=new com.crabit.backend.demo.DemoSimulationBalanceProvider(ownerProvider,j);
            UUID synthetic=j.queryForObject("SELECT account_id FROM demo_simulation_account WHERE logical_account_id='account-3-01'",UUID.class);
            assertThat(provider.lookup(synthetic)).isInstanceOf(com.crabit.backend.balance.CardBalanceProviderResult.Success.class);
            org.mockito.Mockito.verifyNoInteractions(ownerProvider);
            provider.lookup(UUID.fromString("00000000-0000-0000-0000-000000000301"));
            org.mockito.Mockito.verify(ownerProvider).lookup(UUID.fromString("00000000-0000-0000-0000-000000000301"));
            var env=new org.springframework.mock.env.MockEnvironment();for(int grade=3;grade<=6;grade++)env.setProperty("CRABIT_DEMO_TOKEN_GRADE_"+grade,"test-token-"+grade);
            assertThat(new com.crabit.backend.demo.DemoRepresentativeRegistry(j,env).all()).hasSize(4);
            var coverage=new com.crabit.backend.demo.DemoSimulationCoverage(j);
            var account=j.queryForMap("SELECT a.id,a.student_id,a.academy_id FROM card_balance_account a JOIN demo_simulation_account s ON s.account_id=a.id WHERE s.logical_account_id='account-3-01'");
            Instant from=Instant.parse("2026-07-31T15:00:00Z"),to=Instant.parse("2026-08-31T15:00:00Z"),ordinary=Instant.parse("2026-09-09T15:00:00Z");
            assertThat(coverage.collectionStart((UUID)account.get("id"),(UUID)account.get("student_id"),(UUID)account.get("academy_id"),from,to,ordinary)).isEqualTo(SimulationCashOracle.START);
            assertThat(coverage.collectionStart(UUID.fromString("00000000-0000-0000-0000-000000000301"),UUID.fromString("00000000-0000-0000-0000-000000000201"),(UUID)account.get("academy_id"),from,to,ordinary)).isEqualTo(ordinary);
            assertThat(manager.execute(request).get("status").asString()).isEqualTo("NO_OP");
            j.update("UPDATE academy SET name='drift' WHERE id='00000000-0000-0000-0000-000000000101'");
            assertThatThrownBy(()->manager.prepareRestore(plan.backup(),plan.backupDigest(),D,CODE)).hasMessage("RESTORE_DRIFT");
            j.update("UPDATE academy SET name='Current academy' WHERE id='00000000-0000-0000-0000-000000000101'");
            var restore=(ObjectNode)manager.prepareRestore(plan.backup(),plan.backupDigest(),D,CODE).request();
            restore.set("expectedAfterFingerprint",manager.dryRun(restore).get("afterFingerprint"));
            assertThat(manager.execute(restore).get("status").asString()).isEqualTo("RESTORED");
            var restored=manager.inspect("local-test");
            for(String table:initial.get("tables").propertyNames())if(!table.equals("demo_simulation_dataset"))
                assertThat(restored.get("tables").get(table)).as(table).isEqualTo(initial.get("tables").get(table));
            assertThat(restored.get("snapshot").get("sequences")).isEqualTo(initial.get("snapshot").get("sequences"));
            assertThat(restored.get("journal")).hasSize(2);
            Path output=Path.of("build/simulation-import-manager");Files.createDirectories(output);
            Files.write(output.resolve("apply.json"),JSON.writeValueAsBytes(applied));
            Files.write(output.resolve("restored.json"),JSON.writeValueAsBytes(restored));
        }
    }
}
