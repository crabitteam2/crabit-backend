package com.crabit.backend.simulation;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Disposable real-service replay with immutable raw evidence; never an importer or full validator. */
public final class SimulationReplayRun {
    private static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    private SimulationReplayRun() {}
    private record Input(byte[] bytes,JsonNode event,String request,List<String> responses) {}

    public static final class RecapOptions {
        final java.net.URI endpoint; final String credential;
        public RecapOptions(java.net.URI endpoint,String credential) {
            com.crabit.backend.recap.SimulationRecapExecution.validateEndpoint(endpoint);
            if(credential==null || credential.isBlank())throw new IllegalArgumentException("RECAP_CREDENTIAL_REQUIRED");
            this.endpoint=endpoint;this.credential=credential;
        }
    }
    public static final class FeedOptions {
        final java.net.URI endpoint; final String credential;
        final java.time.Duration deadlineBudget;
        public FeedOptions(java.net.URI endpoint,String credential) {
            this(endpoint,credential,java.time.Duration.ofMillis(500));
        }
        public FeedOptions(java.net.URI endpoint,String credential,java.time.Duration deadlineBudget) {
            com.crabit.backend.recommendation.SimulationFeedExecution.validateEndpoint(endpoint);
            if(credential==null || credential.isBlank() || credential.indexOf('\r')>=0 || credential.indexOf('\n')>=0)
                throw new IllegalArgumentException("FEED_CREDENTIAL_REQUIRED");
            this.endpoint=endpoint;this.credential=credential;
            if(!Set.of(java.time.Duration.ofMillis(500),java.time.Duration.ofSeconds(30)).contains(deadlineBudget))
                throw new IllegalArgumentException("FEED_REPLAY_BUDGET");
            this.deadlineBudget=deadlineBudget;
        }
    }
    public static Map<String,Object> run(Path bundle,Path trustedSchema,String manifestDigest,Path output) throws IOException {
        return run(bundle,trustedSchema,manifestDigest,output,null);
    }
    public static Map<String,Object> run(Path bundle,Path trustedSchema,String manifestDigest,Path output,RecapOptions recap) throws IOException {
        return run(bundle,trustedSchema,manifestDigest,output,recap,null);
    }
    public static Map<String,Object> run(Path bundle,Path trustedSchema,String manifestDigest,Path output,RecapOptions recap,FeedOptions feed) throws IOException {
        Path source=bundle.toRealPath();
        Path destination=output.toAbsolutePath().normalize();
        Path realDestination=destination.getParent().toRealPath().resolve(destination.getFileName());
        if(realDestination.startsWith(source))throw new IllegalArgumentException("REPLAY_OUTPUT_INSIDE_SOURCE");
        var admitted=new SimulationBundleReader().read(bundle,trustedSchema,manifestDigest);
        var artifacts=admitted.artifacts();
        // Admission's captured schema bytes avoid a second-read schema substitution.
        JsonNode schema=SimulationBundleReader.parse(artifacts.get("demo-simulation-v1.schema.json"));
        return replay(schema,admitted.datasetId(),manifestDigest,SimulationBundleReader.parse(artifacts.get("students.json")),
            lines(artifacts.get("events.ndjson")),output,recap,feed,
            SimulationBundleReader.parse(artifacts.get("id-map.json")));
    }

    static List<byte[]> lines(byte[] ndjson) {
        List<byte[]> lines=new ArrayList<>();int start=0;
        for(int i=0;i<ndjson.length;i++)if(ndjson[i]=='\n') { lines.add(Arrays.copyOfRange(ndjson,start,i));start=i+1; }
        if(start<ndjson.length)lines.add(Arrays.copyOfRange(ndjson,start,ndjson.length));
        return lines;
    }

