package com.crabit.backend.simulation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Persistent local policy discovery. Fixed-event replay remains the reproducibility authority. */
public final class SimulationReplaySession implements AutoCloseable {
    private static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    private final JsonNode schema,people;
    private final String dataset,inputDigest;
    private final SimulationReplayJournal journal;
    private final SimulationCommandDispatcher dispatcher;
    private final com.crabit.backend.recommendation.SimulationFeedSession feed;
    private final Path feedRoot,recapRoot;
    private final List<JsonNode> events=new ArrayList<>();
    private final Set<String> paths=new HashSet<>();
    private final Map<String,Object> observation=new LinkedHashMap<>();
    private final tools.jackson.databind.node.ArrayNode recaps=JSON.createArrayNode();
    private final OutputStream eventFile;
    private boolean finished,failed,closed;

    public SimulationReplaySession(JsonNode schema,String dataset,String inputDigest,JsonNode people,Path output,
            SimulationReplayRun.RecapOptions recap,SimulationReplayRun.FeedOptions feedOptions) throws IOException {
        this.schema=schema.deepCopy();this.people=people.deepCopy();this.dataset=dataset;this.inputDigest=inputDigest;
        SimulationBundleReader.validate(schema.get("$defs").get("students"),people,"students");
        if(!dataset.matches("sha256:[0-9a-f]{64}") || !inputDigest.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("SESSION_INPUT_DIGEST");
        journal=new SimulationReplayJournal(output,dataset);
        recapRoot=recap==null?null:Files.createDirectory(journal.root().resolve("recap-execution"));
        feedRoot=feedOptions==null?null:Files.createDirectory(journal.root().resolve("feed-execution"));
        feed=feedOptions==null?null:new com.crabit.backend.recommendation.SimulationFeedSession(feedOptions.endpoint,feedOptions.credential,feedRoot);
        SimulationCommandDispatcher created=null;
        try {
            created=new SimulationCommandDispatcher(schema,dataset,inputDigest,people,feed);
            if(recap!=null)created.configureRecap(recap.endpoint,recap.credential,recapRoot);
            eventFile=Files.newOutputStream(journal.root().resolve("events.ndjson"),StandardOpenOption.CREATE_NEW);
            dispatcher=created;
        } catch(IOException|RuntimeException|Error e) {if(created!=null)created.close();if(feed!=null)feed.close();throw e;}
        observation.put("schemaVersion",1);observation.put("schemaKind","simulation-replay-observation");
        observation.put("datasetId",dataset);observation.put("inputDigest",inputDigest);
        observation.put("inputBindingKind","POLICY_DISCOVERY");
        observation.put("status","INCOMPLETE");observation.put("completedEvents",0);
        observation.put("fullDatasetValidationPerformed",false);observation.put("readyForApplication",false);
        observation.put("localDisposableDatabaseOnly",true);observation.put("pythonInvoked",false);
        observation.put("absentRecapResponses",new ArrayList<String>());
        observation.put("recapExchangesVerified",0);observation.put("feedCaptureEnabled",feed!=null);
    }
    public synchronized JsonNode step(byte[] intentBytes) throws IOException {
        if(finished||failed||closed)throw new IllegalStateException("SESSION_NOT_ACTIVE");
        if(intentBytes.length>1024*1024)throw new IllegalArgumentException("SESSION_EVENT_SIZE");
        JsonNode intent=SimulationBundleReader.parse(intentBytes);
        SimulationBundleReader.validate(schema.get("$defs").get("event"),intent,"event");
        String kind=intent.get("kind").asString(),id=intent.get("eventId").asString();
        boolean closure=kind.startsWith("CLOSE_");
        if(closure && recapRoot==null)throw new IllegalArgumentException("RECAP_CONFIGURATION_REQUIRED");
        JsonNode command=intent.get("command");
        String request=!closure && command.has("requestRef")?command.get("requestRef").asString():"raw/requests/event-"+intent.get("sequence").asLong()+".json";
        var responses=new LinkedHashSet<String>();responses.add(intent.get("outcome").get("resultRef").asString());
        for(String key:List.of("responseRef","observationRef"))if(!closure && command.has(key))responses.add(command.get(key).asString());
        var claims=new ArrayList<>(responses);claims.add(request);
        if(closure) {
            for(String key:List.of("snapshotRef","requestRef","responseRef","storedStateRef"))claims.add(command.get(key).asString());
            claims.add("raw/recap-http/event-"+intent.get("sequence").asLong()+".json");
            claims.add("raw/recap-result/event-"+intent.get("sequence").asLong()+".json");
            for(String part:List.of("source","input","verification"))claims.add("raw/recap-period/event-"+intent.get("sequence").asLong()+"-period-"+part+".json");
        }
        if(feed!=null && kind.equals("FEED_QUERY"))claims.addAll(SimulationFeedReplayArtifacts.paths(intent));
        var prospective=new HashSet<>(paths);
        for(String path:claims) {
            SimulationReplayJournal.validRawPath(path);
            if(!prospective.add(path.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("REPLAY_RAW_PATH_COLLISION");
        }
        for(String path:prospective)for(int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1))
            if(prospective.contains(path.substring(0,slash)))throw new IllegalArgumentException("REPLAY_RAW_PATH_COLLISION");
        paths.clear();paths.addAll(prospective);
        try {
            journal.raw(request,id,"REQUEST",intentBytes);
            var before=dispatcher.identities();
            SimulationCommandDispatcher.ObservedStep observed;
            try {observed=dispatcher.observe(intent);}
            finally {
                if(feed!=null && kind.equals("FEED_QUERY"))SimulationFeedReplayArtifacts.capture(journal,intent,feedRoot,observation);
                if(closure)SimulationRecapReplayArtifacts.capture(journal,intent,recapRoot,dispatcher.identities(),recaps,observation);
            }
            for(String response:responses)journal.raw(response,id,"RESPONSE",observed.result().rawResult());
            JsonNode event=observed.event();
            events.add(event);eventFile.write(SimulationBundleReader.canonical(event).getBytes(StandardCharsets.UTF_8));
            eventFile.write('\n');eventFile.flush();
            observation.put("completedEvents",events.size());
            var added=new TreeMap<>(dispatcher.identities());added.keySet().removeAll(before.keySet());
            return JSON.valueToTree(Map.of("status","STEPPED","event",event,"result",SimulationBundleReader.parse(observed.result().rawResult()),
                "newIdentities",added,"completedEvents",events.size()));
        } catch(IOException|RuntimeException|Error e) {failed=true;observation.put("status","FAILED");observation.put("failureClass",e.getClass().getSimpleName());throw e;}
    }
    public synchronized Map<String,Object> finish() throws IOException {
        if(finished||failed||closed || events.isEmpty())throw new IllegalStateException("SESSION_NOT_FINISHABLE");
        try {
            var refs=new HashSet<String>();for(JsonNode e:events)for(JsonNode ref:e.get("artifactRefs"))refs.add(ref.asString());
            new SimulationEventTimeline().verify(events,people,refs);
            SimulationReplayRun.complete(schema,dataset,inputDigest,people,events,journal,observation,dispatcher,feedRoot,recaps);
            observation.put("status","DISCOVERY_COMPLETED");observation.put("fixedReplayRequired",true);
            journal.finish(schema,observation,dispatcher.identities());finished=true;
            return Collections.unmodifiableMap(new LinkedHashMap<>(observation));
        } catch(IOException|RuntimeException|Error e) {failed=true;observation.put("status","FAILED");throw e;}
    }
    @Override public synchronized void close() throws IOException {
        if(closed)return;closed=true;
        try {if(!finished)journal.finish(schema,observation,dispatcher.identities());}
        finally {try {eventFile.close();}finally {try {dispatcher.close();}finally {if(feed!=null)feed.close();}}}
    }
    public static void main(String[] args) {
        PrintStream protocol=System.out;System.setOut(System.err);
        int exit=0;
        try {
            if(args.length!=3)throw new IllegalArgumentException("Usage: simulationSession <trusted-schema> <policy-session.json> <new-output-directory>");
            JsonNode schema=SimulationBundleReader.parse(Files.readAllBytes(Path.of(args[0])));
            JsonNode config=SimulationBundleReader.parse(Files.readAllBytes(Path.of(args[1])));
            if(!config.isObject() || !new HashSet<>(config.propertyNames()).equals(Set.of("schemaVersion","datasetId","inputDigest","students"))
                || !config.path("schemaVersion").isIntegralNumber() || config.path("schemaVersion").asInt()!=1)
                throw new IllegalArgumentException("SESSION_CONFIG");
            String recapUrl=System.getenv("CRABIT_SIMULATION_RECAP_URL"),feedUrl=System.getenv("CRABIT_SIMULATION_FEED_URL");
            var recap=recapUrl==null?null:new SimulationReplayRun.RecapOptions(java.net.URI.create(recapUrl),System.getenv("CRABIT_SIMULATION_RECAP_TOKEN"));
            var feed=feedUrl==null?null:new SimulationReplayRun.FeedOptions(java.net.URI.create(feedUrl),System.getenv("CRABIT_SIMULATION_FEED_TOKEN"));
            try(var session=new SimulationReplaySession(schema,config.path("datasetId").asString(),config.path("inputDigest").asString(),config.get("students"),Path.of(args[2]),recap,feed)) {
                emit(protocol,Map.of("status","READY","schemaVersion",1));
                for(;;) {
                    byte[] line=readLine(System.in);if(line==null)throw new EOFException("SESSION_FINISH_REQUIRED");
                    JsonNode request=SimulationBundleReader.parse(line);
                    String op=request.path("operation").asString();
                    if(op.equals("STEP") && new HashSet<>(request.propertyNames()).equals(Set.of("operation","event")))
                        emit(protocol,session.step(JSON.writeValueAsBytes(request.get("event"))));
                    else if(op.equals("FINISH") && new HashSet<>(request.propertyNames()).equals(Set.of("operation"))) {
                        emit(protocol,session.finish());break;
                    } else throw new IllegalArgumentException("SESSION_OPERATION");
                }
            }
        } catch(Exception e) {emit(protocol,Map.of("status","FAILED","code",e.getClass().getSimpleName(),"readyForApplication",false));exit=4;}
        if(exit!=0)System.exit(exit);
    }
    private static void emit(PrintStream stream,Object response) {stream.println("CRABIT_SIMULATION_V1 "+JSON.writeValueAsString(response));stream.flush();}
    private static byte[] readLine(InputStream stream) throws IOException {
        var out=new ByteArrayOutputStream();int value;
        while((value=stream.read())!=-1 && value!='\n') {if(out.size()>=1024*1024)throw new IOException("SESSION_LINE_LIMIT");out.write(value);}
        return value==-1 && out.size()==0?null:out.toByteArray();
    }
}
