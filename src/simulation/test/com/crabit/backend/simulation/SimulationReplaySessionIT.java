package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class SimulationReplaySessionIT {
    @TempDir Path temp;
    @Test void nextPolicyStepUsesActualRankingThenFixedCommandsReplayIdenticallyTwice() throws Exception {
        var f=new SimulationFeedReplayIT();f.temp=temp;
        var builder=new SimulationFeedContinuationIT();
        var json=SimulationFeedReplayIT.JSON;
        var events=new ArrayList<byte[]>();
        try(var python=f.python()) {
            var options=new SimulationReplayRun.FeedOptions(python.endpoint(),f.TOKEN);
            Path discovered=temp.resolve("discovered");
            try(var session=new SimulationReplaySession(f.schema(),f.DATASET,f.DATASET,f.people(),discovered,null,options)) {
                for(byte[] raw:f.events(true).subList(0,6))events.add(json.writeValueAsBytes(session.step(raw).get("event")));
                var feed=(ObjectNode)json.readTree(f.events(true).get(6));
                ((ObjectNode)feed.get("command")).putArray("orderedCardIds");
                JsonNode actual=session.step(json.writeValueAsBytes(feed));events.add(json.writeValueAsBytes(actual.get("event")));
                String selected=actual.get("event").get("command").get("orderedCardIds").get(0).asString();
                assertThat(selected).isIn("e4","e6");
                var impression=builder.event(8,"IMPRESSION","student-3-00",6,Map.of("academyId","academy-1","resultContextId","first","cardId",selected,"position",0,"impressionId","seen-actual"));
                impression.putArray("causes").add("e7");
                events.add(json.writeValueAsBytes(session.step(json.writeValueAsBytes(impression)).get("event")));
                assertThat(session.finish().get("status")).isEqualTo("DISCOVERY_COMPLETED");
            }
            for(int run=0;run<2;run++) {
                Path output=temp.resolve("fixed-"+run);
                SimulationReplayRun.replay(f.schema(),f.DATASET,f.DATASET,f.people(),events,output,null,options);
                for(String file:List.of("normalized-feed.json","normalized-backend.json","normalized-relational.json","normalized-responses.json"))
                    assertThat(Files.readAllBytes(output.resolve(file))).as(file).isEqualTo(Files.readAllBytes(discovered.resolve(file)));
                f.verifyIndex(output);f.copy(output,"session-fixed-"+run);
            }
            f.verifyIndex(discovered);f.copy(discovered,"session-discovered");
            assertThat(json.readTree(Files.readAllBytes(discovered.resolve("raw/first-request.json"))).get("command").get("orderedCardIds")).isEmpty();
            assertThat(json.readTree(Files.readAllBytes(discovered.resolve("raw/results/8.json"))).toString()).doesNotContain("REJECTED");
        }
    }
}
