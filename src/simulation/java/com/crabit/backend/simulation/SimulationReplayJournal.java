package com.crabit.backend.simulation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One newly owned output directory. Never updates an existing replay or source bundle. */
final class SimulationReplayJournal {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Path root;
    private final String dataset;
    private final SortedMap<String,Object> records=new TreeMap<>();
    private boolean finished;

    SimulationReplayJournal(Path target,String dataset) throws IOException {
        Path absolute=target.toAbsolutePath().normalize();
        Path parent=absolute.getParent().toRealPath();
        root=parent.resolve(absolute.getFileName());
        Files.createDirectory(root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        this.dataset=dataset;
    }
    Path root() { return root; }
    static void validRawPath(String path) {
        if(path.length()>240 || !path.matches("raw/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*(?:\\.[A-Za-z0-9_-]+)?")
            || path.equalsIgnoreCase("raw/index.json"))throw new IllegalArgumentException("REPLAY_RAW_PATH");
    }
    void raw(String path,String event,String kind,byte[] bytes) throws IOException {
        raw(path,event,kind,bytes,"BACKEND",null);
    }
    void raw(String path,String event,String kind,byte[] bytes,String service,String modelVersion) throws IOException {
        raw(path,event,kind,bytes,service,modelVersion,"application/json");
    }
    void raw(String path,String event,String kind,byte[] bytes,String service,String modelVersion,String contentType) throws IOException {
        raw(path,event,kind,bytes,service,modelVersion,contentType,null);
    }
    void rawFile(String path,String event,String kind,Path source) throws IOException {
        rawFile(path,event,kind,source,"BACKEND",null,"application/json");
    }
    void rawFile(String path,String event,String kind,Path source,String service,String modelVersion) throws IOException {
        rawFile(path,event,kind,source,service,modelVersion,"application/json");
    }
    void rawFile(String path,String event,String kind,Path source,String service,String modelVersion,String contentType) throws IOException {
        Path normalized=source.toAbsolutePath().normalize();
        if(!normalized.startsWith(root) || Files.isSymbolicLink(normalized)
            || !Files.isRegularFile(normalized,LinkOption.NOFOLLOW_LINKS)
            || Files.size(normalized)>SimulationBundleReader.MAX_ARTIFACT_BYTES)
            throw new IOException("REPLAY_SOURCE_FILE");
        Path current=root;
        for(Path part:root.relativize(normalized)) {
            current=current.resolve(part);
            if(Files.isSymbolicLink(current))throw new IOException("REPLAY_SOURCE_FILE");
        }
        raw(path,event,kind,Files.readAllBytes(normalized),service,modelVersion,contentType,normalized);
    }
    private void raw(String path,String event,String kind,byte[] bytes,String service,String modelVersion,String contentType,Path source) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        validRawPath(path);
        if(records.containsKey(path))throw new IllegalStateException("REPLAY_RAW_DUPLICATE");
        if(bytes.length>SimulationBundleReader.MAX_ARTIFACT_BYTES)throw new IllegalArgumentException("REPLAY_RAW_SIZE");
        write(path,bytes,source);
        var record=new LinkedHashMap<String,Object>();
        record.put("path",path);record.put("byteLength",bytes.length);record.put("sha256",SimulationBundleReader.digest(bytes));
        record.put("contentType",contentType);record.put("service",service);record.put("modelVersion",modelVersion);
        record.put("eventId",event);record.put("kind",kind);records.put(path,record);
    }
    void finish(JsonNode trustedSchema,Map<String,Object> observation,Map<String,UUID> identities) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        var index=Map.of("schemaVersion",1,"schemaKind","demo-simulation-raw-index","datasetId",dataset,"records",records.values());
        SimulationBundleReader.validate(trustedSchema.get("$defs").get("rawIndex"),JSON.valueToTree(index),"rawIndex");
        write("raw/index.json",JSON.writeValueAsBytes(index));
        // Diagnostic execution mapping only: full typed id-map/export remains a separate operation.
        write("execution-identities.json",JSON.writeValueAsBytes(new TreeMap<>(identities)));
        write("replay-observation.json",JSON.writeValueAsBytes(observation));
        finished=true;
    }
    void cashState(JsonNode state) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        write("state/export.json",SimulationBundleReader.canonical(state).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    void allocationState(JsonNode state) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        write("state/allocation.json",SimulationBundleReader.canonical(state).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    void observationState(JsonNode state) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        write("state/observations.json",SimulationBundleReader.canonical(state).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    void relationalState(SimulationRelationalState.Export export) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        write("state/relational-catalog.json",SimulationBundleReader.canonical(JSON.valueToTree(export.catalog())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        write("state/relational.json",SimulationBundleReader.canonical(JSON.valueToTree(export.state())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    void identityMap(JsonNode mapping) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        write("id-map.json",SimulationBundleReader.canonical(mapping).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    void normalizedRelational(JsonNode state) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        write("normalized-relational.json",com.crabit.backend.recap.SimulationFeedCanonicalJson.encode(state));
    }
    void normalized(JsonNode projection) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        write("normalized-responses.json",SimulationBundleReader.canonical(projection).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    void backendProjection(JsonNode projection) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        write("normalized-backend.json",com.crabit.backend.recap.SimulationFeedCanonicalJson.encode(projection));
    }
    void normalizedFeed(JsonNode value) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        write("normalized-feed.json",com.crabit.backend.recap.SimulationFeedCanonicalJson.encode(value));
    }
    void normalizedRecaps(JsonNode value) throws IOException {
        if(finished)throw new IllegalStateException("JOURNAL_FINISHED");
        write("normalized-recaps.json",com.crabit.backend.recap.SimulationFeedCanonicalJson.encode(value));
    }
    private void write(String relative,byte[] bytes) throws IOException {
        write(relative,bytes,null);
    }
    private void write(String relative,byte[] bytes,Path source) throws IOException {
        if(Files.isSymbolicLink(root) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("REPLAY_ROOT_CHANGED");
        Path file=root.resolve(relative), parent=file.getParent();
        Path current=root;
        for(Path part:root.relativize(parent)) {
            current=current.resolve(part);
            if(!Files.exists(current,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(current);
            if(Files.isSymbolicLink(current) || !Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS))throw new IOException("REPLAY_PARENT_CHANGED");
        }
        // Capture has sealed this execution file. Share its immutable bytes inside this
        // newly owned output instead of allocating a second multi-gigabyte RAW tree.
        if(source!=null) {
            Files.createLink(file,source);
            if(!Arrays.equals(bytes,Files.readAllBytes(file)))throw new IOException("REPLAY_SOURCE_CHANGED");
            return;
        }
        try(var out=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);
        }
    }
}
