package com.crabit.backend.simulation;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Indexes original transport bytes separately from logical commands and backend pages. */
final class SimulationFeedReplayArtifacts {
    private static final List<String> PARTS=List.of("request","response","http","page","input-source","input-verification","page-source","page-verification","composition-verification","ranking-verification");
    private SimulationFeedReplayArtifacts() {}
    static List<String> paths(JsonNode event) {
        String prefix="raw/feed/event-"+event.get("sequence").asLong()+"-";
        return PARTS.stream().map(part->prefix+part+".json").toList();
    }
    static void capture(SimulationReplayJournal journal,JsonNode event,Path source,Map<String,Object> observation) throws IOException {
        Path folder=source.resolve("event-"+event.get("sequence").asLong());
        if(!Files.exists(folder,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(folder) || !Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("FEED_CAPTURE_DIRECTORY_CHANGED");
        String id=event.get("eventId").asString();
        List<String> targets=paths(event);
        // No decoding/re-serialization of Python bytes, including invalid or failed responses.
        for(int i=0;i<PARTS.size();i++) {
            String part=PARTS.get(i);Path file=folder.resolve(part+".json");
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))continue;
            if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                || Files.size(file)>SimulationBundleReader.MAX_ARTIFACT_BYTES)throw new IOException("FEED_CAPTURE_FILE_CHANGED");
            String kind=switch(part){case "request"->"REQUEST";case "response"->"RESPONSE";default->"RUNTIME_OBSERVATION";};
            journal.rawFile(targets.get(i),id,kind,file,part.equals("page")?"BACKEND":"FEED",null,
                part.equals("response")?"application/octet-stream":"application/json");
            switch(part) {
                case "request" -> {increment(observation,"feedHttpAttempts");observation.put("pythonInvoked",true);}
                case "response" -> increment(observation,"feedHttpResponses");
                case "page" -> increment(observation,"feedPagesCaptured");
                default -> { }
            }
        }
    }
    private static void increment(Map<String,Object> observation,String key) {
        observation.put(key,((Number)observation.getOrDefault(key,0)).intValue()+1);
    }
}
