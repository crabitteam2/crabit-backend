package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationReplayRunIT {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String DATASET="sha256:"+"c".repeat(64);
    @TempDir Path temp;
    private JsonNode schema() throws Exception { return JSON.readTree(Files.readAllBytes(Path.of("api/demo-simulation-v1.schema.json"))); }
    private JsonNode people() throws Exception { return JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/bundle-contract-valid/students.json"))); }
    private byte[] event(int sequence,String kind,Map<String,?> command,String status) {
        var e=JSON.createObjectNode();e.put("eventId","event-"+sequence);e.put("sequence",sequence);
        e.put("occurredAt",SimulationCashOracle.START.plusSeconds(sequence-1).toString());e.put("actorStudentId","student-3-00");e.put("kind",kind);
        e.putArray("causes");e.set("command",JSON.valueToTree(command));
        e.putObject("outcome").put("status",status).put("resultRef","raw/results/"+sequence+".json");
        e.putArray("artifactRefs").add("raw/results/"+sequence+".json");
        // Deliberate whitespace must survive exactly in raw requests.
        return ("  "+JSON.writeValueAsString(e)+" \t").getBytes(StandardCharsets.UTF_8);
    }
    private byte[] join() { return event(1,"JOIN",Map.of("studentId","student-3-00","accountId","account-3-00","academyId","academy-1","grade",3),"APPLIED"); }
    private List<byte[]> commands(String purchaseStatus) {
        return List.of(join(),event(2,"GRANT",Map.of("accountId","account-3-00","amountKrw",10000,"cashEntryId","grant-1",
            "budgetMonth","2026-06","scheduledAt",SimulationCashOracle.START.toString()),"APPLIED"),
            event(3,"PURCHASE",Map.of("accountId","account-3-00","amountKrw",10001,"cashEntryId","purchase-1"),purchaseStatus));
    }
    private void verifyIndex(Path output) throws Exception {
        var index=JSON.readTree(Files.readAllBytes(output.resolve("raw/index.json")));
        SimulationBundleReader.validate(schema().get("$defs").get("rawIndex"),index,"rawIndex");
        String prior="";
        for(JsonNode record:index.get("records")) {
            String name=record.get("path").asString();assertThat(name.compareTo(prior)).isPositive();prior=name;
            byte[] bytes=Files.readAllBytes(output.resolve(name));
            assertThat(record.get("byteLength").longValue()).isEqualTo(bytes.length);
            assertThat(record.get("sha256").asString()).isEqualTo(SimulationBundleReader.digest(bytes));
            assertThat(record.get("service").asString()).isEqualTo("BACKEND");assertThat(record.get("modelVersion").isNull()).isTrue();
        }
    }
    @Test void dormancyAnnotationIsPreservedInRawAndNormalizedReplayEvidence() throws Exception {
        var inputs=new ArrayList<>(commands("REJECTED"));
        var e=(ObjectNode)JSON.readTree(event(4,"RETURN_FROM_DORMANCY",Map.of("priorDormancyEventId","event-1"),"APPLIED"));
        e.put("occurredAt",SimulationCashOracle.START.plus(java.time.Duration.ofDays(7)).toString());
        e.putArray("causes").add("event-1");inputs.add(JSON.writeValueAsBytes(e));
        Path output=temp.resolve("dormancy");
        var observation=SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,output);
        assertThat(observation.get("dormancyAnnotationsVerified")).isEqualTo(true);
        assertThat(observation.get("dormancyAnnotationCount")).isEqualTo(1);
        var raw=JSON.readTree(Files.readAllBytes(output.resolve("raw/results/4.json")));
        assertThat(raw.get("inactiveDuration").asString()).isEqualTo("PT168H");
        var normalized=JSON.readTree(Files.readAllBytes(output.resolve("normalized-responses.json")));
        assertThat(normalized.get("events").get(3).get("response")).isEqualTo(raw);
        assertThat(observation.get("readyForApplication")).isEqualTo(false);
        verifyIndex(output);
    }

    @Test void recordsExactCommandsActualCommittedResponsesAndRejectedAttemptWithoutOverwriting() throws Exception {
        Path output=temp.resolve("replay");var commands=commands("REJECTED");
        var observation=SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),commands,output);
        assertThat(observation.get("status")).isEqualTo("REPLAYED_PARTIAL_VALIDATION");
        assertThat(observation.get("completedEvents")).isEqualTo(3);
        assertThat(observation.get("validationDatabaseUnchanged")).isEqualTo(true);
        var before=(SimulationPreservationFingerprint.Snapshot)observation.get("validationPreservationBefore");
        var after=(SimulationPreservationFingerprint.Snapshot)observation.get("validationPreservationAfter");
        assertThat(before).isEqualTo(after);
        assertThat(before.tables()).containsKeys("relationship_cursor_key","flyway_schema_history","wish_photo_cleanup_work");
        var stored=JSON.readTree(Files.readAllBytes(output.resolve("replay-observation.json")));
        assertThat(stored.get("validationPreservationBefore").get("digest").asString()).isEqualTo(before.digest());
        assertThat(stored.get("validationPreservationAfter").get("digest").asString()).isEqualTo(after.digest());
        assertThat(observation.get("readyForApplication")).isEqualTo(false);
        assertThat(Files.readAllBytes(output.resolve("raw/requests/event-1.json"))).isEqualTo(commands.getFirst());
        var joined=JSON.readTree(Files.readAllBytes(output.resolve("raw/results/1.json")));
        assertThat(joined.get("joinedAt").asString()).isEqualTo(SimulationCashOracle.START.toString());
        assertThat(JSON.readTree(Files.readAllBytes(output.resolve("raw/results/2.json"))).get("cardFunds").longValue()).isEqualTo(10000);
        assertThat(JSON.readTree(Files.readAllBytes(output.resolve("raw/results/3.json"))).get("code").asString()).isEqualTo("CASH_OUT_OF_RANGE");
        verifyIndex(output);
        assertThat(observation.get("typedIdentityMapExported")).isEqualTo(true);
        byte[] mappingBytes=Files.readAllBytes(output.resolve("id-map.json"));
        assertThat(observation.get("identityMapDigest")).isEqualTo(SimulationBundleReader.digest(mappingBytes));
        var mapping=JSON.readTree(mappingBytes);
        SimulationBundleReader.validate(schema().get("$defs").get("idMap"),mapping,"idMap");
        // The other 99 preallocated student/account UUID pairs are not persisted JOIN evidence.
        assertThat(mapping.get("entries")).hasSize(3);
        assertThat(observation.get("observationStateExported")).isEqualTo(true);
        assertThat(observation.get("observationReconciliationPerformed")).isEqualTo(true);
        byte[] observations=Files.readAllBytes(output.resolve("state/observations.json"));
        assertThat(observation.get("observationStateDigest")).isEqualTo(SimulationBundleReader.digest(observations));
        assertThat(JSON.readTree(observations).get("observations")).isEmpty();
        assertThat(observation.get("cashStateExported")).isEqualTo(true);
        assertThat(observation.get("cashReconciliationPerformed")).isEqualTo(true);
        assertThat(observation.get("cashAccounts")).isEqualTo(1);
        assertThat(observation.get("allStudentsJoined")).isEqualTo(false);
        assertThat(observation.get("cashCommands")).isEqualTo(Map.of("applied",1,"rejected",1,"failed",0));
        byte[] cashBytes=Files.readAllBytes(output.resolve("state/export.json"));
        assertThat(observation.get("cashStateDigest")).isEqualTo(SimulationBundleReader.digest(cashBytes));
        var cash=JSON.readTree(cashBytes);
        SimulationBundleReader.validate(schema().get("$defs").get("cashState"),cash,"cashState");
        assertThat(cash.get("balances")).hasSize(1);
        assertThat(cash.get("balances").get(0).get("amountKrw").longValue()).isEqualTo(10000);
        assertThat(cash.get("ledger")).hasSize(1);
        assertThat(cash.get("ledger").get(0).get("eventId").asString()).isEqualTo("event-2");
        assertThat(observation.get("responseNormalizationPerformed")).isEqualTo(true);
        byte[] normalizedBytes=Files.readAllBytes(output.resolve("normalized-responses.json"));
        assertThat(observation.get("normalizedResponseDigest")).isEqualTo(SimulationBundleReader.digest(normalizedBytes));
        var normalized=JSON.readTree(normalizedBytes);
        assertThat(normalized.get("events").get(0).get("response").get("studentId").asString()).isEqualTo("STUDENT:student-3-00");
        assertThat(normalized.get("events").get(2).get("status").asString()).isEqualTo("REJECTED");
        byte[] original=Files.readAllBytes(output.resolve("replay-observation.json"));
        assertThatThrownBy(()->SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),commands,output)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(output.resolve("replay-observation.json"))).isEqualTo(original);
    }
    @Test void hundredJoinedAccountsAndEqualTimeCashCommandsReproduceAcrossTwoIndependentDatabases() throws Exception {
        List<JsonNode> sorted=new ArrayList<>();people().forEach(sorted::add);
        sorted.sort(Comparator.comparing(person->person.get("joinedAt").asString()));
        List<byte[]> inputs=new ArrayList<>();int sequence=0;
        for(JsonNode person:sorted) {
            String student=person.get("logicalStudentId").asString(),account=person.get("logicalAccountId").asString();
            String when=person.get("joinedAt").asString();
            String month=java.time.YearMonth.from(java.time.Instant.parse(when).atZone(java.time.ZoneId.of("Asia/Seoul"))).toString();
            List<Map<String,?>> commands=List.of(
                Map.of("studentId",student,"accountId",account,"academyId","academy-1","grade",person.get("grade").intValue()),
                Map.of("accountId",account,"amountKrw",10000,"cashEntryId","grant-"+account,"budgetMonth",month,"scheduledAt",when),
                Map.of("accountId",account,"amountKrw",3000,"cashEntryId","purchase-"+account),
                Map.of("accountId",account,"amountKrw",7001,"cashEntryId","rejected-"+account));
            for(int i=0;i<commands.size();i++) {
                var e=(ObjectNode)JSON.readTree(event(++sequence,List.of("JOIN","GRANT","PURCHASE","PURCHASE").get(i),commands.get(i),i==3?"REJECTED":"APPLIED"));
                e.put("actorStudentId",student);e.put("occurredAt",when);inputs.add(JSON.writeValueAsBytes(e));
            }
        }
        Path a=temp.resolve("hundred-a"),b=temp.resolve("hundred-b");
        var first=SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,a);
        var second=SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,b);
        for(var observation:List.of(first,second)) {
            assertThat(observation.get("allStudentsJoined")).isEqualTo(true);
            assertThat(observation.get("completedEvents")).isEqualTo(400);
            assertThat(observation.get("cashCommands")).isEqualTo(Map.of("applied",200,"rejected",100,"failed",0));
            assertThat(observation.get("cashAccounts")).isEqualTo(100);
            assertThat(observation.get("monthlyBudgetReconciliationPerformed")).isEqualTo(true);
            var budgets=(SimulationMonthlyBudgetVerifier.Verification)observation.get("monthlyBudgetVerification");
            assertThat(budgets.completeMonths()).isEqualTo(80);
            assertThat(budgets.partialMonths()).isEqualTo(100);
            assertThat(budgets.months().stream().filter(month->month.coverage().equals("COMPLETE")))
                .allMatch(month->month.grantedKrw()==10000 && month.appliedGrants()==1);
            assertThat(observation.get("readyForApplication")).isEqualTo(false);
            assertThat(observation.get("fullDatasetValidationPerformed")).isEqualTo(false);
        }
        byte[] bytes=Files.readAllBytes(a.resolve("state/export.json"));
        assertThat(bytes).isEqualTo(Files.readAllBytes(b.resolve("state/export.json")));
        assertThat(first.get("cashStateDigest")).isEqualTo(second.get("cashStateDigest"));
        assertThat(first.get("normalizedResponseDigest")).isEqualTo(second.get("normalizedResponseDigest"));
        assertThat(first.get("typedIdentityMappings")).isEqualTo(201);
        assertThat(first.get("logicalIdentityDigest")).isEqualTo(second.get("logicalIdentityDigest"));
        assertThat(first.get("identityMapDigest")).isNotEqualTo(second.get("identityMapDigest"));
        assertThat(first.get("normalizedRelationalDigest")).isEqualTo(second.get("normalizedRelationalDigest"));
        assertThat(first.get("allRelationalRuntimeValuesNormalized")).isEqualTo(true);
        assertThat(Files.readAllBytes(a.resolve("normalized-relational.json"))).isEqualTo(Files.readAllBytes(b.resolve("normalized-relational.json")));
        assertThat(first.get("backendLogicalProjectionExported")).isEqualTo(true);
        byte[] backendA=Files.readAllBytes(a.resolve("normalized-backend.json"));
        assertThat(backendA).isEqualTo(Files.readAllBytes(b.resolve("normalized-backend.json")));
        assertThat(first.get("backendLogicalDigest")).isEqualTo(SimulationBundleReader.digest(backendA));
        assertThat(second.get("backendLogicalDigest")).isEqualTo(first.get("backendLogicalDigest"));
        var backend=SimulationBundleReader.parse(backendA);
        assertThat(backend.get("commands")).hasSize(400);
        assertThat(backend.get("tables").propertyNames()).hasSize(38);
        assertThat(backend.get("fullDatasetValidationPerformed").booleanValue()).isFalse();

        // Independent UUID allocation really differed: this is not a copied output comparison.
        assertThat(Files.readAllBytes(a.resolve("execution-identities.json"))).isNotEqualTo(Files.readAllBytes(b.resolve("execution-identities.json")));
        var state=JSON.readTree(bytes);assertThat(state.get("ledger")).hasSize(200);
        for(JsonNode balance:state.get("balances")) {
            assertThat(balance.get("amountKrw").longValue()).isEqualTo(7000);
            assertThat(balance.get("sequence").longValue()).isEqualTo(2);
        }
        Files.writeString(Path.of("build/cash-export-observation-516139.json"),JSON.writeValueAsString(Map.of(
            "independentDatabases",2,"commandsPerDatabase",400,"cashAccounts",100,"cashStateDigest",first.get("cashStateDigest"),
            "normalizedResponseDigest",first.get("normalizedResponseDigest"),"rawIdentitiesDiffer",true,
            "fullHistoryValidated",false,"readyForApplication",false)));
    }

    @Test void journalsActualAllocationRowsAndDigestAfterMoneyExecution() throws Exception {
        Path output=temp.resolve("allocation");var inputs=new ArrayList<>(commands("REJECTED"));
        var create=new HashMap<String,Object>();create.put("accountId","account-3-00");create.put("wishId","wish");
        create.put("idempotencyKey","create");create.put("purpose","synthetic");create.put("targetAmount",5000);
        create.put("startDate",null);create.put("targetDate",null);create.put("photoId",null);
        inputs.add(event(4,"CREATE",create,"APPLIED"));
        inputs.add(event(5,"DEPOSIT",Map.of("accountId","account-3-00","wishId","wish","idempotencyKey","deposit","amount",4000,"expectedVersion",0),"APPLIED"));
        var observation=SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,output);
        assertThat(observation.get("allocationStateExported")).isEqualTo(true);
        assertThat(observation.get("allocationReconciliationPerformed")).isEqualTo(true);
        byte[] bytes=Files.readAllBytes(output.resolve("state/allocation.json"));
        assertThat(observation.get("allocationStateDigest")).isEqualTo(SimulationBundleReader.digest(bytes));
        var state=JSON.readTree(bytes);
        assertThat(state.get("wishes").get(0).get("amount").longValue()).isEqualTo(4000);
        assertThat(state.get("wishes").get(0).get("createdAt").asString()).isEqualTo(SimulationCashOracle.START.plusSeconds(3).toString());
        assertThat(state.get("effects")).hasSize(1);
        assertThat(observation.get("relationalStateExported")).isEqualTo(true);
        assertThat(observation.get("relationalReferencesVerified")).isEqualTo(true);
        assertThat(observation.get("behaviorReconciliationPerformed")).isEqualTo(true);
        byte[] graph=Files.readAllBytes(output.resolve("state/relational.json"));
        assertThat(observation.get("relationalStateDigest")).isEqualTo(SimulationBundleReader.digest(graph));
        assertThat(JSON.readTree(graph).get("tables").get("historical_balance_checkpoint")).isNotEmpty();
        assertThat(JSON.readTree(graph).get("tables").has("relationship_cursor_key")).isFalse();
        assertThat(observation.get("fullDatasetValidationPerformed")).isEqualTo(false);
    }

    @Test void completedMonthFailurePreservesActualCashAndNeverClaimsBudgetOrFullValidation() throws Exception {
        Path output=temp.resolve("underfunded-month");
        var grant=event(2,"GRANT",Map.of("accountId","account-3-00","amountKrw",9999,"cashEntryId","grant-1",
            "budgetMonth","2026-06","scheduledAt",SimulationCashOracle.START.toString()),"APPLIED");
        var july=(ObjectNode)JSON.readTree(event(3,"PURCHASE",Map.of("accountId","account-3-00","amountKrw",1,"cashEntryId","purchase-1"),"APPLIED"));
        july.put("occurredAt","2026-06-30T15:00:00Z");
        assertThatThrownBy(()->SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),
            List.of(join(),grant,JSON.writeValueAsBytes(july)),output))
            .hasMessage("MONTHLY_BUDGET_COMPLETE_MONTH_RANGE:account-3-00:2026-06");
        var observation=JSON.readTree(Files.readAllBytes(output.resolve("replay-observation.json")));
        assertThat(observation.get("completedEvents").intValue()).isEqualTo(3);
        assertThat(observation.get("cashReconciliationPerformed").booleanValue()).isTrue();
        assertThat(observation.get("monthlyBudgetReconciliationPerformed").booleanValue()).isFalse();
        assertThat(observation.get("readyForApplication").booleanValue()).isFalse();
        assertThat(observation.get("status").asString()).isEqualTo("FAILED");
        var cash=JSON.readTree(Files.readAllBytes(output.resolve("state/export.json")));
        assertThat(cash.get("ledger")).hasSize(2);
        assertThat(cash.get("balances").get(0).get("amountKrw").longValue()).isEqualTo(9998);
        verifyIndex(output);
        assertThat(output.resolve("normalized-backend.json")).doesNotExist();
    }

    @Test void delayedGrantInActualDatabaseCannotSatisfyThePreviousMonthsMinimum() throws Exception {
        Path output=temp.resolve("delayed-underfunded-month");
        var delayed=(ObjectNode)JSON.readTree(event(2,"GRANT",Map.of("accountId","account-3-00","amountKrw",20000,
            "cashEntryId","delayed-grant","budgetMonth","2026-06","scheduledAt",SimulationCashOracle.START.toString()),"APPLIED"));
        delayed.put("occurredAt","2026-06-30T15:00:00Z");
        byte[] request=JSON.writeValueAsBytes(delayed);
        assertThatThrownBy(()->SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),List.of(join(),request),output))
            .hasMessage("MONTHLY_BUDGET_COMPLETE_MONTH_RANGE:account-3-00:2026-06");
        var report=JSON.readTree(Files.readAllBytes(output.resolve("replay-observation.json")));
        assertThat(report.get("cashReconciliationPerformed").booleanValue()).isTrue();
        assertThat(report.get("monthlyBudgetReconciliationPerformed").booleanValue()).isFalse();
        assertThat(report.get("readyForApplication").booleanValue()).isFalse();
        assertThat(report.get("status").asString()).isEqualTo("FAILED");
        var cash=JSON.readTree(Files.readAllBytes(output.resolve("state/export.json")));
        assertThat(cash.get("ledger").get(0).get("occurredAt").asString()).isEqualTo("2026-06-30T15:00:00Z");
        assertThat(cash.get("balances").get(0).get("amountKrw").longValue()).isEqualTo(20000);
        assertThat(Files.readAllBytes(output.resolve("raw/requests/event-2.json"))).isEqualTo(request);
        verifyIndex(output);
        assertThat(output.resolve("normalized-backend.json")).doesNotExist();
    }
    @Test void actualDatabaseReceiptReportSeparatesScheduleFromRealizedMonth() throws Exception {
        Path output=temp.resolve("delayed-funded-month");
        var june=event(2,"GRANT",Map.of("accountId","account-3-00","amountKrw",10000,"cashEntryId","june-grant",
            "budgetMonth","2026-06","scheduledAt",SimulationCashOracle.START.toString()),"APPLIED");
        var delayed=(ObjectNode)JSON.readTree(event(3,"GRANT",Map.of("accountId","account-3-00","amountKrw",20000,
            "cashEntryId","delayed-grant","budgetMonth","2026-06","scheduledAt",SimulationCashOracle.START.toString()),"APPLIED"));
        delayed.put("occurredAt","2026-06-30T15:00:00Z");
        var report=SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),List.of(join(),june,JSON.writeValueAsBytes(delayed)),output);
        var months=((SimulationMonthlyBudgetVerifier.Verification)report.get("monthlyBudgetVerification")).months();
        assertThat(months).containsExactly(
            new SimulationMonthlyBudgetVerifier.Month("account-3-00","2026-06","COMPLETE",10000,1),
            new SimulationMonthlyBudgetVerifier.Month("account-3-00","2026-07","PARTIAL",20000,1));
        assertThat(report.get("readyForApplication")).isEqualTo(false);
        var request=JSON.readTree(Files.readAllBytes(output.resolve("raw/requests/event-3.json")));
        assertThat(request.get("command").get("budgetMonth").asString()).isEqualTo("2026-06");
        verifyIndex(output);
    }

    @Test void preservesMismatchResponseAndStopsBeforeFollowingCommand() throws Exception {
        Path output=temp.resolve("failed");List<byte[]> inputs=new ArrayList<>(commands("APPLIED"));
        inputs.add(event(4,"PURCHASE",Map.of("accountId","account-3-00","amountKrw",1,"cashEntryId","never-run"),"APPLIED"));
        assertThatThrownBy(()->SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,output)).hasMessageContaining("OUTCOME_MISMATCH");
        assertThat(JSON.readTree(Files.readAllBytes(output.resolve("raw/results/3.json"))).get("code").asString()).isEqualTo("CASH_OUT_OF_RANGE");
        assertThat(output.resolve("raw/requests/event-4.json")).doesNotExist();
        var observation=JSON.readTree(Files.readAllBytes(output.resolve("replay-observation.json")));
        assertThat(observation.get("status").asString()).isEqualTo("FAILED");
        assertThat(observation.get("completedEvents").intValue()).isEqualTo(2);
        assertThat(observation.get("lastAttemptedEventId").asString()).isEqualTo("event-3");
        verifyIndex(output);
        assertThat(output.resolve("normalized-responses.json")).doesNotExist();
        assertThat(output.resolve("state/export.json")).doesNotExist();
        assertThat(observation.get("cashReconciliationPerformed").booleanValue()).isFalse();
        assertThat(observation.get("responseNormalizationPerformed").booleanValue()).isFalse();
    }
    @Test void unsupportedLateCommandAndCaseCollisionFailBeforeCreatingOutput() throws Exception {
        var inputs=new ArrayList<>(commands("REJECTED"));
        inputs.add(event(4,"CLOSE_WEEK",Map.of("accountId","account-3-00",
            "startInclusive","2026-06-01","endExclusive","2026-06-08","generationId","week",
            "snapshotRef","raw/snapshot.json","requestRef","raw/request.json",
            "responseRef","raw/response.json","storedStateRef","raw/stored.json"),"APPLIED"));
        Path output=temp.resolve("unsupported");
        assertThatThrownBy(()->SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),inputs,output)).hasMessageContaining("RECAP_CONFIGURATION_REQUIRED");
        assertThat(output).doesNotExist();
        var collision=(ObjectNode)JSON.readTree(inputs.get(1));
        ((ObjectNode)collision.get("outcome")).put("resultRef","raw/REQUESTS/event-1.json");
        collision.putArray("artifactRefs").add("raw/REQUESTS/event-1.json");
        assertThatThrownBy(()->SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),List.of(join(),JSON.writeValueAsBytes(collision)),output))
            .hasMessage("REPLAY_RAW_PATH_COLLISION");assertThat(output).doesNotExist();
    }
    @Test void journalRefusesTraversalSymlinkParentsAndExistingFiles() throws Exception {
        var journal=new SimulationReplayJournal(temp.resolve("journal"),DATASET);
        assertThatThrownBy(()->journal.raw("raw/../escape.json","e","REQUEST",new byte[0])).hasMessage("REPLAY_RAW_PATH");
        Path elsewhere=Files.createDirectory(temp.resolve("elsewhere"));
        Files.createSymbolicLink(journal.root().resolve("raw"),elsewhere);
        assertThatThrownBy(()->journal.raw("raw/escape.json","e","REQUEST",new byte[0])).hasMessage("REPLAY_PARENT_CHANGED");
        assertThat(elsewhere.resolve("escape.json")).doesNotExist();
        var second=new SimulationReplayJournal(temp.resolve("second"),DATASET);
        Files.createDirectory(second.root().resolve("raw"));Files.writeString(second.root().resolve("raw/original.json"),"original");
        assertThatThrownBy(()->second.raw("raw/original.json","e","RESPONSE",new byte[]{1})).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(second.root().resolve("raw/original.json"))).isEqualTo("original");
    }
    @Test void outputInsideSourceCannotModifyAdmittedBundle() throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("original"),"untouched");
        assertThatThrownBy(()->SimulationReplayRun.run(source,Path.of("api/demo-simulation-v1.schema.json"),DATASET,source.resolve("replay")))
            .hasMessage("REPLAY_OUTPUT_INSIDE_SOURCE");
        assertThat(source.resolve("replay")).doesNotExist();
        assertThat(Files.readString(source.resolve("original"))).isEqualTo("untouched");
    }
    @Test void journalPreservesOpaqueResponseBytesWithoutReserialization() throws Exception {
        var journal=new SimulationReplayJournal(temp.resolve("opaque"),DATASET);
        byte[] opaque=new byte[]{'{',' ',(byte)0xff,0,'}'};
        journal.raw("raw/response.bin","event-1","RESPONSE",opaque);
        var observation=new HashMap<String,Object>();observation.put("status","FAILED");
        journal.finish(schema(),observation,Map.of());
        assertThat(Files.readAllBytes(journal.root().resolve("raw/response.bin"))).isEqualTo(opaque);
        verifyIndex(journal.root());
        assertThatThrownBy(()->journal.raw("raw/again.bin","event-2","RESPONSE",opaque)).hasMessage("JOURNAL_FINISHED");
    }
    @Test void ndjsonFramingPreservesWhitespaceAndRejectsBlankRecords() throws Exception {
        byte[] first=join();byte[] ndjson=new byte[first.length+2];System.arraycopy(first,0,ndjson,0,first.length);ndjson[first.length]='\n';ndjson[first.length+1]='\n';
        var lines=SimulationReplayRun.lines(ndjson);assertThat(lines).hasSize(2);assertThat(lines.getFirst()).isEqualTo(first);
        Path output=temp.resolve("blank");
        assertThatThrownBy(()->SimulationReplayRun.replay(schema(),DATASET,DATASET,people(),lines,output)).isInstanceOf(SimulationBundleReader.Rejection.class);
        assertThat(output).doesNotExist();
    }
}
