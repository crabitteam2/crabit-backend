package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationCommandDispatcherIT {
    static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    static final String DATASET="sha256:"+"c".repeat(64);
    static final Instant START=SimulationCashOracle.START;
    static final String ACTOR="student-3-00", ACCOUNT="account-3-00";
    private long sequence;
    private JsonNode schema() throws Exception { return JSON.readTree(Files.readAllBytes(Path.of("api/demo-simulation-v1.schema.json"))); }
    private JsonNode people() throws Exception { return JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/bundle-contract-valid/students.json"))); }
    private SimulationCommandDispatcher dispatcher() throws Exception { return new SimulationCommandDispatcher(schema(),DATASET,DATASET,people()); }
    private ObjectNode event(String kind,String actor,Instant at,Map<String,?> cmd,String status) {
        var e=JSON.createObjectNode(); e.put("eventId","event-"+(++sequence)); e.put("sequence",sequence);
        e.put("occurredAt",at.toString());e.put("actorStudentId",actor);e.put("kind",kind);e.putArray("causes");
        e.set("command",JSON.valueToTree(cmd));e.putObject("outcome").put("status",status).put("resultRef","raw/result-"+sequence+".json");
        var refs=e.putArray("artifactRefs").add("raw/result-"+sequence+".json");
        cmd.forEach((key,value)->{if(key.endsWith("Ref"))refs.add(value.toString());}); return e;
    }
    private void join(SimulationCommandDispatcher d,JsonNode person) {
        d.execute(event("JOIN",person.get("logicalStudentId").asString(),Instant.parse(person.get("joinedAt").asString()),
            Map.of("studentId",person.get("logicalStudentId").asString(),"accountId",person.get("logicalAccountId").asString(),"academyId","academy-1","grade",person.get("grade").intValue()),"APPLIED"));
    }
    private JsonNode run(SimulationCommandDispatcher d,String kind,int seconds,Map<String,?> command,String status) {
        return JSON.readTree(d.execute(event(kind,ACTOR,START.plusSeconds(seconds),command,status)).rawResult());
    }
    private Map<String,Object> create(String wish,String key,long target) {
        var c=new HashMap<String,Object>();c.put("accountId",ACCOUNT);c.put("wishId",wish);c.put("idempotencyKey",key);c.put("purpose","synthetic wish "+wish);c.put("targetAmount",target);
        c.put("startDate",null);c.put("targetDate",null);c.put("photoId",null);return c;
    }
    private Map<String,Object> move(String wish,String key,long amount,long version) { return Map.of("accountId",ACCOUNT,"wishId",wish,"idempotencyKey",key,"amount",amount,"expectedVersion",version); }

    @Test void dormancyReturnPreservesEveryDomainTableAndCanResumeActualWishActivity() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0));
            run(d,"GRANT",1,Map.of("accountId",ACCOUNT,"amountKrw",10000,"cashEntryId","cash-1","budgetMonth","2026-06","scheduledAt",START.toString()),"APPLIED");
            var before=d.relationalState().state();
            var e=event("RETURN_FROM_DORMANCY",ACTOR,START.plus(Duration.ofDays(7)),Map.of("priorDormancyEventId","event-1"),"APPLIED");
            e.putArray("causes").add("event-1");
            var result=d.execute(e);
            assertThat(JSON.readTree(result.rawResult()).get("inactiveDuration").asString()).isEqualTo("PT168H");
            assertThat(d.relationalState().state()).isEqualTo(before);
            assertThat(d.execute(e).rawResult()).isEqualTo(result.rawResult());
            assertThat(d.relationalState().state()).isEqualTo(before);
            assertThat(run(d,"CREATE",7*86400+1,create("resumed","resumed",5000),"APPLIED").get("wish").get("amount").longValue()).isZero();
            assertThat(run(d,"DEPOSIT",7*86400+2,move("resumed","resume-deposit",3000,0),"APPLIED").get("wish").get("amount").longValue()).isEqualTo(3000);
        }
    }

    @Test void executesRealMoneyTransferCompletionAndRejectedAttemptsWithOriginalIdempotency() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0));
            assertThat(d.id("ACCOUNT",ACCOUNT)).isEqualTo(com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID);
            assertThat(run(d,"GRANT",1,Map.of("accountId",ACCOUNT,"amountKrw",10000,"cashEntryId","cash-1","budgetMonth","2026-06","scheduledAt",START.toString()),"APPLIED").get("cardFunds").longValue()).isEqualTo(10000);
            JsonNode first=run(d,"CREATE",2,create("wish-a","create-a",5000),"APPLIED");
            assertThat(first.get("wish").get("createdAt").asString()).isEqualTo(START.plusSeconds(2).toString());
            JsonNode replay=run(d,"CREATE",3,create("wish-a","create-a",5000),"APPLIED");
            assertThat(replay.get("replayed").booleanValue()).isTrue();
            assertThat(replay.get("wish").get("id")).isEqualTo(first.get("wish").get("id"));
            run(d,"CREATE",4,create("wish-b","create-b",5000),"APPLIED");
            var depositEvent=event("DEPOSIT",ACTOR,START.plusSeconds(5),move("wish-a","dep-a",5000,0),"APPLIED");
            var receipt=d.execute(depositEvent);
            assertThat(JSON.readTree(receipt.rawResult()).get("wish").get("amount").longValue()).isEqualTo(5000);
            assertThat(d.execute(depositEvent).rawResult()).isEqualTo(receipt.rawResult());
            byte[] changed=receipt.rawResult();changed[0]=0;assertThat(d.execute(depositEvent).rawResult()[0]).isNotZero();
            var conflicting=depositEvent.deepCopy();((ObjectNode)conflicting.get("command")).put("amount",4999);
            assertThatThrownBy(()->d.execute(conflicting)).hasMessage("EVENT_ID_CONFLICT");
            assertThat(run(d,"DEPOSIT",6,move("wish-b","too-much",5001,0),"REJECTED").get("code").asString()).isEqualTo("INSUFFICIENT_AVAILABLE_BALANCE");
            var transferred=run(d,"TRANSFER",7,Map.of("accountId",ACCOUNT,"sourceWishId","wish-a","destinationWishId","wish-b","amount",2000,
                "sourceExpectedVersion",1,"destinationExpectedVersion",0,"idempotencyKey","transfer-a-b","rootEventId","transfer-root","sourceEffectId","transfer-from","destinationEffectId","transfer-to"),"APPLIED");
            assertThat(transferred.get("sourceWish").get("amount").longValue()).isEqualTo(3000);
            assertThat(transferred.get("destinationWish").get("amount").longValue()).isEqualTo(2000);
            assertThat(d.id("LEDGER_ROOT","transfer-root").toString()).isEqualTo(transferred.get("eventId").asString());
            assertThat(d.id("LEDGER_EFFECT","transfer-from")).isNotEqualTo(d.id("LEDGER_EFFECT","transfer-to"));
            assertThat(run(d,"COMPLETE",8,Map.of("accountId",ACCOUNT,"wishId","wish-a","expectedVersion",2,"idempotencyKey","early","confirmed",true),"REJECTED").get("code").asString()).isEqualTo("INVALID_STATE_TRANSITION");
            run(d,"DEPOSIT",9,move("wish-a","refill",2000,2),"APPLIED");
            var complete=run(d,"COMPLETE",10,Map.of("accountId",ACCOUNT,"wishId","wish-a","expectedVersion",3,"idempotencyKey","finish","confirmed",true),"APPLIED");
            assertThat(complete.get("wish").get("state").asString()).isEqualTo("COMPLETED");
            assertThat(complete.get("wish").get("amount").longValue()).isZero();
            var lookup=run(d,"BALANCE_LOOKUP",11,Map.of("accountId",ACCOUNT,"observationRef","raw/observation.json"),"APPLIED");
            assertThat(lookup.get("balance").longValue()).isEqualTo(10000);
            assertThat(lookup.get("sourceKind").asString()).isEqualTo("SIMULATION");
            assertThat(run(d,"PURCHASE",12,Map.of("accountId",ACCOUNT,"cashEntryId","cash-2","amountKrw",10001),"REJECTED").get("code").asString()).isEqualTo("CASH_OUT_OF_RANGE");
            assertThat(run(d,"PURCHASE",13,Map.of("accountId",ACCOUNT,"cashEntryId","cash-3","amountKrw",9000),"APPLIED").get("cardFunds").longValue()).isEqualTo(1000);
            run(d,"BALANCE_LOOKUP",14,Map.of("accountId",ACCOUNT,"observationRef","raw/observation-2.json"),"APPLIED");
            var adjusted=run(d,"WITHDRAW",15,move("wish-b","adjust",1500,1),"APPLIED");
            assertThat(adjusted.get("wish").get("amount").longValue()).isEqualTo(500);
            assertThat(adjusted.get("wish").get("balanceAdjustmentInProgress").booleanValue()).isFalse();
            var abandon=run(d,"ABANDON",16,Map.of("accountId",ACCOUNT,"wishId","wish-b","expectedVersion",2,"idempotencyKey","abandon"),"APPLIED");
            assertThat(abandon.get("wish").get("abandonmentAmount").longValue()).isEqualTo(500);
            run(d,"DELETE",17,Map.of("accountId",ACCOUNT,"wishId","wish-b","expectedVersion",3,"idempotencyKey","delete"),"APPLIED");
            var financialExport=d.relationalState();
            assertThat(d.verifyIdempotencyState(financialExport).records()).isEqualTo(9);
            verifyIdempotencyTampering(d,financialExport);
            assertThat(SimulationCheckpointVerifier.verify(financialExport).activeWishFacts()).isPositive();
            assertThat(SimulationAdjustmentVerifier.verify(financialExport).resolvedCases()).isEqualTo(1);
            var allocation=d.allocationState();
            var observed=d.observationState();
            var observationCheck=d.verifyObservationState(observed,allocation);
            assertThat(observationCheck.succeeded()).isGreaterThanOrEqualTo(5);
            assertThat(observationCheck.depositLinks()).isEqualTo(2);
            assertThat(observed.observations()).allSatisfy(o->assertThat(o.sourceKind()).isEqualTo("SIMULATION"));
            var missingObservation=new SimulationObservationState.State(1,observed.schemaKind(),DATASET,observed.accounts(),
                observed.observations().subList(1,observed.observations().size()),observed.cash());
            assertThatThrownBy(()->d.verifyObservationState(missingObservation,allocation)).hasMessageStartingWith("OBSERVATION_");
            var checked=d.verifyAllocationState(allocation);
            assertThat(checked.wishes()).isEqualTo(2);
            assertThat(checked.allocatedAmount()).isZero();
            assertThat(allocation.effects()).hasSize(7);
            assertThat(allocation.roots().stream().filter(r->r.kind().equals("WISH_TRANSFER"))).hasSize(1);
            var missing=new SimulationAllocationState.State(1,allocation.schemaKind(),DATASET,allocation.wishes(),allocation.roots(),allocation.effects().subList(1,allocation.effects().size()));
            assertThatThrownBy(()->d.verifyAllocationState(missing)).hasMessageStartingWith("ALLOCATION_");
            var w=allocation.wishes().get(0);
            var changedWishes=new ArrayList<>(allocation.wishes());
            changedWishes.set(0,new SimulationAllocationState.Wish(w.id(),w.accountId(),w.targetAmount(),1,w.state(),w.createdAt(),w.completedAt(),w.deletedAt()));
            assertThatThrownBy(()->d.verifyAllocationState(new SimulationAllocationState.State(1,allocation.schemaKind(),DATASET,changedWishes,allocation.roots(),allocation.effects())))
                .hasMessage("ALLOCATION_STORED_AMOUNT_MISMATCH");
            assertThat(d.allocationState()).isEqualTo(allocation);

        }
    }

    @Test void allHundredJoinAtTheirOwnTimeAndSocialCommandsUseActorDirection() throws Exception {
        try(var d=dispatcher()) {
            for(JsonNode person:people())if(person.get("joinedAt").asString().equals(START.toString()))join(d,person);
            run(d,"FOLLOW",1,Map.of("academyId","academy-1","viewerStudentId",ACTOR,"ownerStudentId","student-3-01"),"APPLIED");
            run(d,"UNFOLLOW",2,Map.of("academyId","academy-1","viewerStudentId",ACTOR,"ownerStudentId","student-3-01"),"APPLIED");
            run(d,"BLOCK",3,Map.of("academyId","academy-1","viewerStudentId",ACTOR,"ownerStudentId","student-3-01"),"APPLIED");
            run(d,"UNBLOCK",4,Map.of("academyId","academy-1","viewerStudentId",ACTOR,"ownerStudentId","student-3-01"),"APPLIED");
            run(d,"CREATE",5,create("wish-shared","create-shared",5000),"APPLIED");
            assertThat(run(d,"SHARE",6,Map.of("accountId",ACCOUNT,"wishId","wish-shared","expectedVersion",0,"visibility","FOLLOWERS"),"APPLIED").get("wish").get("visibility").asString()).isEqualTo("FOLLOWERS");
            assertThat(run(d,"VISIBILITY_CHANGE",7,Map.of("accountId",ACCOUNT,"wishId","wish-shared","expectedVersion",1,"visibility","PRIVATE"),"APPLIED").get("wish").get("visibility").asString()).isEqualTo("PRIVATE");
            for(JsonNode person:people())if(!person.get("joinedAt").asString().equals(START.toString()))join(d,person);
            assertThat(d.identities().keySet().stream().filter(k->k.startsWith("MEMBERSHIP:"))).hasSize(100);
            assertThat(d.results().stream().filter(r->r.status().equals("APPLIED"))).hasSize(107);
        }
    }

    @Test void profileVisitsUseActualAuthorizationTimeAndStableBehaviorIdentity() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0)); join(d,people().get(1));
            var command=new HashMap<String,Object>();
            command.put("academyId","academy-1"); command.put("targetStudentId","student-3-01");
            command.put("source","DIRECT"); command.put("sourceEventId",null);
            var visit=event("PROFILE_VISIT",ACTOR,START.plusSeconds(1),command,"APPLIED");
            var accepted=d.execute(visit); var body=JSON.readTree(accepted.rawResult()).get("body");
            assertThat(body.get("occurredAt").asString()).isEqualTo(START.plusSeconds(1).toString());
            assertThat(body.get("receivedAt")).isEqualTo(body.get("occurredAt"));
            assertThat(d.id("BEHAVIOR_EVENT",visit.get("eventId").asString()).toString()).isEqualTo(body.get("eventId").asString());
            assertThat(d.execute(visit).rawResult()).isEqualTo(accepted.rawResult());
            run(d,"BLOCK",2,Map.of("academyId","academy-1","viewerStudentId",ACTOR,"ownerStudentId","student-3-01"),"APPLIED");
            assertThat(run(d,"PROFILE_VISIT",3,command,"REJECTED").get("code").asString()).isEqualTo("PROFILE_NOT_FOUND");
            command.put("targetStudentId",ACTOR);
            assertThat(run(d,"PROFILE_VISIT",4,command,"REJECTED").get("code").asString()).isEqualTo("SELF_PROFILE_VISIT");
            command.put("targetStudentId","student-3-20");
            assertThat(run(d,"PROFILE_VISIT",5,command,"REJECTED").get("code").asString()).isEqualTo("PROFILE_NOT_FOUND");
            command.put("targetStudentId","student-3-01"); command.put("sourceEventId",visit.get("eventId").asString());
            var forged=event("PROFILE_VISIT",ACTOR,START.plusSeconds(6),command,"APPLIED");
            assertThatThrownBy(()->d.execute(forged)).hasMessage("VISIT_SOURCE_CAUSE");
            assertThat(d.identities().keySet().stream().filter(k->k.startsWith("BEHAVIOR_EVENT:"))).hasSize(1);
        }
    }

    private String shareAs(SimulationCommandDispatcher d,String actor,String account,String wish,int at) {
        var creation=create(wish,"create-"+wish,5000); creation.put("accountId",account);
        d.execute(event("CREATE",actor,START.plusSeconds(at),creation,"APPLIED"));
        var shared=event("SHARE",actor,START.plusSeconds(at+1),Map.of("accountId",account,"wishId",wish,
            "expectedVersion",0,"visibility","ACADEMY"),"APPLIED");
        d.execute(shared); return shared.get("eventId").asString();
    }
    private Map<String,Object> feed(String context,List<String> cards) {
        var c=new HashMap<String,Object>(); c.put("academyId","academy-1");c.put("limit",100);c.put("cursor",null);
        c.put("resultContextId",context);c.put("orderedCardIds",cards);c.put("requestRef","raw/"+context+"-request.json");
        c.put("responseRef","raw/"+context+"-response.json");return c;
    }
    private Map<String,Object> click(String context,String card,String impression) {
        return new HashMap<>(Map.of("academyId","academy-1","resultContextId",context,"cardId",card,"position",0,
            "impressionId",impression,"clickKind","AUTHOR_PROFILE"));
    }

    @Test void feedCommandsUseActualPagesAndPermitUnmatchedClicksWithDomainRejections() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0)); join(d,people().get(1));
            String card=shareAs(d,"student-3-01","account-3-01","peer-wish",1);
            var page=run(d,"FEED_QUERY",3,feed("page-1",List.of(card)),"APPLIED");
            assertThat(page.get("sortSource").asString()).isEqualTo("LATEST");
            assertThat(page.get("recommendationResultId").isNull()).isTrue();
            assertThat(page.get("items").get(0).get("sharedCardId").asString()).isEqualTo(d.id("SHARED_CARD",card).toString());
            assertThat(page.get("resultContextId").asString()).isEqualTo(d.id("FEED_CONTEXT","page-1").toString());
            var click=click("page-1",card,"unmatched");
            var first=event("CLICK",ACTOR,START.plusSeconds(4),click,"APPLIED");
            var accepted=d.execute(first); var body=JSON.readTree(accepted.rawResult()).get("body");
            assertThat(body.get("eventType").asString()).isEqualTo("FEED_CLICK");
            assertThat(body.get("occurredAt").asString()).isEqualTo(START.plusSeconds(4).toString());
            assertThat(body.get("receivedAt")).isEqualTo(body.get("occurredAt"));
            assertThat(d.execute(first).rawResult()).isEqualTo(accepted.rawResult());
            var exposure=new HashMap<>(click); exposure.remove("clickKind");
            assertThat(run(d,"IMPRESSION",5,exposure,"APPLIED").get("body").get("eventType").asString()).isEqualTo("FEED_EXPOSURE");
            assertThat(run(d,"IMPRESSION",6,exposure,"REJECTED").get("code").asString()).isEqualTo("IMPRESSION_ALREADY_EXPOSED");
            var wrongPosition=new HashMap<>(click);wrongPosition.put("position",1);
            assertThat(run(d,"CLICK",7,wrongPosition,"REJECTED").get("code").asString()).isEqualTo("FEED_CONTEXT_NOT_FOUND");
            assertThat(JSON.readTree(d.execute(event("CLICK","student-3-01",START.plusSeconds(8),click,"REJECTED")).rawResult())
                .get("code").asString()).isEqualTo("FEED_CONTEXT_NOT_FOUND");
            // Expiry is exact at the 24-hour boundary, even for an otherwise visible card.
            assertThat(run(d,"CLICK",86403,click,"REJECTED").get("code").asString()).isEqualTo("FEED_CONTEXT_EXPIRED");
            run(d,"FEED_QUERY",86404,feed("page-2",List.of(card)),"APPLIED");
            var conflict=click("page-2",card,"unmatched");
            assertThat(run(d,"CLICK",86405,conflict,"REJECTED").get("code").asString()).isEqualTo("IMPRESSION_CONFLICT");
            run(d,"BLOCK",86406,Map.of("academyId","academy-1","viewerStudentId",ACTOR,"ownerStudentId","student-3-01"),"APPLIED");
            assertThat(run(d,"CLICK",86407,click("page-2",card,"blocked"),"REJECTED").get("code").asString()).isEqualTo("SHARED_CARD_NOT_FOUND");
            assertThat(d.identities().keySet().stream().filter(k->k.startsWith("BEHAVIOR_EVENT:"))).hasSize(2);
        }
    }

    @Test void feedOrderMismatchRetainsActualResponseAndPoisonsDispatcher() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0));join(d,people().get(1));
            shareAs(d,"student-3-01","account-3-01","peer-wish",1);
            var forged=event("FEED_QUERY",ACTOR,START.plusSeconds(3),feed("false-empty",List.of()),"APPLIED");
            assertThatThrownBy(()->d.execute(forged)).hasMessageContaining("FEED_ORDER_MISMATCH");
            assertThat(JSON.readTree(d.mismatchedResult().orElseThrow().rawResult()).get("items")).hasSize(1);
            assertThat(d.identities().keySet()).doesNotContain("FEED_CONTEXT:false-empty");
            assertThatThrownBy(()->d.execute(forged)).hasMessage("DISPATCHER_POISONED");
        }
    }

    @Test void privateAndResharedCardsKeepDistinctHistoricalIdentities() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0));join(d,people().get(1));
            String old=shareAs(d,"student-3-01","account-3-01","peer-wish",1);
            run(d,"FEED_QUERY",3,feed("page-old",List.of(old)),"APPLIED");
            d.execute(event("VISIBILITY_CHANGE","student-3-01",START.plusSeconds(4),Map.of("accountId","account-3-01",
                "wishId","peer-wish","expectedVersion",1,"visibility","PRIVATE"),"APPLIED"));
            assertThat(run(d,"CLICK",5,click("page-old",old,"hidden"),"REJECTED").get("code").asString()).isEqualTo("SHARED_CARD_NOT_FOUND");
            var shared=event("SHARE","student-3-01",START.plusSeconds(6),Map.of("accountId","account-3-01",
                "wishId","peer-wish","expectedVersion",2,"visibility","ACADEMY"),"APPLIED");
            d.execute(shared);String fresh=shared.get("eventId").asString();
            assertThat(d.id("SHARED_CARD",old)).isNotEqualTo(d.id("SHARED_CARD",fresh));
            run(d,"FEED_QUERY",7,feed("page-new",List.of(fresh)),"APPLIED");
            assertThat(run(d,"CLICK",8,click("page-old",old,"stale"),"REJECTED").get("code").asString()).isEqualTo("SHARED_CARD_NOT_FOUND");
            run(d,"CLICK",9,click("page-new",fresh,"fresh"),"APPLIED");
        }
    }

    @Test void falseExpectedOutcomePoisonsRatherThanBeingRecordedAsSuccess() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0));
            var wrong=event("PURCHASE",ACTOR,START.plusSeconds(1),Map.of("accountId",ACCOUNT,"cashEntryId","bad","amountKrw",1),"APPLIED");
            assertThatThrownBy(()->d.execute(wrong)).hasMessageContaining("OUTCOME_MISMATCH").hasMessageContaining("REJECTED");
            assertThat(d.results()).hasSize(1);
            assertThat(d.mismatchedResult().orElseThrow().errorCode()).isEqualTo("CASH_OUT_OF_RANGE");
            assertThatThrownBy(()->d.execute(wrong)).hasMessage("DISPATCHER_POISONED");
        }
    }

    @Test void malformedUnsupportedFutureAndForgedCommandsFailBeforeExecution() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0));
            var future=event("CREATE","student-3-20",START.plusSeconds(1),create("future","future",5000),"APPLIED");
            assertThatThrownBy(()->d.execute(future)).hasMessage("ACTOR_ENROLLMENT");
            var malformed=event("CREATE",ACTOR,START.plusSeconds(2),create("x","x",5000),"APPLIED");
            malformed.put("extra",true);assertThatThrownBy(()->d.execute(malformed)).isInstanceOf(SimulationBundleReader.Rejection.class);
            var unsupported=event("CLOSE_WEEK",ACTOR,START.plusSeconds(3),Map.of("accountId",ACCOUNT,
                "startInclusive","2026-06-01","endExclusive","2026-06-08","generationId","week",
                "snapshotRef","raw/snapshot.json","requestRef","raw/request.json",
                "responseRef","raw/response.json","storedStateRef","raw/stored.json"),"APPLIED");
            assertThatThrownBy(()->d.execute(unsupported)).hasMessage("RECAP_CONFIGURATION_REQUIRED");
            var forged=event("GRANT",ACTOR,START.plusSeconds(4),Map.of("accountId","account-3-01","amountKrw",5000,"cashEntryId","forged","budgetMonth","2026-06","scheduledAt",START.toString()),"REJECTED");
            assertThat(d.execute(forged).errorCode()).isEqualTo("FORBIDDEN");
            assertThat(d.results()).hasSize(2);
            var duplicateCash=event("PURCHASE",ACTOR,START.plusSeconds(5),Map.of("accountId",ACCOUNT,"cashEntryId","forged","amountKrw",1),"REJECTED");
            assertThatThrownBy(()->d.execute(duplicateCash)).hasMessage("CASH_ENTRY_DUPLICATE");
            assertThat(d.identities().keySet()).doesNotContain("WISH:future","WISH:x");
        }
    }
    @Test void independentDatabasesNormalizeActualMoneySocialBehaviorAndSignedPaginationResponses() throws Exception {
        List<String> projections=new ArrayList<>(), rawPages=new ArrayList<>(), logicalMaps=new ArrayList<>(), relationalProjections=new ArrayList<>();
        for(int replay=0;replay<2;replay++) {
            sequence=0;
            try(var d=dispatcher()) {
                join(d,people().get(0));join(d,people().get(1));
                run(d,"GRANT",1,Map.of("accountId",ACCOUNT,"amountKrw",10000,"cashEntryId","cash-1","budgetMonth","2026-06","scheduledAt",START.toString()),"APPLIED");
                run(d,"CREATE",2,create("savings","create-savings",5000),"APPLIED");
                run(d,"CREATE",3,create("savings","create-savings",5000),"APPLIED");
                run(d,"DEPOSIT",4,move("savings","deposit",4000,0),"APPLIED");
                run(d,"DEPOSIT",5,move("savings","invalid",2000,1),"REJECTED");
                run(d,"BALANCE_LOOKUP",6,Map.of("accountId",ACCOUNT,"observationRef","raw/lookup.json"),"APPLIED");
                String older=shareAs(d,"student-3-01","account-3-01","older",7);
                String newer=shareAs(d,"student-3-01","account-3-01","newer",9);
                var firstCommand=feed("page-one",List.of(newer));firstCommand.put("limit",1);
                var page=run(d,"FEED_QUERY",11,firstCommand,"APPLIED");
                assertThat(page.get("nextCursor").isNull()).isFalse();rawPages.add(JSON.writeValueAsString(page));
                run(d,"CLICK",12,click("page-one",newer,"unmatched"),"APPLIED");
                run(d,"FOLLOW",13,Map.of("academyId","academy-1","viewerStudentId",ACTOR,"ownerStudentId","student-3-01"),"APPLIED");
                var nextCommand=feed("page-two",List.of(older));nextCommand.put("limit",1);nextCommand.put("cursor",page.get("nextCursor").asString());
                run(d,"FEED_QUERY",14,nextCommand,"APPLIED");
                var forgedCommand=feed("forged-page",List.of());forgedCommand.put("cursor",page.get("nextCursor").asString()+"x");
                assertThat(run(d,"FEED_QUERY",15,forgedCommand,"REJECTED").get("code").asString()).isEqualTo("MALFORMED_REQUEST");
                assertThat(d.verifyAllocationState(d.allocationState()).allocatedAmount()).isEqualTo(4000);
                assertThat(d.verifyRelationalDomain(d.relationalState()).sourceVersions()).isGreaterThan(10);
                assertThat(d.verifyBehaviorState(d.relationalState()).clicksWithoutPriorExposure()).isEqualTo(1);
                var identityMap=SimulationReplayIdentityMap.capture(d.relationalState(),d.identities(),schema());
                logicalMaps.add(SimulationBundleReader.canonical(SimulationReplayIdentityMap.logicalProjection(identityMap)));
                var normalizedState=SimulationRelationalNormalizer.normalize(d.relationalState(),identityMap,d.verifyIdempotencyState(d.relationalState()));
                assertThat(normalizedState.get("allRuntimeValuesNormalized").booleanValue()).isTrue();
                assertThat(normalizedState.get("opaqueRuntimeFields")).isEmpty();
                var comparedTables=normalizedState.get("tables").deepCopy();
                // Every table participates after verifying original fingerprints against actual requests/results.
                relationalProjections.add(SimulationBundleReader.canonical(comparedTables));

                JsonNode normalized=d.normalizedResponses();
                projections.add(SimulationBundleReader.canonical(normalized));
                var rows=normalized.get("events");
                var firstPage=rows.get(12).get("response");
                assertThat(firstPage.get("nextCursor").get("version").intValue()).isEqualTo(2);
                assertThat(firstPage.get("nextCursor").get("sessionId").asString()).startsWith("FEED_SESSION:");
                assertThat(firstPage.get("items").get(0).get("sharedCardId").asString()).isEqualTo("SHARED_CARD:"+newer);
                assertThat(normalized.get("fullDatasetNormalizationPerformed").booleanValue()).isFalse();
            }
        }
        assertThat(rawPages.get(0)).isNotEqualTo(rawPages.get(1));
        assertThat(projections.get(0)).isEqualTo(projections.get(1));
        assertThat(logicalMaps.get(0)).isEqualTo(logicalMaps.get(1));
        assertThat(relationalProjections.get(0)).isEqualTo(relationalProjections.get(1));
        Path report=Path.of("build/simulation-verification/response-normalization.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report,JSON.writeValueAsString(Map.of("schemaVersion",1,"status","PASS",
            "independentDisposableDatabases",2,"fullDatasetValidationPerformed",false,"eventsPerReplay",17,
            "normalizedRelationalComparedTableDigests",relationalProjections.stream().map(value->SimulationBundleReader.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList(),
            "excludedRelationalTables",List.of(),
            "rawPageDigests",rawPages.stream().map(value->SimulationBundleReader.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList(),
            "normalizedResponseDigests",projections.stream().map(value->SimulationBundleReader.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList(),
            "logicalIdentityDigests",logicalMaps.stream().map(value->SimulationBundleReader.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList())));
    }

    @Test void verifiesUnicodePurposeDatesStudentKeyNamespacesAndExactRecordBinding() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0));join(d,people().get(1));
            Map<String,Object> peer=create("unicode-peer","shared-key",5000);
            peer.put("accountId","account-3-01");peer.put("purpose","\u2002Cafe\u0301\u00a0");
            peer.put("startDate","2026-06-01");peer.put("targetDate","2026-07-01");
            d.execute(event("CREATE","student-3-01",START.plusSeconds(1),peer,"APPLIED"));
            run(d,"CREATE",1,create("unicode-owner","shared-key",7000),"APPLIED");
            d.execute(event("CREATE","student-3-01",START.plusSeconds(2),peer,"APPLIED"));
            var export=d.relationalState();var verified=d.verifyIdempotencyState(export);
            assertThat(verified.records()).isEqualTo(2);
            var identityMap=SimulationReplayIdentityMap.capture(export,d.identities(),schema());
            var normalized=SimulationRelationalNormalizer.normalize(export,identityMap,verified);
            assertThat(normalized.get("allRuntimeValuesNormalized").booleanValue()).isTrue();
            for(JsonNode student:normalized.get("tables").get("student")) {
                var record=student.get("wish_idempotency_records").get("shared-key");
                assertThat(record.get("requestFingerprint").asString()).startsWith("sha256:");
                if(student.get("id").asString().equals("STUDENT:student-3-01"))
                    assertThat(record.get("snapshot").get("purpose").asString()).isEqualTo("Café");
            }
            var forged=altered(export,rows->{
                var record=(ObjectNode)rows.get("student").getFirst().get("wish_idempotency_records").get("shared-key");
                record.put("requestFingerprint","sha256:"+"0".repeat(64));
            });
            assertThatThrownBy(()->SimulationRelationalNormalizer.normalize(forged,identityMap,verified))
                .hasMessage("IDEMPOTENCY_RECONCILIATION_UNVERIFIED_RECORD");
            assertThat(d.verifyIdempotencyState(export).records()).isEqualTo(2);
        }
    }

    private void verifyIdempotencyTampering(SimulationCommandDispatcher d,SimulationRelationalState.Export original) {
        String before=SimulationBundleReader.canonical(JSON.valueToTree(original));
        for(String field:List.of("requestFingerprint","operation","targetId","httpStatus","snapshot","destinationSnapshot","eventId","recordedAt","occurredAt","photoReplayState")) {
            var forged=altered(original,rows->{
                var student=rows.get("student").getFirst();
                var records=student.get("wish_idempotency_records");
                var key=records.propertyNames().iterator().next();
                var record=(ObjectNode)records.get(key);
                switch(field) {
                    case "httpStatus" -> record.put(field,999);
                    case "snapshot" -> ((ObjectNode)record.get(field)).put("amount",123456);
                    case "destinationSnapshot" -> record.set(field,record.get("snapshot").deepCopy());
                    case "photoReplayState" -> ((ObjectNode)record.get(field)).put("kind","PHOTO_REVOKED");
                    case "targetId","eventId" -> record.put(field,UUID.randomUUID().toString());
                    case "recordedAt","occurredAt" -> record.put(field,"2026-06-02T00:00:00Z");
                    default -> record.put(field,"corrupt");
                }
            });
            assertThatThrownBy(()->d.verifyIdempotencyState(forged)).as(field).hasMessageStartingWith("IDEMPOTENCY_RECONCILIATION_");
        }
        var missing=altered(original,rows->{var records=(ObjectNode)rows.get("student").getFirst().get("wish_idempotency_records");records.remove(records.propertyNames().iterator().next());});
        assertThatThrownBy(()->d.verifyIdempotencyState(missing)).hasMessage("IDEMPOTENCY_RECONCILIATION_MISSING_RECORD");
        var extra=altered(original,rows->{var records=(ObjectNode)rows.get("student").getFirst().get("wish_idempotency_records");records.set("not-executed",records.get(records.propertyNames().iterator().next()).deepCopy());});
        assertThatThrownBy(()->d.verifyIdempotencyState(extra)).hasMessage("IDEMPOTENCY_RECONCILIATION_UNEXPECTED_RECORD");
        assertThat(SimulationBundleReader.canonical(JSON.valueToTree(original))).isEqualTo(before);
        assertThat(SimulationBundleReader.canonical(JSON.valueToTree(d.relationalState()))).isEqualTo(before);
    }

    private SimulationRelationalState.Export altered(SimulationRelationalState.Export original, java.util.function.Consumer<Map<String,List<JsonNode>>> mutation) {
        var rows=new TreeMap<String,List<JsonNode>>();
        original.state().tables().forEach((k,v)->rows.put(k,new ArrayList<>(v.stream().map(JsonNode::deepCopy).toList())));
        mutation.accept(rows);var state=original.state();
        return new SimulationRelationalState.Export(original.catalog(),new SimulationRelationalState.State(1,state.schemaKind(),state.datasetId(),state.catalogDigest(),rows));
    }
    @Test void exportedAdjustmentEpisodeProvesOpeningShortageIntermediateOrderResolutionAndOutbox() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0));
            run(d,"GRANT",1,Map.of("accountId",ACCOUNT,"amountKrw",10000,"cashEntryId","grant","budgetMonth","2026-06","scheduledAt",START.toString()),"APPLIED");
            run(d,"CREATE",2,create("wish","create",10000),"APPLIED");
            run(d,"DEPOSIT",3,move("wish","deposit",9000,0),"APPLIED");
            run(d,"PURCHASE",4,Map.of("accountId",ACCOUNT,"cashEntryId","purchase","amountKrw",5000),"APPLIED");
            run(d,"BALANCE_LOOKUP",5,Map.of("accountId",ACCOUNT,"observationRef","raw/opening.json"),"APPLIED");
            var opened=d.relationalState();
            assertThat(SimulationCheckpointVerifier.verify(opened).checkpoints()).isPositive();
            assertThat(SimulationAdjustmentVerifier.verify(opened).openCases()).isEqualTo(1);
            run(d,"WITHDRAW",6,move("wish","partial",1000,1),"APPLIED");
            run(d,"GRANT",7,Map.of("accountId",ACCOUNT,"amountKrw",2000,"cashEntryId","grant2","budgetMonth","2026-06","scheduledAt",START.plusSeconds(7).toString()),"APPLIED");
            run(d,"BALANCE_LOOKUP",8,Map.of("accountId",ACCOUNT,"observationRef","raw/intermediate.json"),"APPLIED");
            run(d,"WITHDRAW",9,move("wish","resolution",1000,2),"APPLIED");
            var exported=d.relationalState();d.verifyRelationalState(exported);
            assertThat(SimulationCheckpointVerifier.verify(exported).activeWishFacts()).isPositive();
            var checked=SimulationAdjustmentVerifier.verify(exported);
            assertThat(checked.cases()).isEqualTo(1);assertThat(checked.links()).isEqualTo(4);
            assertThat(checked.resolvedCases()).isEqualTo(1);assertThat(checked.openCases()).isZero();
            var identityMap=SimulationReplayIdentityMap.capture(exported,d.identities(),schema());
            var normalizedState=SimulationRelationalNormalizer.normalize(exported,identityMap);
            assertThat(normalizedState.get("tables").get("balance_adjustment_case_event")).isNotEmpty();
            assertThat(normalizedState.get("tables").get("mismatch_notification_outbox")).isNotEmpty();
            var mapped=identityMap.get("entries");
            for(String kind:List.of("BALANCE_OBSERVATION","LEDGER_ROOT","LEDGER_EFFECT","ADJUSTMENT_CASE")) {
                long expected=exported.state().tables().get(Map.of("BALANCE_OBSERVATION","balance_observation",
                    "LEDGER_ROOT","ledger_event","LEDGER_EFFECT","ledger_wish_effect","ADJUSTMENT_CASE","balance_adjustment_case").get(kind)).size();
                assertThat(java.util.stream.StreamSupport.stream(mapped.spliterator(),false)
                    .filter(row->row.get("entityKind").asString().equals(kind)).count()).isEqualTo(expected);
            }
            var extraIdentity=new HashMap<>(d.identities());extraIdentity.put("WISH:phantom",UUID.randomUUID());
            assertThatThrownBy(()->SimulationReplayIdentityMap.capture(exported,extraIdentity,schema())).hasMessage("IDENTITY_EXPORT_UNPERSISTED_IDENTITY:WISH");
            var missingIdentity=new HashMap<>(d.identities());missingIdentity.remove("WISH:wish");
            assertThatThrownBy(()->SimulationReplayIdentityMap.capture(exported,missingIdentity,schema())).hasMessage("IDENTITY_EXPORT_UNKNOWN_IDENTITY:WISH");
            var duplicateIdentity=new HashMap<>(d.identities());duplicateIdentity.put("WISH:alias",d.id("WISH","wish"));
            assertThatThrownBy(()->SimulationReplayIdentityMap.capture(exported,duplicateIdentity,schema())).hasMessage("IDENTITY_EXPORT_UUID_BIJECTION");
            var collidingIdentity=new HashMap<>(d.identities());
            String derived=java.util.stream.StreamSupport.stream(mapped.spliterator(),false)
                .filter(row->row.get("entityKind").asString().equals("BALANCE_OBSERVATION") && row.get("logicalId").asString().startsWith("auto:"))
                .findFirst().orElseThrow().get("logicalId").asString();
            // An attacker-supplied logical ID cannot alias a different automatic observation.
            String explicitObservation=collidingIdentity.keySet().stream().filter(key->key.startsWith("BALANCE_OBSERVATION:")).sorted().findFirst().orElseThrow();
            UUID explicitUuid=collidingIdentity.remove(explicitObservation);
            collidingIdentity.put("BALANCE_OBSERVATION:"+derived,explicitUuid);
            assertThatThrownBy(()->SimulationReplayIdentityMap.capture(exported,collidingIdentity,schema())).hasMessage("IDENTITY_EXPORT_LOGICAL_BIJECTION:BALANCE_OBSERVATION");
            // Capture is independent of physical row order and does not mutate the original state.
            var reversed=altered(exported,rows->rows.values().forEach(Collections::reverse));
            assertThat(SimulationReplayIdentityMap.capture(reversed,d.identities(),schema())).isEqualTo(identityMap);
            var wrongShortage=altered(exported,rows->((ObjectNode)rows.get("balance_adjustment_case").getFirst()).put("opened_shortage",3999));
            assertThatThrownBy(()->SimulationAdjustmentVerifier.verify(altered(exported,rows->((ObjectNode)rows.get("balance_adjustment_case").getFirst()).put("opened_shortage",4000.5))))
                .hasMessage("ADJUSTMENT_INTEGER_REQUIRED");
            assertThat(d.verifyRelationalState(wrongShortage).tables()).isEqualTo(38);
            assertThatThrownBy(()->SimulationAdjustmentVerifier.verify(wrongShortage)).hasMessage("ADJUSTMENT_OPENING_SHORTAGE");
            assertThatThrownBy(()->SimulationAdjustmentVerifier.verify(altered(exported,rows->{
                var link=rows.get("balance_adjustment_case_event").stream().filter(r->r.get("sequence_number").intValue()==1).findFirst().orElseThrow();
                ((ObjectNode)link).put("sequence_number",99);
            }))).hasMessageStartingWith("ADJUSTMENT_LINK_");
            assertThatThrownBy(()->SimulationAdjustmentVerifier.verify(altered(exported,rows->{
                var link=rows.get("balance_adjustment_case_event").stream().filter(r->r.get("sequence_number").intValue()==1).findFirst().orElseThrow();
                ((ObjectNode)link).put("event_role","RESOLUTION");
            }))).hasMessage("ADJUSTMENT_LINK_ROLE");
            assertThatThrownBy(()->SimulationAdjustmentVerifier.verify(altered(exported,rows->rows.get("mismatch_notification_outbox").clear())))
                .hasMessage("ADJUSTMENT_REFERENCE");
            assertThatThrownBy(()->SimulationAdjustmentVerifier.verify(altered(exported,rows->((ObjectNode)rows.get("balance_adjustment_case").getFirst()).put("status","OPEN"))))
                .hasMessage("ADJUSTMENT_LINK_ROLE");
            assertThat(SimulationAdjustmentVerifier.verify(exported)).isEqualTo(checked);
            assertThat(d.relationalState().state()).isEqualTo(exported.state());
        }
    }

    @Test void behaviorExportReconcilesCommandsAndPreservesUnmatchedClicksAfterLaterExposureAndCardRemoval() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0));join(d,people().get(1));join(d,people().get(2));
            String card=shareAs(d,"student-3-01","account-3-01","peer",1);
            run(d,"FEED_QUERY",3,feed("page",List.of(card)),"APPLIED");
            // Same-time click then exposure must still count the click as unmatched at acceptance.
            run(d,"CLICK",4,click("page",card,"shared-impression"),"APPLIED");
            var exposure=click("page",card,"shared-impression");exposure.remove("clickKind");
            run(d,"IMPRESSION",4,exposure,"APPLIED");
            run(d,"CLICK",4,click("page",card,"shared-impression"),"APPLIED");
            run(d,"CLICK",5,click("page",card,"never-exposed"),"APPLIED");
            run(d,"IMPRESSION",6,exposure,"REJECTED");
            d.execute(event("VISIBILITY_CHANGE","student-3-01",START.plusSeconds(7),Map.of("accountId","account-3-01",
                "wishId","peer","expectedVersion",1,"visibility","PRIVATE"),"APPLIED"));
            var exported=d.relationalState();assertThat(exported.state().tables().get("shared_card")).isEmpty();
            var checked=d.verifyBehaviorState(exported);
            assertThat(checked.events()).isEqualTo(4);assertThat(checked.impressions()).isEqualTo(2);
            assertThat(checked.clicksWithoutPriorExposure()).isEqualTo(2);
            var wrongLink=altered(exported,rows->{
                var row=rows.get("behavior_impression").stream().filter(r->!r.get("exposed_event_id").isNull()).findFirst().orElseThrow();
                ((ObjectNode)row).put("exposed_event_id",UUID.randomUUID().toString());
            });
            assertThat(d.verifyRelationalState(wrongLink).tables()).isEqualTo(38);
            assertThatThrownBy(()->d.verifyBehaviorState(wrongLink)).hasMessage("BEHAVIOR_EXPOSED_EVENT_LINK");
            var wrongTarget=altered(exported,rows->((ObjectNode)rows.get("behavior_event").getFirst()).put("target_id",d.id("STUDENT","student-3-02").toString()));
            assertThat(d.verifyRelationalState(wrongTarget).tables()).isEqualTo(38);
            assertThatThrownBy(()->d.verifyBehaviorState(wrongTarget)).hasMessage("BEHAVIOR_CARD_AUTHOR");
            var missing=altered(exported,rows->rows.get("behavior_event").removeFirst());
            assertThat(d.verifyRelationalState(missing).tables()).isEqualTo(38);
            assertThatThrownBy(()->d.verifyBehaviorState(missing)).hasMessage("BEHAVIOR_MISSING_EVENT");
            assertThatThrownBy(()->d.verifyBehaviorState(altered(exported,rows->((ObjectNode)rows.get("behavior_result_context").getFirst())
                .put("created_at",START.plusSeconds(4).toString())))).hasMessage("BEHAVIOR_CONTEXT_COMMAND");
            var shifted=altered(exported,rows->{
                for(String table:List.of("behavior_result_item","behavior_impression","behavior_event"))
                    rows.get(table).forEach(row->((ObjectNode)row).put("position",1));
            });
            assertThat(d.verifyRelationalState(shifted).tables()).isEqualTo(38);
            assertThatThrownBy(()->d.verifyBehaviorState(shifted)).hasMessage("BEHAVIOR_RESULT_ITEMS");
            var extra=altered(exported,rows->{
                var clickRow=rows.get("behavior_event").stream().filter(row->row.get("event_type").asString().equals("FEED_CLICK")).findFirst().orElseThrow().deepCopy();
                ((ObjectNode)clickRow).put("event_id",UUID.randomUUID().toString());rows.get("behavior_event").add(clickRow);
            });
            assertThat(d.verifyRelationalState(extra).tables()).isEqualTo(38);
            assertThatThrownBy(()->d.verifyBehaviorState(extra)).hasMessage("BEHAVIOR_EVENT_SET");
            var identities=SimulationReplayIdentityMap.capture(exported,d.identities(),schema());
            var normalizedState=SimulationRelationalNormalizer.normalize(exported,identities);
            assertThat(normalizedState.get("tables").get("behavior_impression")).hasSize(2);
            assertThat(normalizedState.get("tables").get("shared_card")).isEmpty();

            assertThat(java.util.stream.StreamSupport.stream(identities.get("entries").spliterator(),false)
                .filter(row->row.get("entityKind").asString().equals("SHARED_CARD")).count()).isEqualTo(1);
            assertThat(d.verifyBehaviorState(exported)).isEqualTo(checked);
            assertThat(d.relationalState().state()).isEqualTo(exported.state());
        }
    }

    @Test void accessOracleRecomputesDirectedFollowBlockReleaseAndFreshShareAgainstRealRows() throws Exception {
        try(var d=dispatcher()) {
            List<JsonNode> commands=new ArrayList<>();
            java.util.function.Function<ObjectNode,JsonNode> execute=e->{
                var result=d.execute(e);commands.add(e);return JSON.readTree(result.rawResult());
            };
            for(int i=0;i<2;i++) {
                JsonNode p=people().get(i);
                execute.apply(event("JOIN",p.get("logicalStudentId").asString(),START,Map.of(
                    "studentId",p.get("logicalStudentId").asString(),"accountId",p.get("logicalAccountId").asString(),
                    "academyId","academy-1","grade",3),"APPLIED"));
            }
            String owner="student-3-01",account="account-3-01";
            var create=create("peer","peer-create",10000);create.put("accountId",account);
            execute.apply(event("CREATE",owner,START.plusSeconds(1),create,"APPLIED"));
            var share=event("SHARE",owner,START.plusSeconds(2),Map.of("accountId",account,"wishId","peer","expectedVersion",0,"visibility","FOLLOWERS"),"APPLIED");
            execute.apply(share);String card=share.get("eventId").asString();
            var reverse=Map.of("academyId","academy-1","viewerStudentId",owner,"ownerStudentId",ACTOR);
            var forward=Map.of("academyId","academy-1","viewerStudentId",ACTOR,"ownerStudentId",owner);
            execute.apply(event("FOLLOW",owner,START.plusSeconds(3),reverse,"APPLIED"));
            execute.apply(event("FEED_QUERY",ACTOR,START.plusSeconds(4),feed("empty-reverse",List.of()),"APPLIED"));
            var follow=event("FOLLOW",ACTOR,START.plusSeconds(5),forward,"APPLIED");execute.apply(follow);
            execute.apply(event("FEED_QUERY",ACTOR,START.plusSeconds(6),feed("visible",List.of(card)),"APPLIED"));
            execute.apply(event("CLICK",ACTOR,START.plusSeconds(7),click("visible",card,"first"),"APPLIED"));
            execute.apply(event("BLOCK",owner,START.plusSeconds(8),reverse,"APPLIED"));
            execute.apply(event("CLICK",ACTOR,START.plusSeconds(9),click("visible",card,"blocked"),"REJECTED"));
            execute.apply(event("UNBLOCK",owner,START.plusSeconds(10),reverse,"APPLIED"));
            execute.apply(event("FEED_QUERY",ACTOR,START.plusSeconds(11),feed("empty-released",List.of()),"APPLIED"));
            execute.apply(event("FOLLOW",ACTOR,START.plusSeconds(12),forward,"APPLIED"));
            execute.apply(event("FEED_QUERY",ACTOR,START.plusSeconds(13),feed("visible-again",List.of(card)),"APPLIED"));
            execute.apply(event("VISIBILITY_CHANGE",owner,START.plusSeconds(14),Map.of("accountId",account,"wishId","peer","expectedVersion",1,"visibility","PRIVATE"),"APPLIED"));
            var reshare=event("SHARE",owner,START.plusSeconds(15),Map.of("accountId",account,"wishId","peer","expectedVersion",2,"visibility","ACADEMY"),"APPLIED");
            execute.apply(reshare);String fresh=reshare.get("eventId").asString();
            execute.apply(event("FEED_QUERY",ACTOR,START.plusSeconds(16),feed("fresh-card",List.of(fresh)),"APPLIED"));
            execute.apply(event("CLICK",ACTOR,START.plusSeconds(17),click("visible",card,"stale-card"),"REJECTED"));
            var exported=d.relationalState();
            var checked=d.verifyBehaviorState(exported);
            assertThat(checked.access()).isEqualTo(new SimulationBehaviorAccessVerifier.Verification(5,3,1,0));
            assertThat(d.id("SHARED_CARD",card)).isNotEqualTo(d.id("SHARED_CARD",fresh));
            // All referenced actual rows still exist, but changing only the causal follow direction must fail.
            List<JsonNode> forged=commands.stream().map(JsonNode::deepCopy).toList();
            var fakeFollow=(ObjectNode)forged.stream().filter(e->e.get("eventId").equals(follow.get("eventId"))).findFirst().orElseThrow();
            fakeFollow.put("actorStudentId",owner);fakeFollow.set("command",JSON.valueToTree(reverse));
            assertThatThrownBy(()->SimulationBehaviorAccessVerifier.verify(exported,forged,d.identities()))
                .hasMessageStartingWith("BEHAVIOR_ACCESS_CARD_DENIED");
            assertThat(d.verifyBehaviorState(exported)).isEqualTo(checked);
            assertThat(d.relationalState().state()).isEqualTo(exported.state());
        }
    }

    @Test void profileVisitEvidenceMustMatchAcceptedEventAndRetainedHistoricalSources() throws Exception {
        try(var d=dispatcher()) {
            join(d,people().get(0));join(d,people().get(1));
            shareAs(d,"student-3-01","account-3-01","peer",1);
            var visit=new HashMap<String,Object>();visit.put("academyId","academy-1");visit.put("targetStudentId","student-3-01");
            visit.put("source","DIRECT");visit.put("sourceEventId",null);
            run(d,"PROFILE_VISIT",3,visit,"APPLIED");
            // Later visibility cannot rewrite the original captured visit categories.
            d.execute(event("VISIBILITY_CHANGE","student-3-01",START.plusSeconds(4),Map.of("accountId","account-3-01",
                "wishId","peer","expectedVersion",1,"visibility","PRIVATE"),"APPLIED"));
            run(d,"PROFILE_VISIT",5,visit,"APPLIED");
            var exported=d.relationalState();var checked=d.verifyBehaviorState(exported);assertThat(checked.visits()).isEqualTo(2);
            var missing=altered(exported,rows->rows.get("feed_visit_evidence").removeFirst());
            assertThat(d.verifyRelationalState(missing).tables()).isEqualTo(38);
            assertThatThrownBy(()->d.verifyBehaviorState(missing)).hasMessage("BEHAVIOR_MISSING_VISIT_EVIDENCE");
            assertThatThrownBy(()->d.verifyBehaviorState(altered(exported,rows->((ObjectNode)rows.get("feed_visit_evidence").getFirst())
                .put("target_author_id",d.id("STUDENT",ACTOR).toString())))).hasMessage("BEHAVIOR_VISIT_EVIDENCE_BINDING");
            assertThatThrownBy(()->d.verifyBehaviorState(altered(exported,rows->((ObjectNode)rows.get("feed_visit_evidence").getFirst())
                .putArray("source_versions").add("baseline:"+START).add("history:999999999")))).hasMessage("BEHAVIOR_VISIT_HIGH_WATER");
            assertThatThrownBy(()->d.verifyBehaviorState(altered(exported,rows->((ObjectNode)rows.get("feed_visit_evidence").getFirst())
                .putArray("source_versions").add("baseline:"+START).add("history:1").add("wish:999999999")))).hasMessage("BEHAVIOR_VISIT_SOURCE_REFERENCE");
            assertThatThrownBy(()->d.verifyBehaviorState(altered(exported,rows->((ObjectNode)rows.get("feed_visit_evidence").getFirst())
                .putArray("category_ids").add("기타").add("기타")))).hasMessage("BEHAVIOR_VISIT_CATEGORIES");
            var wrongSource=altered(exported,rows->{
                var accountHistory=rows.get("feed_source_history").stream().filter(row->row.get("source_kind").asString().equals("card_balance_account")
                    && row.get("payload").get("student_id").asString().equals(d.id("STUDENT",ACTOR).toString())).findFirst().orElseThrow();
                var evidence=rows.get("feed_visit_evidence").stream().filter(row->row.get("category_ids").size()>0).findFirst().orElseThrow();
                var sources=(tools.jackson.databind.node.ArrayNode)evidence.get("source_versions");
                for(int i=0;i<sources.size();i++)if(sources.get(i).asString().startsWith("account:"))
                    sources.set(i,JSON.valueToTree("account:"+accountHistory.get("version").longValue()));
            });
            assertThat(d.verifyRelationalState(wrongSource).tables()).isEqualTo(38);
            assertThatThrownBy(()->d.verifyBehaviorState(wrongSource)).hasMessage("BEHAVIOR_VISIT_SOURCE_OWNER");
            assertThat(d.relationalState().state()).isEqualTo(exported.state());
        }
    }

    @Test void influenceAnnotationUsesActualExposureAndDecisionWithoutChangingDomainRows() throws Exception {
        try(var d=dispatcher()) {
            List<JsonNode> commands=new ArrayList<>();
            java.util.function.Function<ObjectNode,JsonNode> execute=e->{
                var result=d.execute(e);commands.add(e);return JSON.readTree(result.rawResult());
            };
            for(int n=0;n<2;n++) {
                JsonNode p=people().get(n);
                execute.apply(event("JOIN",p.get("logicalStudentId").asString(),START,Map.of(
                    "studentId",p.get("logicalStudentId").asString(),"accountId",p.get("logicalAccountId").asString(),
                    "academyId","academy-1","grade",3),"APPLIED"));
            }
            var peer=create("peer","peer-create",10000);peer.put("accountId","account-3-01");
            execute.apply(event("CREATE","student-3-01",START.plusSeconds(1),peer,"APPLIED"));
            var share=event("SHARE","student-3-01",START.plusSeconds(2),Map.of("accountId","account-3-01",
                "wishId","peer","expectedVersion",0,"visibility","ACADEMY"),"APPLIED");execute.apply(share);
            String card=share.get("eventId").asString();
            execute.apply(event("FEED_QUERY",ACTOR,START.plusSeconds(3),feed("page",List.of(card)),"APPLIED"));
            var exposureCommand=click("page",card,"seen");exposureCommand.remove("clickKind");
            var exposure=event("IMPRESSION",ACTOR,START.plusSeconds(4),exposureCommand,"APPLIED");execute.apply(exposure);
            var clickEvent=event("CLICK",ACTOR,START.plusSeconds(5),click("page",card,"seen"),"APPLIED");execute.apply(clickEvent);
            var decision=event("CREATE",ACTOR,START.plusSeconds(6),create("inspired","inspired-create",5000),"APPLIED");execute.apply(decision);
            var before=d.relationalState();var cash=d.cashState();
            var annotation=event("INFLUENCED_DECISION",ACTOR,START.plusSeconds(7),Map.of("signalType","CLICK",
                "signalEventId",clickEvent.get("eventId").asString(),"decisionEventId",decision.get("eventId").asString()),"APPLIED");
            annotation.putArray("causes").add(clickEvent.get("eventId").asString()).add(decision.get("eventId").asString());
            JsonNode receipt=execute.apply(annotation);
            assertThat(receipt.get("exposureEventId")).isEqualTo(exposure.get("eventId"));
            assertThat(receipt.get("domainMutationPerformed").booleanValue()).isFalse();
            assertThat(d.relationalState().state().tables()).isEqualTo(before.state().tables());assertThat(d.cashState()).isEqualTo(cash);
            assertThat(d.verifyBehaviorState(d.relationalState()).events()).isEqualTo(2);
            assertThat(SimulationInfluenceVerifier.verify(commands,d.results())).isEqualTo(1);
            assertThat(d.normalizedResponses().get("events").get(commands.size()-1).get("response")).isEqualTo(receipt);
            assertThat(JSON.readTree(d.execute(annotation).rawResult())).isEqualTo(receipt);
        }
    }

}
