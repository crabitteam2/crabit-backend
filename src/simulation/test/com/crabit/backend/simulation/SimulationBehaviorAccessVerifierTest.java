package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationBehaviorAccessVerifierTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Map<String,UUID> ids=new HashMap<>();
    private final List<JsonNode> commands=new ArrayList<>();
    private final Map<String,List<JsonNode>> tables=new HashMap<>();
    private long seq;

    SimulationBehaviorAccessVerifierTest() {
        tables.put("behavior_result_item",new ArrayList<>());tables.put("behavior_event",new ArrayList<>());
        for(String student:List.of("viewer","owner")) {
            ids.put("STUDENT:"+student,UUID.randomUUID());
            event("JOIN",student,Map.of("academyId","academy"));
        }
        event("CREATE","owner",Map.of("wishId","wish"));
    }
    private ObjectNode event(String kind,String actor,Map<String,?> command) {
        var e=JSON.createObjectNode().put("eventId","e"+(++seq)).put("sequence",seq).put("kind",kind)
            .put("actorStudentId",actor).put("occurredAt",SimulationCashOracle.START.toString());
        e.set("command",JSON.valueToTree(command));e.putObject("outcome").put("status","APPLIED");commands.add(e);return e;
    }
    private String share(String visibility) {
        var e=event("SHARE","owner",Map.of("wishId","wish","visibility",visibility));
        String card=UUID.randomUUID().toString();ids.put("SHARED_CARD:"+e.get("eventId").asString(),UUID.fromString(card));return card;
    }
    private ObjectNode relation(String kind,String actor,String target) {
        return event(kind,actor,Map.of("academyId","academy","viewerStudentId",actor,"ownerStudentId",target));
    }
    private ObjectNode page(String actor,String card) {
        String context="page-"+(seq+1);UUID uuid=UUID.randomUUID();ids.put("FEED_CONTEXT:"+context,uuid);
        var e=event("FEED_QUERY",actor,Map.of("academyId","academy","resultContextId",context));
        if(card!=null)tables.get("behavior_result_item").add(JSON.valueToTree(Map.of("context_id",uuid.toString(),"card_id",card)));
        return e;
    }
    private SimulationBehaviorAccessVerifier.Verification verify() {
        var state=new SimulationRelationalState.State(1,"test","dataset","catalog",tables);
        return SimulationBehaviorAccessVerifier.verify(new SimulationRelationalState.Export(
            new SimulationRelationalState.Catalog(Map.of(),List.of(),List.of()),state),commands,ids);
    }
    @Test void reverseOrFutureFollowCannotAuthorizeEarlierPageEvenAtTheSameInstant() {
        String card=share("FOLLOWERS");relation("FOLLOW","owner","viewer");
        var denied=page("viewer",card);relation("FOLLOW","viewer","owner");
        assertThatThrownBy(this::verify).hasMessage("BEHAVIOR_ACCESS_CARD_DENIED event="+denied.get("eventId").asString());
    }
    @Test void rejectedFollowDoesNotGrantPermission() {
        String card=share("FOLLOWERS");var follow=relation("FOLLOW","viewer","owner");
        ((ObjectNode)follow.get("outcome")).put("status","REJECTED");page("viewer",card);
        assertThatThrownBy(this::verify).hasMessageStartingWith("BEHAVIOR_ACCESS_CARD_DENIED");
    }
    @Test void eitherDirectionBlockEndsBothFollowsAndUnblockDoesNotResurrectThem() {
        String card=share("FOLLOWERS");relation("FOLLOW","viewer","owner");relation("FOLLOW","owner","viewer");
        page("viewer",card);relation("BLOCK","owner","viewer");page("viewer",null);
        relation("UNBLOCK","owner","viewer");page("viewer",card);
        assertThatThrownBy(this::verify).hasMessageStartingWith("BEHAVIOR_ACCESS_CARD_DENIED");
    }
    @Test void laterPrivacyDoesNotInvalidateEarlierPageAndNewShareDoesNotReviveOldCard() {
        String old=share("ACADEMY");page("viewer",old);
        event("VISIBILITY_CHANGE","owner",Map.of("wishId","wish","visibility","PRIVATE"));
        assertThat(verify().feedCards()).isEqualTo(1);
        String current=share("ACADEMY");page("viewer",current);
        assertThat(verify().feedCards()).isEqualTo(2);
        page("viewer",old);assertThatThrownBy(this::verify).hasMessageStartingWith("BEHAVIOR_ACCESS_CARD_DENIED");
    }
    @Test void rejectsDeletedSelfAndWrongAcademyCards() {
        String card=share("ACADEMY");
        var query=page("owner",card);assertThatThrownBy(this::verify).hasMessageStartingWith("BEHAVIOR_ACCESS_CARD_DENIED");
        query.put("actorStudentId","viewer");((ObjectNode)query.get("command")).put("academyId","other");
        assertThatThrownBy(this::verify).hasMessageStartingWith("BEHAVIOR_ACCESS_MEMBERSHIP");
        ((ObjectNode)query.get("command")).put("academyId","academy");assertThat(verify().feedCards()).isEqualTo(1);
        event("DELETE","owner",Map.of("wishId","wish"));page("viewer",card);
        assertThatThrownBy(this::verify).hasMessageStartingWith("BEHAVIOR_ACCESS_CARD_DENIED");
    }
    @Test void replayedCreateCannotUndoPriorPrivacyTransition() {
        String card=share("ACADEMY");event("CREATE","owner",Map.of("wishId","wish"));page("viewer",card);
        assertThat(verify().feedCards()).isEqualTo(1);
    }
    @Test void profileVisitNeedsMembershipAndNoBilateralBlockButNoPublicWish() {
        var visit=event("PROFILE_VISIT","viewer",Map.of("academyId","academy","targetStudentId","owner"));
        String eventId=visit.get("eventId").asString();UUID id=UUID.randomUUID();ids.put("BEHAVIOR_EVENT:"+eventId,id);
        tables.get("behavior_event").add(JSON.valueToTree(Map.of("actor_id",ids.get("STUDENT:viewer").toString(),"event_id",id.toString())));
        assertThat(verify().profileVisits()).isEqualTo(1);
        relation("BLOCK","viewer","owner");
        var blocked=event("PROFILE_VISIT","viewer",Map.of("academyId","academy","targetStudentId","owner"));
        UUID second=UUID.randomUUID();ids.put("BEHAVIOR_EVENT:"+blocked.get("eventId").asString(),second);
        tables.get("behavior_event").add(JSON.valueToTree(Map.of("actor_id",ids.get("STUDENT:viewer").toString(),"event_id",second.toString())));
        assertThatThrownBy(this::verify).hasMessageStartingWith("BEHAVIOR_ACCESS_PROFILE_DENIED");
    }
}
