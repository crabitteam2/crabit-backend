package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationFeedContinuationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    ObjectNode event(String id,long sequence,String cursor) {
        var e=JSON.createObjectNode().put("eventId",id).put("sequence",sequence).put("kind","FEED_QUERY").put("actorStudentId","student");
        e.putArray("causes").add("page:first");e.putObject("outcome").put("status","APPLIED");
        e.putObject("command").put("academyId","academy").put("cursor",cursor);return e;
    }
    @Test void usesActualEarlierResponseWithoutChangingTheCommandOrSignedBytes() {
        var prior=event("page:first",1,null);var current=event("next",2,"event:page:first:nextCursor");var before=current.deepCopy();
        var actual=new SimulationCommandDispatcher.Result("page:first","APPLIED",null,JSON.writeValueAsBytes(Map.of("nextCursor","actual.signed.cursor")));
        assertThat(SimulationFeedContinuation.resolve(current,Map.of("page:first",prior),Map.of("page:first",actual))).isEqualTo("actual.signed.cursor");
        assertThat(current).isEqualTo(before);
        assertThat(SimulationFeedContinuation.resolve(event("literal",2,"bad-token"),Map.of(),Map.of())).isEqualTo("bad-token");
        assertThat(SimulationFeedContinuation.resolve(event("initial",2,null),Map.of(),Map.of())).isNull();
    }
    @TestFactory List<DynamicTest> rejectsUnboundOrWrongScopeReferencesBeforeServiceExecution() {
        Map<String,Consumer<ObjectNode>> changes=new LinkedHashMap<>();
        changes.put("SYNTAX",e->((ObjectNode)e.get("command")).put("cursor","event:page:first:other"));
        changes.put("NOT_EARLIER",e->e.put("sequence",1));
        changes.put("CAUSE_REQUIRED",e->e.putArray("causes"));
        changes.put("SCOPE",e->e.put("actorStudentId","other"));
        changes.put("SCOPE_ACADEMY",e->((ObjectNode)e.get("command")).put("academyId","other"));
        return changes.entrySet().stream().map(entry->DynamicTest.dynamicTest(entry.getKey(),()->{
            var current=event("next",2,"event:page:first:nextCursor");entry.getValue().accept(current);
            assertThatThrownBy(()->SimulationFeedContinuation.source(current,Map.of("page:first",event("page:first",1,null))))
                .hasMessageContaining("FEED_CONTINUATION_"+entry.getKey().replace("_ACADEMY",""));
        })).toList();
    }
    @Test void rejectsMissingWrongKindRejectedSourceAndAbsentActualNextPage() {
        var current=event("next",2,"event:page:first:nextCursor");var prior=event("page:first",1,null);
        assertThatThrownBy(()->SimulationFeedContinuation.source(current,Map.of())).hasMessageContaining("NOT_EARLIER");
        prior.put("kind","CLICK");assertThatThrownBy(()->SimulationFeedContinuation.source(current,Map.of("page:first",prior))).hasMessageContaining("SOURCE_NOT_APPLIED_FEED");
        prior.put("kind","FEED_QUERY");((ObjectNode)prior.get("outcome")).put("status","REJECTED");
        assertThatThrownBy(()->SimulationFeedContinuation.source(current,Map.of("page:first",prior))).hasMessageContaining("SOURCE_NOT_APPLIED_FEED");
        ((ObjectNode)prior.get("outcome")).put("status","APPLIED");
        assertThatThrownBy(()->SimulationFeedContinuation.resolve(current,Map.of("page:first",prior),Map.of())).hasMessageContaining("RESULT_NOT_APPLIED");
        var actual=new SimulationCommandDispatcher.Result("page:first","APPLIED",null,"{\"nextCursor\":null}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(()->SimulationFeedContinuation.resolve(current,Map.of("page:first",prior),Map.of("page:first",actual))).hasMessageContaining("NO_NEXT_PAGE");
    }
}
