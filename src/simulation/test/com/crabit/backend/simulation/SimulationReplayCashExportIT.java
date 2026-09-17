package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import com.crabit.backend.demo.DemoSimulationCashService;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationReplayCashExportIT {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String DATASET="sha256:"+"c".repeat(64);
    @Test void capturesPersistedValuesRejectsCacheDriftAndOrphanRowsWithoutRepairingThem() {
        UUID academy=UUID.randomUUID(),student=UUID.randomUUID(),account=UUID.randomUUID();
        var identities=Map.of("ACCOUNT:a",account);
        ObjectNode command=JSON.valueToTree(Map.of("eventId","grant","sequence",1,"kind","GRANT",
            "occurredAt",SimulationCashOracle.START.toString(),"outcome",Map.of("status","APPLIED"),
            "command",Map.of("accountId","a","cashEntryId","cash-1","amountKrw",10000,
                "budgetMonth","2026-06","scheduledAt",SimulationCashOracle.START.toString())));
        JsonNode people=JSON.valueToTree(List.of(Map.of("logicalAccountId","a","joinedAt",SimulationCashOracle.START.toString())));
        JsonNode manifest=JSON.valueToTree(Map.of("datasetId",DATASET));
        var verifier=new SimulationBundleCashVerifier();
        try(var clock=new SimulationPostgresClock()) {
            clock.executeAt(SimulationCashOracle.START,s->{
                var j=s.jdbc();
                j.update("INSERT INTO academy(id,name) VALUES (?,'replay')",academy);
                j.update("INSERT INTO student(id,nickname,age,age_provenance) VALUES (?,'synthetic',9,'PROVIDED')",student);
                j.update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,?)",UUID.randomUUID(),student,academy,Timestamp.from(SimulationCashOracle.START));
                j.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",account,student,academy,Timestamp.from(SimulationCashOracle.START));
                j.update("INSERT INTO demo_simulation_dataset(dataset_id,manifest_digest,state,starts_at,ends_at) VALUES (?,?,'BUILDING',?,?)",DATASET,DATASET,Timestamp.from(SimulationCashOracle.START),Timestamp.from(SimulationCashOracle.END));
                j.update("INSERT INTO demo_simulation_account(account_id,dataset_id,logical_student_id,logical_account_id,grade,is_owner) VALUES (?,?,'s','a',3,false)",account,DATASET);
                new DemoSimulationCashService(j).apply(DATASET,"cash-1",account,DemoSimulationCashService.Kind.GRANT,10000,SimulationCashOracle.START);
                return null;
            });
            clock.executeAt(SimulationCashOracle.START,s->{
                var j=s.jdbc();var state=SimulationReplayCashExport.capture(j,DATASET,identities,List.of(command));
                assertThat(verifier.verify(manifest,people,List.of(command),state).balances().get("a").amountKrw()).isEqualTo(10000);
                // The exporter never fills fields from a forged expected command.
                var forged=command.deepCopy();((ObjectNode)forged.get("command")).put("amountKrw",20000);
                var actual=SimulationReplayCashExport.capture(j,DATASET,identities,List.of(forged));
                assertThat(actual).isEqualTo(state);
                assertThatThrownBy(()->verifier.verify(manifest,people,List.of(forged),actual)).hasMessageContaining("LEDGER_COMMAND_MISMATCH");
                // A forged cache is exported as observed and rejected against the source events.
                j.update("UPDATE demo_simulation_account SET card_funds=10001 WHERE account_id=?",account);
                var drift=SimulationReplayCashExport.capture(j,DATASET,identities,List.of(command));
                assertThat(drift.get("balances").get(0).get("amountKrw").longValue()).isEqualTo(10001);
                assertThatThrownBy(()->verifier.verify(manifest,people,List.of(command),drift)).hasMessageContaining("FINAL_BALANCE_MISMATCH");
                assertThat(j.queryForObject("SELECT card_funds FROM demo_simulation_account WHERE account_id=?",Long.class,account)).isEqualTo(10001);
                assertThatThrownBy(()->SimulationReplayCashExport.capture(j,DATASET,identities,List.of())).hasMessage("CASH_EXPORT_ORPHAN_ENTRY");
                assertThatThrownBy(()->SimulationReplayCashExport.capture(j,DATASET,Map.of("ACCOUNT:wrong",account),List.of(command))).hasMessage("CASH_EXPORT_UNKNOWN_ACCOUNT");
                return null;
            });
        }
    }
}
