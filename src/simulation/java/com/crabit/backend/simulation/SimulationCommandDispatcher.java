package com.crabit.backend.simulation;

import com.crabit.backend.balance.*;
import com.crabit.backend.recap.*;
import java.net.URI;
import java.nio.file.*;
import java.io.*;
import com.crabit.backend.behavior.*;
import com.crabit.backend.demo.DemoSimulationCashService;
import com.crabit.backend.e2e.SeedFixtureCatalog;
import com.crabit.backend.relationship.*;
import com.crabit.backend.wish.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Stateful local command execution. Never imports expected results into the database. */
public final class SimulationCommandDispatcher implements AutoCloseable {
    private static final Set<String> SUPPORTED = Set.of("JOIN", "GRANT", "PURCHASE", "BALANCE_LOOKUP",
        "CREATE", "DEPOSIT", "WITHDRAW", "TRANSFER", "COMPLETE", "ABANDON", "DELETE",
        "SHARE", "VISIBILITY_CHANGE", "FOLLOW", "UNFOLLOW", "BLOCK", "UNBLOCK", "PROFILE_VISIT", "FEED_QUERY", "IMPRESSION", "CLICK", "INFLUENCED_DECISION", "RETURN_FROM_DORMANCY", "CLOSE_WEEK", "CLOSE_MONTH");
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private final SimulationDomainRuntime runtime;
    private final com.crabit.backend.recommendation.SimulationFeedSession feedSession;
    private final SimulationSharedCardIds recordedCardIds;
    private final JsonNode schema;
    private final String dataset;
    private final Map<String,JsonNode> students = new HashMap<>();
    private final Map<String,UUID> identities = new TreeMap<>();
    private final Map<String,JsonNode> commands = new HashMap<>();
    private final Map<String,Result> results = new LinkedHashMap<>();
    private final Map<String,JsonNode> verifiedCursors = new HashMap<>();
    private final Set<String> joined = new HashSet<>();
    private record ImpressionKey(String actor, String logicalId) {}
    private final Map<ImpressionKey,UUID> impressions = new HashMap<>();
    private long sequence;
    private int previousPhase = -1;
    private Instant previous = SimulationCashOracle.START;
    private boolean poisoned;
    private SimulationRelationalState.Catalog relationalCatalog;
    private Result mismatchedResult;
    private RecapConfiguration recapConfiguration;
    private final Set<String> closedPeriods = new HashSet<>();

