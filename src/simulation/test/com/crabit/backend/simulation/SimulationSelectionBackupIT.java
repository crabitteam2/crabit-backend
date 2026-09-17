package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SimulationSelectionBackupIT {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final SimulationRecapDispatcherIT f=new SimulationRecapDispatcherIT();
    private void join(SimulationCommandDispatcher d) {
        d.execute(f.event("JOIN",f.START,Map.of("studentId",f.ACTOR,"accountId",f.ACCOUNT,"academyId","academy-1","grade",3),"APPLIED"));
    }
    private static Map<String,List<JsonNode>> all(SimulationRelationalState.Export export) {
        var primary=new TreeMap<String,List<String>>();
        export.catalog().keys().stream().filter(SimulationRelationalState.Key::primary).forEach(k->primary.put(k.table(),k.columns()));
        var selection=new TreeMap<String,List<JsonNode>>();
        export.state().tables().forEach((table,rows)->selection.put(table,rows.stream().map(row->{
            var key=JSON.createObjectNode();for(String column:primary.get(table))key.set(column,row.get(column));return (JsonNode)key;
        }).toList()));return selection;
    }
    @Test void capturesOriginalGraphAndTextBytesWithoutExpandingSelectionOrMutatingDatabase() throws Exception {
        try(var d=new SimulationCommandDispatcher(f.schema(),f.DATASET,f.DATASET,f.people())) {
            join(d);
            d.execute(f.event("GRANT",f.START.plusSeconds(1),Map.of("accountId",f.ACCOUNT,"amountKrw",20000,"cashEntryId","grant","budgetMonth","2026-06","scheduledAt",f.START.toString()),"APPLIED"));
            var create=new HashMap<String,Object>();create.put("accountId",f.ACCOUNT);create.put("wishId","wish");create.put("idempotencyKey","create");
            create.put("purpose","백업 '); DROP TABLE student; --");create.put("targetAmount",5000);create.put("startDate",null);create.put("targetDate",null);create.put("photoId",null);
            d.execute(f.event("CREATE",f.START.plusSeconds(2),create,"APPLIED"));
            d.execute(f.event("DEPOSIT",f.START.plusSeconds(3),Map.of("accountId",f.ACCOUNT,"wishId","wish","amount",4000,"expectedVersion",0,"idempotencyKey","deposit"),"APPLIED"));
            var export=d.relationalState();var before=d.preservationFingerprint();var selection=all(export);
            var backup=d.backupSelection(selection,before.digest());
            assertThat(backup.rows()).isEqualTo(d.verifyRelationalState(export).rows());
            JsonNode saved=JSON.readTree(backup.bytes());
            export.state().tables().forEach((table,rows)->{
                var actual=new ArrayList<JsonNode>();saved.get("tables").get(table).forEach(actual::add);
                assertThat(actual).containsExactlyInAnyOrderElementsOf(rows);
            });
            assertThat(saved.get("boundary").get("coveredReferencesClosed").booleanValue()).isTrue();
            assertThat(saved.get("restoreSupported").booleanValue()).isFalse();
            assertThat(saved.get("readyForApplication").booleanValue()).isFalse();
            assertThat(saved.get("tables").has("relationship_cursor_key")).isFalse();
            assertThat(backup.digest()).isEqualTo(SimulationBundleReader.digest(backup.bytes()));
            assertThat(d.verifySelectionBackup(backup.bytes(),backup.digest()).bytes()).isEqualTo(backup.bytes());
            assertThatThrownBy(()->d.verifySelectionBackup(backup.bytes(),f.DATASET)).hasMessage("SELECTION_BACKUP_CHECKSUM_MISMATCH");
            var altered=(tools.jackson.databind.node.ObjectNode)JSON.readTree(backup.bytes());
            ((tools.jackson.databind.node.ObjectNode)altered.get("tables").get("wish").get(0)).put("purpose","forged same-count value");
            byte[] forged=SimulationBundleReader.canonical(altered).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThatThrownBy(()->d.verifySelectionBackup(forged,SimulationBundleReader.digest(forged))).hasMessage("SELECTION_BACKUP_CONTENT_MISMATCH");
            var extra=(tools.jackson.databind.node.ObjectNode)JSON.readTree(backup.bytes());extra.put("restoreSupported",true);
            byte[] promoted=SimulationBundleReader.canonical(extra).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThatThrownBy(()->d.verifySelectionBackup(promoted,SimulationBundleReader.digest(promoted))).hasMessage("SELECTION_BACKUP_CONTENT_MISMATCH");
            var reversed=new TreeMap<String,List<JsonNode>>();selection.forEach((t,keys)->{
                var copy=new ArrayList<>(keys);Collections.reverse(copy);reversed.put(t,copy);
            });
            assertThat(d.backupSelection(reversed,before.digest()).bytes()).isEqualTo(backup.bytes());
            byte[] detached=backup.bytes();Arrays.fill(detached,(byte)0);
            assertThat(SimulationBundleReader.digest(backup.bytes())).isEqualTo(backup.digest());
            var one=d.backupSelection(Map.of("student",selection.get("student")),before.digest());
            assertThat(one.rows()).isEqualTo(1);
            JsonNode cut=JSON.readTree(one.bytes());
            assertThat(cut.get("boundary").get("coveredReferencesClosed").booleanValue()).isFalse();
            assertThat(cut.get("boundary").get("foreignKeys").get("crossings").size()).isPositive();
            assertThat(cut.get("tables").get("card_balance_account").size()).isZero();
            assertThat(d.preservationFingerprint()).isEqualTo(before);
            assertThat(d.relationalState().state()).isEqualTo(export.state());
            Path output=Path.of("build/simulation-selection-backup");Files.createDirectories(output);
            Files.write(output.resolve("backup.json"),backup.bytes());
            Files.write(output.resolve("student-cut.json"),one.bytes());
            Files.write(output.resolve("observation.json"),JSON.writeValueAsBytes(Map.of("commands",4,"rows",backup.rows(),
                "backupDigest",backup.digest(),"selectedStudentRows",one.rows(),"databaseUnchanged",true,"restoreSupported",false)));
        }
    }
    @Test void staleFingerprintMalformedOrEmptyScopeFailsWithoutErasingNewEvents() throws Exception {
        try(var d=new SimulationCommandDispatcher(f.schema(),f.DATASET,f.DATASET,f.people())) {
            join(d);var export=d.relationalState();var selection=all(export);var old=d.preservationFingerprint();
            var saved=d.backupSelection(selection,old.digest());
            d.execute(f.event("GRANT",f.START.plusSeconds(1),Map.of("accountId",f.ACCOUNT,"amountKrw",20000,"cashEntryId","grant","budgetMonth","2026-06","scheduledAt",f.START.toString()),"APPLIED"));
            var current=d.preservationFingerprint();
            assertThatThrownBy(()->d.backupSelection(selection,old.digest())).hasMessage("SELECTION_BACKUP_REVISION_CONFLICT");
            assertThatThrownBy(()->d.verifySelectionBackup(saved.bytes(),saved.digest())).hasMessage("SELECTION_BACKUP_REVISION_CONFLICT");
            assertThatThrownBy(()->d.backupSelection(Map.of(),current.digest())).hasMessage("SELECTION_BACKUP_EMPTY");
            assertThatThrownBy(()->d.backupSelection(Map.of("relationship_cursor_key",List.of()),current.digest())).hasMessage("GRAPH_BOUNDARY_UNKNOWN_TABLE");
            var key=selection.get("student").getFirst();
            assertThatThrownBy(()->d.backupSelection(Map.of("student",List.of(key,key)),current.digest())).hasMessage("GRAPH_BOUNDARY_DUPLICATE_SELECTION");
            assertThat(d.preservationFingerprint()).isEqualTo(current);
            assertThat(d.backupSelection(all(d.relationalState()),current.digest()).rows()).isPositive();
        }
    }
}
