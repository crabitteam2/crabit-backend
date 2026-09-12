package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SimulationRecapStoryVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    ObjectNode request(String... ids) {
        var r=(ObjectNode)JSON.readTree("""
          {"student_id":"viewer","academy_id":"academy","snapshot_at":"2026-06-07T15:00:00Z",
           "period":{"start_date":"2026-06-01","end_date_exclusive":"2026-06-08"},"input":{}}
          """);
        var a=((ObjectNode)r.get("input")).putArray("success_story_candidates");
        for(String id:ids)a.addObject().put("wish_id",id).put("type_title","ACADEMY_SUCCESS");return r;
    }
    ObjectNode peers(){return (ObjectNode)JSON.readTree("""
      {"accounts":[{"id":"account","student_id":"author","academy_id":"academy","closed_at":null}],
       "memberships":[{"student_id":"author","academy_id":"academy","left_at":null}],
       "wish":[{"id":"wish","account_id":"account","academy_id":"academy","state":"COMPLETED",
        "completed_at":"2026-06-02T00:00:00Z","deleted_at":null}]}
      """);}
    ObjectNode source(){return (ObjectNode)JSON.readTree("""
      {"cards":[{"id":"card","wish_id":"wish","kind":"COMPLETION","visibility":"ACADEMY","updated_at":"2026-06-02T00:00:00Z"}],
       "follows":[],"blocks":[]}
      """);}
    ObjectNode card(ObjectNode s){return (ObjectNode)s.get("cards").get(0);}
    ObjectNode wish(ObjectNode p){return (ObjectNode)p.get("wish").get(0);}
    void follow(ObjectNode s,String from,String to,boolean ended){((ArrayNode)s.get("follows")).addObject().put("academy_id","academy").put("source_id",from).put("target_id",to).put("ended_at",ended?"2026-06-03T00:00:00Z":null);}
    void block(ObjectNode s,String from,String to,boolean released){((ArrayNode)s.get("blocks")).addObject().put("blocker_id",from).put("blocked_id",to).put("released_at",released?"2026-06-03T00:00:00Z":null);}
    @Test void academySuccessDoesNotRequireFollow(){assertThat(SimulationRecapStoryVerifier.verify(request("wish"),peers(),source()).selected()).isEqualTo(1);}
    @Test void followersRequiresActiveViewerToAuthorDirection(){
        var s=source();card(s).put("visibility","FOLLOWERS");
        follow(s,"author","viewer",false);follow(s,"viewer","author",true);
        assertThat(SimulationRecapStoryVerifier.verify(request(),peers(),s).selected()).isZero();
        follow(s,"viewer","author",false);assertThat(SimulationRecapStoryVerifier.verify(request("wish"),peers(),s).selected()).isEqualTo(1);
    }
    @Test void eitherBlockDirectionHidesButReleasedBlocksDoNot(){
        for(boolean reverse:List.of(true,false)) {
            var s=source();block(s,reverse?"author":"viewer",reverse?"viewer":"author",false);
            assertThat(SimulationRecapStoryVerifier.verify(request(),peers(),s).selected()).isZero();
            ((ObjectNode)s.get("blocks").get(0)).put("released_at","2026-06-03T00:00:00Z");
            assertThat(SimulationRecapStoryVerifier.verify(request("wish"),peers(),s).selected()).isEqualTo(1);
        }
    }
    @Test void excludesSelfPrivateDeletedClosedLeftForeignAndUnfinished(){
        for(String mode:List.of("self","private","deleted","closed","left","foreign","unfinished")) {
            var p=peers();var s=source();switch(mode){
                case "self" -> ((ObjectNode)p.get("accounts").get(0)).put("student_id","viewer");
                case "private" -> card(s).put("visibility","PRIVATE");
                case "deleted" -> wish(p).put("deleted_at","2026-06-03T00:00:00Z");
                case "closed" -> ((ObjectNode)p.get("accounts").get(0)).put("closed_at","2026-06-03T00:00:00Z");
                case "left" -> ((ObjectNode)p.get("memberships").get(0)).put("left_at","2026-06-03T00:00:00Z");
                case "foreign" -> wish(p).put("academy_id","other");
                case "unfinished" -> wish(p).put("state","IN_PROGRESS");
            }
            assertThat(SimulationRecapStoryVerifier.verify(request(),p,s).selected()).as(mode).isZero();
        }
    }
    @Test void completionAndShareMustBothBeInHalfOpenKoreanPeriod(){
        for(String field:List.of("completed_at","updated_at"))for(String at:List.of("2026-05-31T14:59:59.999999Z","2026-06-07T15:00:00Z")) {
            var p=peers();var s=source();(field.equals("completed_at")?wish(p):card(s)).put(field,at);
            assertThat(SimulationRecapStoryVerifier.verify(request(),p,s).selected()).isZero();
        }
        var p=peers();var s=source();wish(p).put("completed_at","2026-05-31T15:00:00Z");card(s).put("updated_at","2026-06-07T14:59:59.999999Z");
        assertThat(SimulationRecapStoryVerifier.verify(request("wish"),p,s).selected()).isEqualTo(1);
    }
    @Test void computesCompleteTopFiveInPostgresOrderAndRejectsMissingExtraReorderedOrDuplicateCandidates(){
        var p=peers();var s=source();((ArrayNode)p.get("wish")).removeAll();((ArrayNode)s.get("cards")).removeAll();
        List<String> ids=List.of("ffffffff-0000-0000-0000-000000000000","00000000-0000-0000-0000-000000000000","80000000-0000-0000-0000-000000000000","10000000-0000-0000-0000-000000000000","20000000-0000-0000-0000-000000000000","30000000-0000-0000-0000-000000000000");
        for(String id:ids){var w=wish(peers());w.put("id",id);((ArrayNode)p.get("wish")).add(w);var c=card(source());c.put("wish_id",id);((ArrayNode)s.get("cards")).add(c);}
        var expected=ids.stream().sorted().limit(5).toList();
        var v=SimulationRecapStoryVerifier.verify(request(expected.toArray(String[]::new)),p,s);assertThat(v.eligibleBeforeLimit()).isEqualTo(6);assertThat(v.selected()).isEqualTo(5);
        var reversed=new ArrayList<>(expected);Collections.reverse(reversed);
        for(List<String> wrong:List.of(expected.subList(0,4),ids,reversed,List.of(expected.get(0),expected.get(0))))
            assertThatThrownBy(()->SimulationRecapStoryVerifier.verify(request(wrong.toArray(String[]::new)),p,s)).hasMessage("RECAP_STORY_CANDIDATE_SELECTION");
    }
    @Test void rejectsWrongCompletionKindAndTitle(){
        var s=source();card(s).put("kind","PROGRESS");assertThatThrownBy(()->SimulationRecapStoryVerifier.verify(request("wish"),peers(),s)).hasMessage("RECAP_STORY_COMPLETION_KIND");
        var r=request("wish");((ObjectNode)r.get("input").get("success_story_candidates").get(0)).put("type_title","invented");
        assertThatThrownBy(()->SimulationRecapStoryVerifier.verify(r,peers(),source())).hasMessage("RECAP_STORY_TYPE_TITLE");
    }
}
