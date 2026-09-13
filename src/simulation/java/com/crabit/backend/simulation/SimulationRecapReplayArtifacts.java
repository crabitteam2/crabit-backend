package com.crabit.backend.simulation;

import com.crabit.backend.recap.SimulationRecapNormalization;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/** Copies actual immutable execution evidence; absent HTTP exchanges stay absent. */
final class SimulationRecapReplayArtifacts {
    private SimulationRecapReplayArtifacts() {}
    @SuppressWarnings("unchecked")
    static void capture(SimulationReplayJournal journal,JsonNode event,Path source,Map<String,UUID> identities,
                        ArrayNode exchanges,Map<String,Object> observation) throws IOException {
        Path folder=source.resolve("event-"+event.get("sequence").asLong());
        String periodPrefix="event-"+event.get("sequence").asLong()+"-period-";
        for(String part:List.of("source","input","verification")) {
            Path file=source.resolve(periodPrefix+part+".json");
            if(Files.exists(file))journal.rawFile("raw/recap-period/"+periodPrefix+part+".json",
                event.get("eventId").asString(),"RUNTIME_OBSERVATION",file);
        }
        if(Files.exists(source.resolve(periodPrefix+"verification.json")))
            observation.put("recapPeriodsVerified",((Number)observation.getOrDefault("recapPeriodsVerified",0)).intValue()+1);
        if(!Files.exists(folder))return; // Rejected authorization or independent verification failed before HTTP.
        JsonNode command=event.get("command");String id=event.get("eventId").asString();
        byte[] request=Files.readAllBytes(folder.resolve("request.json"));
        JsonNode frozen=SimulationBundleReader.parse(request);
        String model=frozen.get("algorithm_version").asString();
        boolean invoked=Files.exists(folder.resolve("http.json"));
        journal.rawFile(command.get("requestRef").asString(),id,invoked?"REQUEST":"STORED_DOCUMENT",folder.resolve("request.json"),invoked?"RECAP":"BACKEND",invoked?model:null);
        // Snapshot is a materialized copy of the frozen input object, not an HTTP request.
        journal.raw(command.get("snapshotRef").asString(),id,"STORED_DOCUMENT",
            com.crabit.backend.recap.SimulationFeedCanonicalJson.encode(frozen.get("input")));
        byte[] response=Files.exists(folder.resolve("response.json"))?Files.readAllBytes(folder.resolve("response.json")):null;
        if(response!=null)journal.rawFile(command.get("responseRef").asString(),id,"RESPONSE",folder.resolve("response.json"),"RECAP",model);
        else ((List<String>)observation.get("absentRecapResponses")).add(command.get("responseRef").asString());
        if(invoked) {
            journal.rawFile("raw/recap-http/event-"+event.get("sequence").asLong()+".json",id,"RUNTIME_OBSERVATION",folder.resolve("http.json"),"RECAP",model);
            observation.put("pythonInvoked",true);
        }
        if(Files.exists(folder.resolve("result-verification.json")))
            journal.rawFile("raw/recap-result/event-"+event.get("sequence").asLong()+".json",id,"RUNTIME_OBSERVATION",
                folder.resolve("result-verification.json"));
        if(!Files.exists(folder.resolve("stored-state.json")))return; // Failure bytes already preserved above.
        byte[] stored=Files.readAllBytes(folder.resolve("stored-state.json"));
        journal.rawFile(command.get("storedStateRef").asString(),id,"STORED_DOCUMENT",folder.resolve("stored-state.json"),"POSTGRESQL",null);
        JsonNode row=SimulationBundleReader.parse(stored);
        if(!Set.of("SUCCEEDED","NOT_ELIGIBLE").contains(row.get("state").asString()))return;
        exchanges.add(new SimulationRecapNormalization(identities).exchange(request,response,row));
        observation.put("recapExchangesVerified",exchanges.size());
    }
}