    /** Explicit local service configuration; credentials are never part of exported records. */
    public synchronized void configureRecap(URI endpoint,String credential,Path evidenceRoot) throws IOException {
        require(sequence==0 && recapConfiguration==null,"RECAP_CONFIGURATION_BEFORE_REPLAY");
        SimulationRecapExecution.validateEndpoint(endpoint);
        require(credential!=null && !credential.isBlank(),"RECAP_CREDENTIAL_REQUIRED");
        Path root=evidenceRoot.toAbsolutePath().normalize();
        require(!Files.isSymbolicLink(root) && Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS),"RECAP_EVIDENCE_ROOT_REQUIRED");
        recapConfiguration=new RecapConfiguration(endpoint,credential,root.toRealPath());
    }
    private static final class RecapConfiguration {
        final URI endpoint; final String credential; final Path root;
        RecapConfiguration(URI endpoint,String credential,Path root) {this.endpoint=endpoint;this.credential=credential;this.root=root;}
    }

    public record Result(String eventId, String status, String errorCode, byte[] rawResult) {
        public Result { rawResult=rawResult.clone(); }
        @Override public byte[] rawResult() { return rawResult.clone(); }
    }

    /** The schema must be the caller's trusted repository schema, never an untrusted bundle override. */
    public SimulationCommandDispatcher(JsonNode trustedSchema, String datasetId, String manifestDigest, JsonNode people) {
        this(trustedSchema,datasetId,manifestDigest,people,null);
    }
    public SimulationCommandDispatcher(JsonNode trustedSchema,String datasetId,String manifestDigest,JsonNode people,
            com.crabit.backend.recommendation.SimulationFeedSession feedSession) {
        this(trustedSchema,datasetId,manifestDigest,people,feedSession,null);
    }
    SimulationCommandDispatcher(JsonNode trustedSchema,String datasetId,String manifestDigest,JsonNode people,
            com.crabit.backend.recommendation.SimulationFeedSession feedSession,SimulationSharedCardIds recordedCardIds) {
        this.feedSession=feedSession;
        this.recordedCardIds=recordedCardIds;
        schema=trustedSchema.deepCopy(); dataset=datasetId;
        require(datasetId.matches("sha256:[0-9a-f]{64}") && manifestDigest.matches("sha256:[0-9a-f]{64}"),"DATASET_ID");
        SimulationBundleReader.validate(schema.get("$defs").get("students"),people,"students");
        Map<Integer,Integer> grades=new HashMap<>(), initial=new HashMap<>();
        Set<String> accounts=new HashSet<>(), academies=new HashSet<>(); int owners=0;
        for(JsonNode p:people) {
            String id=str(p,"logicalStudentId"), account=str(p,"logicalAccountId"), academy=str(p,"logicalAcademyId");
            require(students.putIfAbsent(id,p.deepCopy())==null && accounts.add(account),"DUPLICATE_IDENTITY");
            academies.add(academy); int grade=p.get("grade").intValue(); grades.merge(grade,1,Integer::sum);
            Instant joinedAt=Instant.parse(str(p,"joinedAt"));
            if(joinedAt.equals(SimulationCashOracle.START))initial.merge(grade,1,Integer::sum);
            else require(!joinedAt.isBefore(Instant.parse("2026-06-30T15:00:00Z")) && joinedAt.isBefore(SimulationCashOracle.END),"JOIN_TIME");
            boolean owner=p.get("isOwner").booleanValue(); if(owner)owners++;
            bind("STUDENT",id,owner?SeedFixtureCatalog.OWNER_ID:UUID.randomUUID());
            bind("ACCOUNT",account,owner?SeedFixtureCatalog.OWNER_ACCOUNT_ID:UUID.randomUUID());
        }
        require(owners==1 && academies.size()==1,"POPULATION_IDENTITY");
        for(int grade=3;grade<=6;grade++)require(grades.getOrDefault(grade,0)==25 && initial.getOrDefault(grade,0)==20,"POPULATION_GRADE");
        String academy=academies.iterator().next(); bind("ACADEMY",academy,SeedFixtureCatalog.PRIMARY_ACADEMY_ID);
        runtime=new SimulationDomainRuntime(feedSession,recordedCardIds);
        try {
            runtime.executeAt(previous,s->new TransactionTemplate(s.service(PlatformTransactionManager.class)).execute(tx->{
                // Reserve a deterministic, JS-safe range before any source row/history is created.
                // Target import rejects collisions; existing target counters are never silently reused.
                long sequenceStart=(Long.parseLong(dataset.substring(7,15),16)+1L)*1_000_000L;
                for(String sequenceName:List.of("feed_source_history_version_seq","ledger_event_application_order_seq","student_follow_activation_seq"))
                    s.jdbc().execute("ALTER SEQUENCE public."+sequenceName+" RESTART WITH "+sequenceStart);
                s.jdbc().update("INSERT INTO academy(id,name) VALUES (?,'Assumption-based simulation')",id("ACADEMY",academy));
                s.jdbc().update("INSERT INTO demo_simulation_dataset(dataset_id,manifest_digest,state,starts_at,ends_at) VALUES (?,?,'BUILDING',?,?)",
                    dataset,manifestDigest,Timestamp.from(previous),Timestamp.from(SimulationCashOracle.END));
                return null;
            }));
        } catch(RuntimeException|Error e) { runtime.close(); throw e; }
    }

    public static boolean supports(String kind) { return SUPPORTED.contains(kind); }

    public record ObservedStep(JsonNode event,Result result) {
        public ObservedStep {event=event.deepCopy();}
        @Override public JsonNode event() {return event.deepCopy();}
    }
    private boolean observing;
    /** Policy discovery only: returns the realized command for subsequent strict fixed-event replay. */
    public synchronized ObservedStep observe(JsonNode input) {
        require(!commands.containsKey(str(input,"eventId")),"OBSERVATION_EVENT_ALREADY_EXECUTED");
        if(str(input,"kind").equals("FEED_QUERY"))
            require(input.path("command").path("orderedCardIds").isEmpty(),"OBSERVATION_REQUIRES_UNKNOWN_FEED_ORDER");
        observing=true;
        try {
            var result=execute(input);
            return new ObservedStep(commands.get(result.eventId()),result);
        } finally {observing=false;}
    }

    public synchronized Result execute(JsonNode input) {
        require(!poisoned,"DISPATCHER_POISONED");
        JsonNode e=input.deepCopy();
        SimulationBundleReader.validate(schema.get("$defs").get("event"),e,"event");
        String event=str(e,"eventId"), kind=str(e,"kind"), actor=str(e,"actorStudentId");
        require(SUPPORTED.contains(kind),"COMMAND_NOT_IMPLEMENTED:"+kind);
        if(commands.containsKey(event)) {
            require(commands.get(event).equals(e),"EVENT_ID_CONFLICT"); return results.get(event);
        }
        Instant when=Instant.parse(str(e,"occurredAt"));
        require(e.get("sequence").longValue()>sequence && !when.isBefore(previous)
            && !when.isBefore(SimulationCashOracle.START) && when.isBefore(SimulationCashOracle.END),"EVENT_ORDER");
        int phase=kind.equals("CLOSE_WEEK")?0:kind.equals("CLOSE_MONTH")?1:2;
        require(!when.equals(previous) || phase>=previousPhase,"EVENT_PHASE_ORDER");
        Set<String> causes=new HashSet<>();
        for(JsonNode cause:e.get("causes")) require(causes.add(cause.asString()) && results.containsKey(cause.asString()),"CAUSE_NOT_EARLIER");
        Set<String> refs=new HashSet<>();
        for(JsonNode ref:e.get("artifactRefs"))require(refs.add(ref.asString()),"DUPLICATE_ARTIFACT_REF");
        require(refs.contains(str(e.get("outcome"),"resultRef")),"OUTCOME_REF");
        SimulationFeedContinuation.source(e,commands);
        JsonNode person=students.get(actor), c=e.get("command");
        for(String field:c.propertyNames())if(field.endsWith("Ref"))require(refs.contains(str(c,field)),"COMMAND_ARTIFACT_REF");
        require(person!=null && !when.isBefore(Instant.parse(str(person,"joinedAt"))),"ACTOR_ENROLLMENT");
        if(kind.equals("JOIN")) {
            require(!joined.contains(actor) && when.equals(Instant.parse(str(person,"joinedAt")))
                && actor.equals(str(c,"studentId")) && str(c,"accountId").equals(str(person,"logicalAccountId"))
                && str(c,"academyId").equals(str(person,"logicalAcademyId")) && c.get("grade").equals(person.get("grade")),"JOIN_IDENTITY");
        } else require(joined.contains(actor),"JOIN_REQUIRED");
        if(kind.equals("PROFILE_VISIT")) {
            require(id("ACADEMY",str(c,"academyId")).equals(id("ACADEMY",str(person,"logicalAcademyId"))),"VISIT_ACADEMY");
            id("STUDENT",str(c,"targetStudentId"));
            if(!c.get("sourceEventId").isNull()) require(causes.contains(str(c,"sourceEventId")),"VISIT_SOURCE_CAUSE");
        }
        if(Set.of("FEED_QUERY","IMPRESSION","CLICK").contains(kind)) {
            require(id("ACADEMY",str(c,"academyId")).equals(id("ACADEMY",str(person,"logicalAcademyId"))),"FEED_ACADEMY");
            if(kind.equals("FEED_QUERY")) {
                require(!identities.containsKey("FEED_CONTEXT:"+str(c,"resultContextId")),"FEED_CONTEXT_ID_CONFLICT");
                Set<String> cards=new HashSet<>();
                for(JsonNode card:c.get("orderedCardIds")) {
                    require(cards.add(card.asString()),"FEED_DUPLICATE_CARD"); id("SHARED_CARD",card.asString());
                }
            } else {
                id("FEED_CONTEXT",str(c,"resultContextId")); id("SHARED_CARD",str(c,"cardId"));
            }
        }
        if(kind.equals("GRANT") || kind.equals("PURCHASE")) {
            require(commands.values().stream().noneMatch(old->old.get("command").has("cashEntryId")
                && str(old.get("command"),"cashEntryId").equals(str(c,"cashEntryId"))),"CASH_ENTRY_DUPLICATE");
        }
        if(kind.equals("TRANSFER"))require(new HashSet<>(List.of(str(c,"rootEventId"),str(c,"sourceEffectId"),str(c,"destinationEffectId"))).size()==3,"TRANSFER_IDENTITIES");
        if(kind.equals("CLOSE_WEEK") || kind.equals("CLOSE_MONTH")) {
            require(recapConfiguration!=null,"RECAP_CONFIGURATION_REQUIRED");
            require(when.equals(LocalDate.parse(str(c,"endExclusive")).atStartOfDay(RecapPeriods.SEOUL).toInstant()),"RECAP_CLOSE_INSTANT");
            SimulationRecapPreparation.period(kind,LocalDate.parse(str(c,"startInclusive")),LocalDate.parse(str(c,"endExclusive")),Clock.fixed(when,RecapPeriods.SEOUL));
            require(!identities.containsKey("RECAP_GENERATION:"+str(c,"generationId")),"RECAP_GENERATION_ID_CONFLICT");
            require(!closedPeriods.contains(kind+":"+str(c,"accountId")+":"+str(c,"startInclusive")),"RECAP_PERIOD_ALREADY_CLOSED");
            require(!Files.exists(recapConfiguration.root.resolve("event-"+e.get("sequence").longValue()),LinkOption.NOFOLLOW_LINKS),"RECAP_EVIDENCE_ALREADY_EXISTS");
        }
        // Unsupported media and unknown logical identities fail before any domain side effects.
        if(kind.equals("CREATE"))require(c.get("photoId").isNull(),"PHOTO_REPLAY_NOT_IMPLEMENTED");
        if(kind.equals("CREATE") && identities.containsKey("WISH:"+str(c,"wishId"))) {
            require(commands.values().stream().anyMatch(old->str(old,"kind").equals("CREATE")
                && str(old,"actorStudentId").equals(actor) && old.get("command").equals(c)),"WISH_ID_CONFLICT");
        }
        try {
            if(recordedCardIds!=null)recordedCardIds.begin(event);
            Result actual=runtime.executeAt(when,s->invoke(s,e,person));
            if(recordedCardIds!=null)recordedCardIds.finish();
            if(observing)((tools.jackson.databind.node.ObjectNode)e.get("outcome")).put("status",actual.status());
            if(!actual.status().equals(str(e.get("outcome"),"status")))mismatchedResult=actual;
            require(actual.status().equals(str(e.get("outcome"),"status")),"OUTCOME_MISMATCH:"+event+":"+actual.status());
            commands.put(event,e); results.put(event,actual); previous=when; previousPhase=phase; sequence=e.get("sequence").longValue();
            if(kind.equals("JOIN"))joined.add(actor);
            if((kind.equals("CLOSE_WEEK") || kind.equals("CLOSE_MONTH")) && actual.status().equals("APPLIED"))
                closedPeriods.add(kind+":"+str(c,"accountId")+":"+str(c,"startInclusive"));
            return actual;
        } catch(RuntimeException|Error failure) { poisoned=true; throw failure; }
    }

    private Result invoke(SimulationDomainRuntime.Services s, JsonNode e, JsonNode p) {
        String kind=str(e,"kind"), event=str(e,"eventId"); JsonNode c=e.get("command");
        UUID actor=id("STUDENT",str(e,"actorStudentId")), academy=id("ACADEMY",str(p,"logicalAcademyId"));
        UUID account=c.has("accountId")?id("ACCOUNT",str(c,"accountId")):null;
        String key=c.has("idempotencyKey")?str(c,"idempotencyKey"):null;
        try {
            Object value;
            switch(kind) {
                case "CLOSE_WEEK", "CLOSE_MONTH" -> {
                    if(!str(c,"accountId").equals(str(p,"logicalAccountId")))return result(event,"REJECTED","FORBIDDEN",Map.of("code","FORBIDDEN"));
                    var prepared=SimulationRecapPreparation.prepare(s,dataset,actor,account,UUID.randomUUID(),kind,
                        LocalDate.parse(str(c,"startInclusive")),LocalDate.parse(str(c,"endExclusive")));
                    bind("RECAP_GENERATION",str(c,"generationId"),prepared.generationId());
                    Path output=recapConfiguration.root.resolve("event-"+e.get("sequence").longValue());
                    try {
                        // Capture at the closure before Python; later replay mutations cannot alter this oracle.
                        String prefix="event-"+e.get("sequence").longValue()+"-period-";
                        var periodSource=SimulationRecapPeriodVerifier.capture(s.jdbc(),account,academy);
                        Files.writeString(recapConfiguration.root.resolve(prefix+"source.json"),JSON.writeValueAsString(periodSource),StandardOpenOption.CREATE_NEW);
                        Files.writeString(recapConfiguration.root.resolve(prefix+"input.json"),prepared.requestJson(),StandardOpenOption.CREATE_NEW);
                        var verified=SimulationRecapPeriodVerifier.verify(JSON.readTree(prepared.requestJson()),periodSource);
                        Files.writeString(recapConfiguration.root.resolve(prefix+"verification.json"),JSON.writeValueAsString(Map.of("period",verified,"peers",SimulationRecapPeerVerifier.verify(JSON.readTree(prepared.requestJson()),periodSource.get("peer_source")),"stories",SimulationRecapStoryVerifier.verify(JSON.readTree(prepared.requestJson()),periodSource.get("peer_source"),periodSource.get("story_source")),"authors",SimulationRecapAuthorVerifier.verify(JSON.readTree(prepared.requestJson()),periodSource))),StandardOpenOption.CREATE_NEW);
                        var completed=SimulationRecapExecution.complete(s,dataset,prepared,recapConfiguration.endpoint,recapConfiguration.credential,output);
                        // NOT_ELIGIBLE has no HTTP exchange, but its actual frozen input and stored row are still evidence.
                        if(!Files.exists(output)) {
                            Files.createDirectory(output);
                            Files.writeString(output.resolve("request.json"),prepared.requestJson(),StandardOpenOption.CREATE_NEW);
                        }
                        var stored=s.service(RecapGenerationRepository.class).findById(prepared.generationId()).orElseThrow();
                        Files.writeString(output.resolve("stored-state.json"),s.jdbc().queryForObject("SELECT to_jsonb(g)::text FROM recap_generation g WHERE id=?",String.class,stored.id()),StandardOpenOption.CREATE_NEW);
                        value=Map.of("generationId",completed.generationId(),"state",completed.state(),
                            "generationVersion",stored.generationVersion(),"inputDigest",prepared.inputDigest(),"pythonInvoked",completed.pythonInvoked());
                    } catch(IOException failure) {throw new UncheckedIOException(failure);}
                    finally {
                        if(Files.isDirectory(output) && !Files.exists(output.resolve("stored-state.json"))) {
                            try {
                                Files.writeString(output.resolve("stored-state.json"),s.jdbc().queryForObject(
                                    "SELECT to_jsonb(g)::text FROM recap_generation g WHERE id=?",String.class,prepared.generationId()),StandardOpenOption.CREATE_NEW);
                            } catch(IOException failure) {throw new UncheckedIOException(failure);}
                        }
                    }
                }
                case "RETURN_FROM_DORMANCY" -> value=SimulationDormancyVerifier.receipt(e,commands,results);
                case "INFLUENCED_DECISION" -> value=SimulationInfluenceVerifier.receipt(e,commands,results);
                case "JOIN" -> value=new TransactionTemplate(s.service(PlatformTransactionManager.class)).execute(tx->{
                    s.jdbc().update("INSERT INTO student(id,nickname,age,age_provenance) VALUES (?,?,?,'PROVIDED')",actor,str(p,"syntheticDisplayName"),p.get("grade").intValue()+6);
                    UUID membership=SeedFixtureCatalog.OWNER_ID.equals(actor)
                        ?UUID.fromString("00000000-0000-0000-0000-000000000501"):UUID.randomUUID();
                    s.jdbc().update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,?)",membership,actor,academy,Timestamp.from(s.clock().instant()));
                    s.jdbc().update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",account,actor,academy,Timestamp.from(s.clock().instant()));
                    s.jdbc().update("INSERT INTO demo_simulation_account(account_id,dataset_id,logical_student_id,logical_account_id,grade,is_owner) VALUES (?,?,?,?,?,?)",
                        account,dataset,str(p,"logicalStudentId"),str(p,"logicalAccountId"),p.get("grade").intValue(),p.get("isOwner").booleanValue());
                    bind("MEMBERSHIP",str(p,"logicalStudentId"),membership);
                    return Map.of("studentId",actor,"accountId",account,"academyId",academy,"joinedAt",s.clock().instant());
                });
                case "GRANT", "PURCHASE" -> {
                    if(!str(c,"accountId").equals(str(p,"logicalAccountId")))return result(event,"REJECTED","FORBIDDEN",Map.of("code","FORBIDDEN"));
                    if(kind.equals("GRANT")) {
                        Instant scheduled=Instant.parse(str(c,"scheduledAt"));
                        require(!scheduled.isAfter(s.clock().instant()) && !scheduled.isBefore(Instant.parse(str(p,"joinedAt")))
                            && str(c,"budgetMonth").equals(YearMonth.from(scheduled.atZone(ZoneId.of("Asia/Seoul"))).toString()),"GRANT_SCHEDULE");
                    }
                    try { value=Map.of("cardFunds",s.service(DemoSimulationCashService.class).apply(dataset,str(c,"cashEntryId"),account,
                        DemoSimulationCashService.Kind.valueOf(kind),num(c,"amountKrw"),s.clock().instant())); }
                    catch(IllegalArgumentException failure) {
                        if(!"Cash out of range".equals(failure.getMessage()))throw failure;
                        return result(event,"REJECTED","CASH_OUT_OF_RANGE",Map.of("code","CASH_OUT_OF_RANGE"));
                    }
                }
                case "BALANCE_LOOKUP" -> {
                    if(!str(c,"accountId").equals(str(p,"logicalAccountId")))return result(event,"REJECTED","FORBIDDEN",Map.of("code","FORBIDDEN"));
                    var lookup=s.service(CardBalanceSyncService.class).refresh(account,BalanceLookupMethod.USER_REQUESTED);
                    var obs=lookup instanceof CardBalanceSyncResult.Success ok?ok.observation():((CardBalanceSyncResult.Failure)lookup).observation();
                    var observation=new LinkedHashMap<String,Object>();
                    bind("BALANCE_OBSERVATION",event,obs.id());
                    observation.put("observationId",obs.id()); observation.put("status",obs.status()); observation.put("observedAt",obs.observedAt());
                    observation.put("sourceKind","SIMULATION"); observation.put("datasetId",dataset);
                    observation.put("balance",obs.actualCardBalance()==null?null:obs.actualCardBalance().won()); value=observation;
                    if(lookup instanceof CardBalanceSyncResult.Failure)return result(event,"FAILED","BALANCE_SYNC_FAILED",value);
                }
                case "FEED_QUERY" -> {
                    BehaviorModels.FeedResult feed=null;
                    try {
                        if(feedSession!=null)feedSession.begin(e.get("sequence").longValue());
                        try {
                            feed=s.service(BehaviorService.class).createResult(actor,academy,
                                SimulationFeedContinuation.resolve(e,commands,results),c.get("limit").intValue());
                        } finally { if(feedSession!=null)feedSession.finish(feed); }
                    } catch(IOException failure) { throw new UncheckedIOException(failure); }
                    // Compare actual service order; never populate context rows from expected card IDs.
                    List<UUID> expected=new ArrayList<>();
                    for(JsonNode card:c.get("orderedCardIds"))expected.add(id("SHARED_CARD",card.asString()));
                    if(observing) {
                        var observed=JSON.createArrayNode();
                        for(var card:feed.items()) {
                            String logical=identities.entrySet().stream()
                                .filter(entry->entry.getKey().startsWith("SHARED_CARD:") && entry.getValue().equals(card.sharedCardId()))
                                .map(entry->entry.getKey().substring("SHARED_CARD:".length())).findFirst()
                                .orElseThrow(()->new IllegalStateException("OBSERVED_CARD_IDENTITY_MISSING"));
                            observed.add(logical);
                        }
                        ((tools.jackson.databind.node.ObjectNode)c).set("orderedCardIds",observed);
                    } else if(!feed.items().stream().map(SharedCardProjection::sharedCardId).toList().equals(expected)) {
                        mismatchedResult=result(event,"APPLIED",null,feed);
                        throw new IllegalStateException("FEED_ORDER_MISMATCH:"+event);
                    }
                    bind("FEED_CONTEXT",str(c,"resultContextId"),feed.resultContextId());
                    if(feed.recommendationResultId()!=null)bindFirst("FEED_RECOMMENDATION",event,UUID.fromString(feed.recommendationResultId()));
                    if(feed.nextCursor()!=null) {
                        var decoded=s.service(SharedCardCursor.class).decodeV2(feed.nextCursor(),actor,academy);
                        // Repeated sessions/states retain the first issuing command's logical identity.
                        bindFirst("FEED_SESSION",event,decoded.contextId());
                        bindFirst("FEED_PAGE_STATE",event,decoded.stateId());
                        verifiedCursors.put(event,JSON.valueToTree(Map.of("version",2,"operation","listAcademySharedCards",
                            "studentId",actor,"academyId",academy,"sessionId",decoded.contextId(),
                            "stateId",decoded.stateId(),"expiresAt",decoded.expiresAt())));
                    }
                    value=feed;
                }
                case "IMPRESSION", "CLICK" -> {
                    UUID behaviorId=UUID.randomUUID();
                    var impressionKey=new ImpressionKey(str(e,"actorStudentId"),str(c,"impressionId"));
                    UUID impression=impressions.computeIfAbsent(impressionKey,ignored->UUID.randomUUID());
                    var accepted=s.service(BehaviorService.class).collect(actor,academy,
                        new BehaviorModels.Event(behaviorId,kind.equals("CLICK")?"FEED_CLICK":"FEED_EXPOSURE",
                            s.clock().instant(),null,id("FEED_CONTEXT",str(c,"resultContextId")),
                            id("SHARED_CARD",str(c,"cardId")),c.get("position").intValue(),impression,
                            kind.equals("CLICK")?str(c,"clickKind"):null));
                    bind("BEHAVIOR_EVENT",event,accepted.body().eventId()); value=accepted;
                }
                case "PROFILE_VISIT" -> {
                    UUID behaviorId=UUID.randomUUID();
                    var accepted=s.service(BehaviorService.class).collect(actor,academy,
                        new BehaviorModels.Event(behaviorId,"PROFILE_VISIT",s.clock().instant(),
                            id("STUDENT",str(c,"targetStudentId")),null,null,null,null,null));
                    bind("BEHAVIOR_EVENT",event,accepted.body().eventId());
                    value=accepted;
                }
                case "CREATE" -> {
                    var created=s.service(WishLifecycleService.class).create(actor,academy,account,key,str(c,"purpose"),num(c,"targetAmount"),date(c,"startDate"),date(c,"targetDate"),null);
                    bind("WISH",str(c,"wishId"),created.wish().id()); value=created;
                }
                case "DEPOSIT", "WITHDRAW" -> {
                    var service=s.service(WishFundMovementService.class); UUID wish=id("WISH",str(c,"wishId"));
                    value=kind.equals("DEPOSIT")?service.deposit(actor,academy,account,wish,key,num(c,"amount"),num(c,"expectedVersion"))
                        :service.withdraw(actor,academy,account,wish,key,num(c,"amount"),num(c,"expectedVersion"));
                }
                case "TRANSFER" -> {
                    UUID source=id("WISH",str(c,"sourceWishId")), destination=id("WISH",str(c,"destinationWishId"));
                    require(!str(c,"sourceEffectId").equals(str(c,"destinationEffectId")),"TRANSFER_EFFECT_IDENTITIES");
                    var transfer=s.service(WishFundMovementService.class).transfer(actor,academy,account,key,source,destination,num(c,"amount"),num(c,"sourceExpectedVersion"),num(c,"destinationExpectedVersion"));
                    bind("LEDGER_ROOT",str(c,"rootEventId"),transfer.eventId());
                    bind("LEDGER_EFFECT",str(c,"sourceEffectId"),s.jdbc().queryForObject("SELECT id FROM ledger_wish_effect WHERE event_id=? AND wish_id=?",UUID.class,transfer.eventId(),source));
                    bind("LEDGER_EFFECT",str(c,"destinationEffectId"),s.jdbc().queryForObject("SELECT id FROM ledger_wish_effect WHERE event_id=? AND wish_id=?",UUID.class,transfer.eventId(),destination));
                    value=transfer;
                }
                case "COMPLETE", "ABANDON", "DELETE" -> {
                    var service=s.service(WishLifecycleService.class); UUID wish=id("WISH",str(c,"wishId")); long version=num(c,"expectedVersion");
                    value=switch(kind) { case "COMPLETE" -> service.complete(actor,academy,account,wish,key,version);
                        case "ABANDON" -> service.abandon(actor,academy,account,wish,key,version); default -> service.delete(actor,academy,account,wish,key,version); };
                }
                case "SHARE", "VISIBILITY_CHANGE" -> value=s.service(WishLifecycleService.class).patch(actor,academy,account,id("WISH",str(c,"wishId")),num(c,"expectedVersion"),
                    new WishPatch(null,null,false,null,WishVisibility.valueOf(str(c,"visibility"))));
                case "FOLLOW", "UNFOLLOW", "BLOCK", "UNBLOCK" -> {
                    require(str(e,"actorStudentId").equals(str(c,"viewerStudentId")) && academy.equals(id("ACADEMY",str(c,"academyId"))),"SOCIAL_DIRECTION");
                    UUID target=id("STUDENT",str(c,"ownerStudentId")); var service=s.service(RelationshipCommandService.class);
                    switch(kind) { case "FOLLOW" -> service.follow(actor,academy,target,s.clock().instant());
                        case "UNFOLLOW" -> service.unfollow(actor,academy,target,s.clock().instant());
                        case "BLOCK" -> service.blockStudent(actor,target,s.clock().instant());
                        default -> service.unblockStudent(actor,target,s.clock().instant()); }
                    value=Map.of("actorStudentId",actor,"ownerStudentId",target,"operation",kind,"occurredAt",s.clock().instant());
                }
                default -> throw new IllegalStateException("COMMAND_NOT_IMPLEMENTED");
            }
            if(value instanceof WishLifecycleService.MutationOutcome mutation && mutation.eventId()!=null)
                bindFirst("LEDGER_ROOT",event,mutation.eventId());
            if(value instanceof WishFundMovementService.MutationOutcome mutation && mutation.eventId()!=null)
                bindFirst("LEDGER_ROOT",event,mutation.eventId());
            if(c.has("wishId") && identities.containsKey("WISH:"+str(c,"wishId"))) {
                // The first actual materialization owns the card's logical identity. Retain old IDs after privacy deletion.
                for(UUID card:s.jdbc().query("SELECT id FROM shared_card WHERE wish_id=?",(rs,n)->rs.getObject(1,UUID.class),id("WISH",str(c,"wishId")))) {
                    if(identities.entrySet().stream().noneMatch(entry->entry.getKey().startsWith("SHARED_CARD:") && entry.getValue().equals(card)))
                        bind("SHARED_CARD",event,card);
                }
            }
            return result(event,"APPLIED",null,value);
        } catch(WishLifecycleException failure) {
            return result(event,failure.code()==WishLifecycleException.Code.BALANCE_SYNC_FAILED?"FAILED":"REJECTED",failure.code().name(),Map.of("code",failure.code().name()));
        } catch(SharedCardQueryService.FeedCursorExpired failure) {
            return result(event,"REJECTED","FEED_CURSOR_EXPIRED",Map.of("code","FEED_CURSOR_EXPIRED"));
        } catch(BehaviorException failure) {
            return result(event,"REJECTED",failure.code(),Map.of("code",failure.code()));
        } catch(RelationshipException failure) {
            return result(event,"REJECTED",failure.code().name(),Map.of("code",failure.code().name()));
        }
    }

    private static Result result(String event,String status,String error,Object value) { return new Result(event,status,error,JSON.writeValueAsBytes(value)); }
    private void bindFirst(String kind,String logical,UUID value) {
        if(identities.entrySet().stream().noneMatch(e->e.getKey().startsWith(kind+":") && e.getValue().equals(value)))bind(kind,logical,value);
    }
    public synchronized JsonNode normalizedResponses() {
        Map<String,String> recapDigests=new HashMap<>();
        var recapNormalizer=new SimulationRecapNormalization(identities);
        if(identities.keySet().stream().anyMatch(k->k.startsWith("RECAP_GENERATION:")))
            for(JsonNode row:relationalState().state().tables().get("recap_generation")) {
                recapNormalizer.stored(row);
                JsonNode request=JSON.readTree(row.get("request_json").asString());
                recapDigests.put(row.get("input_digest").asString(),recapNormalizer.logicalDigest(request));
            }
        var normalizer=new SimulationResponseNormalizer(identities,recapDigests);
        var rows=JSON.createArrayNode();
        for(var result:results.values()) {
            JsonNode event=commands.get(result.eventId());
            var row=rows.addObject();
            row.put("eventId",result.eventId());row.set("sequence",event.get("sequence"));
            row.set("occurredAt",event.get("occurredAt"));row.set("kind",event.get("kind"));
            row.put("status",result.status());row.put("errorCode",result.errorCode());
            row.set("response",normalizer.normalize(str(event,"kind"),result.rawResult(),verifiedCursors.get(result.eventId())));
        }
        var projection=JSON.createObjectNode();
        projection.put("schemaVersion",1);projection.put("schemaKind","simulation-normalized-responses");
        projection.put("datasetId",dataset);projection.put("fullDatasetNormalizationPerformed",false);
        projection.set("events",rows);return projection;
    }
    /** A consistent read of this runtime's committed cash tables, including zero-funded joined accounts. */
    public synchronized JsonNode cashState() {
        require(!poisoned && !joined.isEmpty(),"CASH_EXPORT_REQUIRES_SUCCESSFUL_REPLAY");
        return runtime.executeAt(previous,s->{
            var tx=new TransactionTemplate(s.service(PlatformTransactionManager.class));
            tx.setReadOnly(true);
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return tx.execute(ignored->SimulationReplayCashExport.capture(s.jdbc(),dataset,identities,orderedCommands()));
        });
    }
    public synchronized SimulationAllocationState.State allocationState() {
        require(!poisoned && !joined.isEmpty(),"ALLOCATION_EXPORT_REQUIRES_SUCCESSFUL_REPLAY");
        return runtime.executeAt(previous,s->{
            var tx=new TransactionTemplate(s.service(PlatformTransactionManager.class));
            tx.setReadOnly(true);
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return tx.execute(ignored->SimulationAllocationState.capture(s.jdbc(),dataset));
        });
    }
    public synchronized SimulationAllocationState.Verification verifyAllocationState(SimulationAllocationState.State state) {
        require(!poisoned && !joined.isEmpty(),"ALLOCATION_EXPORT_REQUIRES_SUCCESSFUL_REPLAY");
        var verified=SimulationAllocationState.verify(state,dataset,identities,previous);
        SimulationAllocationState.verifyCommands(state,identities,orderedCommands());
        return verified;
    }
    public synchronized SimulationObservationState.State observationState() {
        require(!poisoned && !joined.isEmpty(),"OBSERVATION_EXPORT_REQUIRES_SUCCESSFUL_REPLAY");
        return runtime.executeAt(previous,s->{
            var tx=new TransactionTemplate(s.service(PlatformTransactionManager.class));
            tx.setReadOnly(true);
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return tx.execute(ignored->SimulationObservationState.capture(s.jdbc(),dataset));
        });
    }
    public synchronized SimulationObservationState.Verification verifyObservationState(
        SimulationObservationState.State state,SimulationAllocationState.State allocations) {
        require(!poisoned && !joined.isEmpty(),"OBSERVATION_EXPORT_REQUIRES_SUCCESSFUL_REPLAY");
        var joinedIdentities=new HashMap<>(identities);
        Set<UUID> accounts=new HashSet<>();
        for(String student:joined)accounts.add(id("ACCOUNT",str(students.get(student),"logicalAccountId")));
        joinedIdentities.entrySet().removeIf(e->e.getKey().startsWith("ACCOUNT:") && !accounts.contains(e.getValue()));
        return SimulationObservationState.verify(state,allocations,dataset,joinedIdentities,previous);
    }
    public synchronized SimulationPreservationFingerprint.Snapshot preservationFingerprint() {
        require(!poisoned && !joined.isEmpty(),"PRESERVATION_REQUIRES_SUCCESSFUL_REPLAY");
        return runtime.preservationFingerprint();
    }
    public synchronized SimulationRelationalState.Export relationalState() {
        require(!poisoned && !joined.isEmpty(),"RELATIONAL_EXPORT_REQUIRES_SUCCESSFUL_REPLAY");
        return runtime.executeAt(previous,s->{
            var tx=new TransactionTemplate(s.service(PlatformTransactionManager.class));tx.setReadOnly(true);
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var export=tx.execute(ignored->SimulationRelationalState.capture(s.jdbc(),dataset));
            relationalCatalog=export.catalog();return export;
        });
    }
    public synchronized SimulationRelationalState.Verification verifyRelationalState(SimulationRelationalState.Export export) {
        require(!poisoned && !joined.isEmpty(),"RELATIONAL_EXPORT_REQUIRES_SUCCESSFUL_REPLAY");
        Set<UUID> studentIds=new HashSet<>(),accountIds=new HashSet<>(),academyIds=new HashSet<>();
        for(String student:joined) {
            studentIds.add(id("STUDENT",student));
            accountIds.add(id("ACCOUNT",str(students.get(student),"logicalAccountId")));
            academyIds.add(id("ACADEMY",str(students.get(student),"logicalAcademyId")));
        }
        require(relationalCatalog!=null && relationalCatalog.equals(export.catalog()),"RELATIONAL_CATALOG_CHANGED");
        return SimulationRelationalState.verify(export.state(),relationalCatalog,dataset,studentIds,accountIds,academyIds);
    }
    /** Reads state bytes against the catalog captured from this migrated replay DB, never an input catalog. */
    public synchronized SimulationRelationalState.Export readRelationalState(byte[] bytes) {
        require(relationalCatalog!=null,"RELATIONAL_CATALOG_REQUIRED");
        var export=new SimulationRelationalState.Export(relationalCatalog,SimulationRelationalInput.read(bytes));
        verifyRelationalState(export);
        return export;
    }
    /** Exercises typed SQL insertion into disposable tables; public domain rows and triggers remain intact. */
    public synchronized SimulationRelationalStaging.Report stageRelationalState(byte[] bytes) {
        var export=readRelationalState(bytes);
        var before=preservationFingerprint();
        try {
            return runtime.executeAt(previous,s->{
                var tx=new TransactionTemplate(s.service(PlatformTransactionManager.class));
                tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return tx.execute(ignored->{
                    var live=SimulationRelationalState.capture(s.jdbc(),dataset);
                    require(live.catalog().equals(export.catalog()),"RELATIONAL_CATALOG_CHANGED");
                    return SimulationRelationalStaging.stage(s.jdbc(),export);
                });
            });
        } finally {
            require(before.equals(preservationFingerprint()),"RELATIONAL_STAGING_PUBLIC_STATE_CHANGED");
        }
    }
    /** Captures exact selected original rows in this owned replay DB, failing on stale/drifting state. */
    public synchronized SimulationSelectionBackup.Backup backupSelection(
            Map<String,List<JsonNode>> selection, String expectedFingerprint) {
        var before=preservationFingerprint();
        require(before.digest().equals(expectedFingerprint),"SELECTION_BACKUP_REVISION_CONFLICT");
        // Freeze caller-owned mutable JSON keys before any SQL reads.
        var frozen=new TreeMap<String,List<JsonNode>>();
        selection.forEach((table,keys)->frozen.put(table,keys.stream().map(JsonNode::deepCopy).toList()));
        return runtime.preservationWindow(expectedFingerprint,(jdbc,lockedBefore)->{
            var export=SimulationRelationalState.capture(jdbc,dataset);
            relationalCatalog=export.catalog();
            verifyRelationalDomain(export);
            return SimulationSelectionBackup.capture(export,frozen,lockedBefore);
        });
    }
    /** Read-back of a saved backup against current original rows; never restores or accepts an input catalog. */
    public synchronized SimulationSelectionBackup.Backup verifySelectionBackup(byte[] bytes, String expectedDigest) {
        require(bytes!=null && bytes.length>0 && bytes.length<=SimulationBundleReader.MAX_ARTIFACT_BYTES,"SELECTION_BACKUP_SIZE");
        byte[] frozen=bytes.clone();
        require(SimulationBundleReader.digest(frozen).equals(expectedDigest),"SELECTION_BACKUP_CHECKSUM_MISMATCH");
        JsonNode root=SimulationBundleReader.parse(frozen);
        require(root.isObject() && root.path("beforeFingerprint").path("digest").isString()
            && root.path("tables").isObject(),"SELECTION_BACKUP_FORMAT");
        var current=relationalState();
        require(new HashSet<>(root.get("tables").propertyNames()).equals(current.state().tables().keySet()),
            "SELECTION_BACKUP_TABLES");
        var primary=new TreeMap<String,List<String>>();
        current.catalog().keys().stream().filter(SimulationRelationalState.Key::primary)
            .forEach(k->primary.put(k.table(),k.columns()));
        var selection=new TreeMap<String,List<JsonNode>>();
        for(String table:current.state().tables().keySet()) {
            JsonNode rows=root.get("tables").get(table);
            require(rows.isArray(),"SELECTION_BACKUP_ROWS");
            var keys=new ArrayList<JsonNode>();
            for(JsonNode row:rows) {
                require(row.isObject() && primary.containsKey(table),"SELECTION_BACKUP_KEY");
                var key=JSON.createObjectNode();
                for(String column:primary.get(table)) {
                    require(row.hasNonNull(column),"SELECTION_BACKUP_KEY");key.set(column,row.get(column));
                }
                keys.add(key);
            }
            selection.put(table,keys);
        }
        var observed=backupSelection(selection,root.get("beforeFingerprint").get("digest").asString());
        // Exact re-creation rejects unknown fields, altered rows, forged metadata and noncanonical transport.
        require(java.util.Arrays.equals(frozen,observed.bytes()),"SELECTION_BACKUP_CONTENT_MISMATCH");
        return observed;
    }
    /** An exact-row diagnostic cut; crossing references never cause automatic scope expansion. */
    public synchronized SimulationGraphBoundary.Report inspectGraphBoundary(
            SimulationRelationalState.Export export, Map<String,List<JsonNode>> selection) {
        verifyRelationalState(export);
        return SimulationGraphBoundary.inspect(export,selection);
    }
    /** Non-FK diagnostics with explicit coverage; never an import authorization. */
    public synchronized SimulationSemanticGraphBoundary.Report inspectSemanticGraphBoundary(
            SimulationRelationalState.Export export, Map<String,List<JsonNode>> selection) {
        verifyRelationalDomain(export);
        return SimulationSemanticGraphBoundary.inspect(export,selection);
    }
    public synchronized SimulationRelationalDomainVerifier.Verification verifyRelationalDomain(SimulationRelationalState.Export export) {
        verifyRelationalState(export);
        List<SimulationRelationalDomainVerifier.Person> people=new ArrayList<>();
        for(String logical:joined) {
            JsonNode p=students.get(logical);
            people.add(new SimulationRelationalDomainVerifier.Person(id("STUDENT",logical),id("ACCOUNT",str(p,"logicalAccountId")),
                id("ACADEMY",str(p,"logicalAcademyId")),id("MEMBERSHIP",logical),logical,str(p,"logicalAccountId"),
                p.get("grade").intValue(),p.get("isOwner").booleanValue(),Instant.parse(str(p,"joinedAt"))));
        }
        return SimulationRelationalDomainVerifier.verify(export,people,previous);
    }
    public synchronized SimulationBehaviorVerifier.Verification verifyBehaviorState(SimulationRelationalState.Export export) {
        verifyRelationalState(export);
        return SimulationBehaviorVerifier.verify(export,orderedCommands(),identities);
    }
    /** Only students whose JOIN actually completed participate; never invent future zero balances. */
    public synchronized SimulationCashOracle.Result verifyCashState(JsonNode state) {
        require(!poisoned && !joined.isEmpty(),"CASH_EXPORT_REQUIRES_SUCCESSFUL_REPLAY");
        SimulationBundleReader.validate(schema.get("$defs").get("cashState"),state,"cashState");
        var people=JSON.createArrayNode();
        joined.stream().sorted().forEach(student->people.add(students.get(student)));
        return new SimulationBundleCashVerifier().verify(JSON.valueToTree(Map.of("datasetId",dataset)),people,orderedCommands(),state);
    }
    public synchronized SimulationMonthlyBudgetVerifier.Verification verifyMonthlyBudgets(JsonNode cash) {
        require(!poisoned && !joined.isEmpty(),"MONTHLY_BUDGET_REQUIRES_SUCCESSFUL_REPLAY");
        var people=JSON.createArrayNode();joined.stream().sorted().forEach(student->people.add(students.get(student)));
        return SimulationMonthlyBudgetVerifier.verify(dataset,people,orderedCommands(),cash,previous);
    }
    public synchronized SimulationIdempotencyVerifier.Verified verifyIdempotencyState(SimulationRelationalState.Export export) {
        verifyRelationalState(export);
        return SimulationIdempotencyVerifier.verify(export,orderedCommands(),results(),identities());
    }
    private List<JsonNode> orderedCommands() {
        return results.keySet().stream().map(commands::get).toList();
    }
    private void bind(String kind,String logical,UUID value) {
        String key=kind+":"+logical; UUID existing=identities.get(key);
        require(value!=null && (existing==null || existing.equals(value)),"IDENTITY_CONFLICT");
        require(identities.entrySet().stream().noneMatch(e->e.getKey().startsWith(kind+":") && !e.getKey().equals(key) && e.getValue().equals(value)),"IDENTITY_NOT_BIJECTIVE");
        identities.put(key,value);
    }
    public synchronized UUID id(String kind,String logical) { UUID value=identities.get(kind+":"+logical); require(value!=null,"IDENTITY_UNKNOWN:"+kind+":"+logical); return value; }
    public synchronized Map<String,UUID> identities() { return Map.copyOf(identities); }
    public synchronized List<Result> results() { return List.copyOf(results.values()); }
    public synchronized Optional<Result> mismatchedResult() { return Optional.ofNullable(mismatchedResult); }
    private static String str(JsonNode n,String k) { return n.get(k).asString(); }
    private static long num(JsonNode n,String k) { return n.get(k).longValue(); }
    private static LocalDate date(JsonNode n,String k) { return n.get(k).isNull()?null:LocalDate.parse(str(n,k)); }
    private static void require(boolean valid,String code) { if(!valid)throw new IllegalStateException(code); }
    @Override public synchronized void close() { poisoned=true; runtime.close(); }
}