    static Map<String,Object> replay(JsonNode schema,String dataset,String manifestDigest,JsonNode people,List<byte[]> lines,Path output) throws IOException {
        return replay(schema,dataset,manifestDigest,people,lines,output,null);
    }
    static Map<String,Object> replay(JsonNode schema,String dataset,String manifestDigest,JsonNode people,List<byte[]> lines,Path output,RecapOptions recap) throws IOException {
        return replay(schema,dataset,manifestDigest,people,lines,output,recap,null);
    }
    static Map<String,Object> replay(JsonNode schema,String dataset,String manifestDigest,JsonNode people,List<byte[]> lines,Path output,RecapOptions recap,FeedOptions feed) throws IOException {
        return replay(schema,dataset,manifestDigest,people,lines,output,recap,feed,null);
    }
    static Map<String,Object> replay(JsonNode schema,String dataset,String manifestDigest,JsonNode people,List<byte[]> lines,Path output,RecapOptions recap,FeedOptions feed,JsonNode sourceIdentityMap) throws IOException {
        // Event support, causal admission and output path preflight precede any DB startup.
        SimulationBundleReader.validate(schema.get("$defs").get("students"),people,"students");
        if(!dataset.matches("sha256:[0-9a-f]{64}") || !manifestDigest.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("REPLAY_DIGEST_BINDING");
        List<Input> inputs=new ArrayList<>();List<JsonNode> events=new ArrayList<>();Set<String> paths=new HashSet<>(), refs=new HashSet<>();
        for(byte[] source:lines) {
            byte[] bytes=source.clone();JsonNode event=SimulationBundleReader.parse(bytes);
            SimulationBundleReader.validate(schema.get("$defs").get("event"),event,"event");
            String kind=event.get("kind").asString();
            if(!SimulationCommandDispatcher.supports(kind))throw new IllegalArgumentException("COMMAND_NOT_IMPLEMENTED:"+kind);
            boolean closure=kind.equals("CLOSE_WEEK") || kind.equals("CLOSE_MONTH");
            if(closure && recap==null)throw new IllegalArgumentException("RECAP_CONFIGURATION_REQUIRED");
            JsonNode command=event.get("command");
            if(kind.equals("CREATE") && !command.get("photoId").isNull())throw new IllegalArgumentException("PHOTO_REPLAY_NOT_IMPLEMENTED");
            String request=!closure && command.has("requestRef")?command.get("requestRef").asString():"raw/requests/event-"+event.get("sequence").longValue()+".json";
            LinkedHashSet<String> responses=new LinkedHashSet<>();responses.add(event.get("outcome").get("resultRef").asString());
            for(String key:List.of("responseRef","observationRef"))if(!closure && command.has(key))responses.add(command.get(key).asString());
            List<String> claims=new ArrayList<>(responses);claims.add(request);
            if(closure) {
                for(String key:List.of("snapshotRef","requestRef","responseRef","storedStateRef"))claims.add(command.get(key).asString());
                claims.add("raw/recap-http/event-"+event.get("sequence").asLong()+".json");
                claims.add("raw/recap-result/event-"+event.get("sequence").asLong()+".json");
                for(String part:List.of("source","input","verification"))
                    claims.add("raw/recap-period/event-"+event.get("sequence").asLong()+"-period-"+part+".json");
            }
            if(feed!=null && kind.equals("FEED_QUERY"))
                claims.addAll(SimulationFeedReplayArtifacts.paths(event));
            for(String path:claims) {
                SimulationReplayJournal.validRawPath(path);
                if(!paths.add(path.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("REPLAY_RAW_PATH_COLLISION");
            }
            for(JsonNode ref:event.get("artifactRefs"))refs.add(ref.asString());
            events.add(event);inputs.add(new Input(bytes,event,request,List.copyOf(responses)));
        }
        // A file path may not be another artifact's parent, even on a case-insensitive host.
        for(String path:paths)for(int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1))
            if(paths.contains(path.substring(0,slash)))throw new IllegalArgumentException("REPLAY_RAW_PATH_COLLISION");
        new SimulationEventTimeline().verify(events,people,refs);
        if(inputs.isEmpty())throw new IllegalArgumentException("REPLAY_REQUIRES_EVENTS");
        var recordedCardIds=sourceIdentityMap==null?null:SimulationSharedCardIds.recorded(schema,dataset,sourceIdentityMap,events);
        var journal=new SimulationReplayJournal(output,dataset);
        var observation=new LinkedHashMap<String,Object>();
        observation.put("schemaVersion",1);observation.put("schemaKind","simulation-replay-observation");
        observation.put("datasetId",dataset);observation.put("manifestDigest",manifestDigest);
        observation.put("requestedEvents",inputs.size());observation.put("completedEvents",0);
        observation.put("sharedCardIdentityAllocation",recordedCardIds==null?"FRESH_RANDOM":"RECORDED_SOURCE_IDS");
        observation.put("recordedSharedCardIdentities",recordedCardIds==null?0:recordedCardIds.size());
        observation.put("sourceIdentityMapCanonicalDigest",sourceIdentityMap==null?null:SimulationBundleReader.digest(
            SimulationBundleReader.canonical(sourceIdentityMap).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        observation.put("status","FAILED");observation.put("localDisposableDatabaseOnly",true);
        observation.put("fullDatasetValidationPerformed",false);observation.put("readyForApplication",false);
        observation.put("responseNormalizationPerformed",false);
        observation.put("backendLogicalProjectionExported",false);
        observation.put("typedIdentityMapExported",false);
        observation.put("relationalIdentityNormalizationPerformed",false);
        observation.put("idempotencyReconciliationPerformed",false);
        observation.put("observationStateExported",false);observation.put("observationReconciliationPerformed",false);
        observation.put("allocationStateExported",false);observation.put("allocationReconciliationPerformed",false);
        observation.put("cashStateExported",false);observation.put("cashReconciliationPerformed",false);
        observation.put("allStudentsJoined",false);
        observation.put("monthlyBudgetReconciliationPerformed",false);
        observation.put("relationalReferencesVerified",false);
        observation.put("behaviorReconciliationPerformed",false);
        observation.put("behaviorAccessReconciliationPerformed",false);
        observation.put("influenceAnnotationsVerified",false);
        observation.put("dormancyAnnotationsVerified",false);
        observation.put("pythonInvoked",false);observation.put("relationalStateExported",false);
        observation.put("rawRequestFormat","original logical command envelope bytes, excluding NDJSON LF delimiter");
        int completed=0;Map<String,UUID> identities=Map.of();
        var recapExchanges=JSON.createArrayNode();
        Path recapRoot=recap==null?null:Files.createDirectory(journal.root().resolve("recap-execution"));
        observation.put("recapExchangesVerified",0);
        observation.put("absentRecapResponses",new ArrayList<String>());
        observation.put("feedCaptureEnabled",feed!=null);
        observation.put("feedDeadlineBudgetMillis",feed==null?null:feed.deadlineBudget.toMillis());
        observation.put("servingLatencyPolicyValidated",false);
        observation.put("feedHttpAttempts",0);
        observation.put("feedHttpResponses",0);
        observation.put("feedPagesCaptured",0);
        observation.put("feedExchangeNormalizationPerformed",false);
        Path feedRoot=feed==null?null:Files.createDirectory(journal.root().resolve("feed-execution"));
        try(var feedSession=feed==null?null:new com.crabit.backend.recommendation.SimulationFeedSession(feed.endpoint,feed.credential,feedRoot,feed.deadlineBudget);
            var dispatcher=new SimulationCommandDispatcher(schema,dataset,manifestDigest,people,feedSession,recordedCardIds)) {
            try {
                if(recap!=null)dispatcher.configureRecap(recap.endpoint,recap.credential,recapRoot);
                for(Input input:inputs) {
                    String id=input.event().get("eventId").asString();observation.put("lastAttemptedEventId",id);
                    journal.raw(input.request(),id,"REQUEST",input.bytes());
                    SimulationCommandDispatcher.Result result;
                    try { result=dispatcher.execute(input.event()); }
                    catch(RuntimeException failure) {
                        var mismatch=dispatcher.mismatchedResult();
                        if(mismatch.isPresent() && mismatch.get().eventId().equals(id))
                            for(String path:input.responses())journal.raw(path,id,"RESPONSE",mismatch.get().rawResult());
                        throw failure;
                    } finally {
                        if(feed!=null && input.event().get("kind").asString().equals("FEED_QUERY"))
                            SimulationFeedReplayArtifacts.capture(journal,input.event(),feedRoot,observation);
                        if(input.event().get("kind").asString().startsWith("CLOSE_"))
                            SimulationRecapReplayArtifacts.capture(journal,input.event(),recapRoot,dispatcher.identities(),recapExchanges,observation);
                    }
                    for(String path:input.responses())journal.raw(path,id,"RESPONSE",result.rawResult());
                    completed++;observation.put("completedEvents",completed);
                }
                complete(schema,dataset,manifestDigest,people,events,journal,observation,dispatcher,feedRoot,recapExchanges);
            } finally { identities=dispatcher.identities(); }
        } catch(RuntimeException | IOException | Error failure) {
            observation.put("status","FAILED");
            // Keep diagnostics bounded: exception messages may contain input or runtime secrets.
            observation.put("failureClass",failure.getClass().getSimpleName());
            throw failure;
        } finally { journal.finish(schema,observation,identities); }
        return Collections.unmodifiableMap(observation);
    }

    static void complete(JsonNode schema,String dataset,String manifestDigest,JsonNode people,List<JsonNode> events,
                         SimulationReplayJournal journal,Map<String,Object> observation,SimulationCommandDispatcher dispatcher,
                         Path feedRoot,tools.jackson.databind.node.ArrayNode recapExchanges) throws IOException {
                journal.normalizedRecaps(recapExchanges);
                observation.put("normalizedRecapDigest",SimulationBackendProjection.digest(recapExchanges));
                var preservationBefore=dispatcher.preservationFingerprint();
                observation.put("validationPreservationBefore",preservationBefore);
                observation.put("validationDatabaseUnchanged",false);
                JsonNode cashState=dispatcher.cashState();
                // Preserve DB evidence before comparison, even if reconciliation rejects it.
                journal.cashState(cashState);observation.put("cashStateExported",true);
                observation.put("cashStateDigest",SimulationBundleReader.digest(SimulationBundleReader.canonical(cashState).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var cash=dispatcher.verifyCashState(cashState);
                observation.put("cashReconciliationPerformed",true);
                observation.put("cashAccounts",cash.balances().size());
                observation.put("monthlyBudgetVerification",dispatcher.verifyMonthlyBudgets(cashState));
                observation.put("monthlyBudgetReconciliationPerformed",true);
                observation.put("allStudentsJoined",cash.balances().size()==people.size());
                observation.put("cashCommands",Map.of("applied",cash.applied(),"rejected",cash.rejected(),"failed",cash.failed()));
                observation.put("cashBalanceAfterSource","SQL cumulative sum of committed ledger in account sequence order");
                var allocationState=dispatcher.allocationState();
                JsonNode allocationJson=JSON.valueToTree(allocationState);
                journal.allocationState(allocationJson);observation.put("allocationStateExported",true);
                observation.put("allocationStateDigest",SimulationBundleReader.digest(SimulationBundleReader.canonical(allocationJson).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                observation.put("allocationVerification",dispatcher.verifyAllocationState(allocationState));
                observation.put("allocationReconciliationPerformed",true);
                var observationState=dispatcher.observationState();
                JsonNode observationJson=JSON.valueToTree(observationState);
                journal.observationState(observationJson);observation.put("observationStateExported",true);
                observation.put("observationStateDigest",SimulationBundleReader.digest(SimulationBundleReader.canonical(observationJson).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                observation.put("observationVerification",dispatcher.verifyObservationState(observationState,allocationState));
                observation.put("observationReconciliationPerformed",true);
                var relational=dispatcher.relationalState();
                journal.relationalState(relational);observation.put("relationalStateExported",true);
                observation.put("relationalStateDigest",SimulationBundleReader.digest(SimulationBundleReader.canonical(JSON.valueToTree(relational.state())).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                observation.put("relationalVerification",dispatcher.verifyRelationalState(relational));
                observation.put("relationalReferencesVerified",true);
                observation.put("relationalDomainVerification",dispatcher.verifyRelationalDomain(relational));
                observation.put("relationalTemporalAndHistoryVerified",true);
                observation.put("checkpointVerification",SimulationCheckpointVerifier.verify(relational));
                observation.put("historicalCheckpointsVerified",true);
                observation.put("adjustmentVerification",SimulationAdjustmentVerifier.verify(relational));
                observation.put("adjustmentEpisodesVerified",true);
                observation.put("behaviorVerification",dispatcher.verifyBehaviorState(relational));
                observation.put("behaviorReconciliationPerformed",true);
                observation.put("behaviorAccessReconciliationPerformed",true);
                observation.put("influenceAnnotationCount",SimulationInfluenceVerifier.verify(events,dispatcher.results()));
                observation.put("influenceAnnotationsVerified",true);
                observation.put("dormancyAnnotationCount",SimulationDormancyVerifier.verify(events,dispatcher.results()));
                observation.put("dormancyAnnotationsVerified",true);
                JsonNode identityMap=SimulationReplayIdentityMap.capture(relational,dispatcher.identities(),schema);
                journal.identityMap(identityMap);
                observation.put("typedIdentityMapExported",true);
                observation.put("typedIdentityMappings",identityMap.get("entries").size());
                observation.put("identityMapDigest",SimulationBundleReader.digest(SimulationBundleReader.canonical(identityMap).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                observation.put("logicalIdentityDigest",SimulationBundleReader.digest(SimulationBundleReader.canonical(SimulationReplayIdentityMap.logicalProjection(identityMap)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var fingerprints=dispatcher.verifyIdempotencyState(relational);
                observation.put("idempotencyRecordsVerified",fingerprints.records());
                observation.put("idempotencyReconciliationPerformed",true);
                var feedVerified=SimulationFeedNormalization.verify(events,feedRoot,relational,dispatcher.identities());
                journal.normalizedFeed(feedVerified.exchanges());
                observation.put("feedExchangesVerified",feedVerified.exchanges().size());
                observation.put("feedExchangeNormalizationPerformed",feedVerified.exchanges().size()>0);
                observation.put("normalizedFeedDigest",SimulationBundleReader.digest(com.crabit.backend.recap.SimulationFeedCanonicalJson.encode(feedVerified.exchanges())));
                JsonNode normalizedState=SimulationRelationalNormalizer.normalize(relational,identityMap,fingerprints,feedVerified.recommendationIds());
                journal.normalizedRelational(normalizedState);
                observation.put("relationalIdentityNormalizationPerformed",true);
                observation.put("allRelationalRuntimeValuesNormalized",normalizedState.get("allRuntimeValuesNormalized").booleanValue());
                observation.put("normalizedRelationalDigest",SimulationBundleReader.digest(com.crabit.backend.recap.SimulationFeedCanonicalJson.encode(normalizedState)));
                JsonNode normalized=dispatcher.normalizedResponses();
                journal.normalized(normalized);
                observation.put("normalizedResponseDigest",SimulationBundleReader.digest(SimulationBundleReader.canonical(normalized).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                observation.put("responseNormalizationPerformed",true);
                JsonNode backendProjection=SimulationBackendProjection.capture(schema,dataset,manifestDigest,people,events,
                    identityMap,cashState,normalizedState,normalized);
                journal.backendProjection(backendProjection);
                observation.put("backendLogicalProjectionExported",true);
                observation.put("backendLogicalDigest",SimulationBackendProjection.digest(backendProjection));
                var preservationAfter=dispatcher.preservationFingerprint();
                observation.put("validationPreservationAfter",preservationAfter);
                var preservation=SimulationPreservationFingerprint.compare(preservationBefore,preservationAfter);
                observation.put("validationPreservationDifference",preservation);
                if (!preservation.unchanged()) throw new IllegalStateException("REPLAY_VALIDATION_DATABASE_DRIFT");
                observation.put("validationDatabaseUnchanged",true);
                observation.put("status","REPLAYED_PARTIAL_VALIDATION");
    }

    public static void main(String[] args) {
        if(args.length!=4) { System.err.println("Usage: simulationRun --args='<bundle> <trusted-schema> <manifest-sha256> <new-output-directory>'");System.exit(2);return; }
        try {
            String endpoint=System.getenv("CRABIT_SIMULATION_RECAP_URL");
            RecapOptions recap=endpoint==null?null:new RecapOptions(java.net.URI.create(endpoint),System.getenv("CRABIT_SIMULATION_RECAP_TOKEN"));
            String feedEndpoint=System.getenv("CRABIT_SIMULATION_FEED_URL");
            String replayBudget=System.getenv().getOrDefault("CRABIT_SIMULATION_FEED_BUDGET_MS","500");
            if(!Set.of("500","30000").contains(replayBudget))throw new IllegalArgumentException("FEED_REPLAY_BUDGET");
            FeedOptions feed=feedEndpoint==null?null:new FeedOptions(java.net.URI.create(feedEndpoint),System.getenv("CRABIT_SIMULATION_FEED_TOKEN"),
                java.time.Duration.ofMillis(Long.parseLong(replayBudget)));
            System.out.println(JSON.writeValueAsString(run(Path.of(args[0]),Path.of(args[1]),args[2],Path.of(args[3]),recap,feed)));
        }
        catch(Exception failure) {
            System.out.println(JSON.writeValueAsString(Map.of("schemaVersion",1,"schemaKind","simulation-replay-observation",
                "status","FAILED","readyForApplication",false,"code",failure.getClass().getSimpleName())));System.exit(4);
        }
    }
}
