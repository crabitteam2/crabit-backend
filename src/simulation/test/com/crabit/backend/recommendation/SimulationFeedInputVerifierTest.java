package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

class SimulationFeedInputVerifierTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    record Fixture(ObjectNode request,ObjectNode source) {}
    ObjectNode monthly(String month,String coverage) {
        var m=JSON.createObjectNode().put("month",month).put("coverage",coverage).put("metrics_version","core-metrics-v1");
        if(coverage.equals("COMPLETE"))m.putObject("values").put("deposit_count",0).put("total_savings",0).put("avg_amount",0.0)
            .put("abandon_count",0).put("transfer_count",0).put("visit_count",0).putNull("regularity_std").putNull("pace_bias");
        else m.putNull("values");return m;
    }
    Fixture fixture() {
        var s=JSON.createObjectNode();for(String table:List.of("card_balance_account","academy_membership","wish","shared_card","student_follow","student_block","behavior_collection","ledger_event","ledger_wish_effect","behavior_event","feed_visit_evidence","representative_wish_selection"))s.putArray(table);
        ((ArrayNode)s.get("behavior_collection")).addObject().put("id",1).put("started_at","2026-06-01T00:00:00Z");
        for(String id:List.of("viewer","author")) {
            ((ArrayNode)s.get("card_balance_account")).addObject().put("id",id+"-account").put("student_id",id).put("academy_id","academy").put("opened_at","2026-05-01T00:00:00Z").putNull("closed_at");
            ((ArrayNode)s.get("academy_membership")).addObject().put("student_id",id).put("academy_id","academy").putNull("left_at");
        }
        ((ArrayNode)s.get("wish")).addObject().put("id","wish").put("account_id","author-account").put("academy_id","academy").put("purpose","책 모으기").put("target_amount",10000).put("state","IN_PROGRESS").put("created_at","2026-06-03T00:00:00Z").putNull("deleted_at").putNull("completed_at").putNull("target_date");
        ((ArrayNode)s.get("shared_card")).addObject().put("id","card").put("wish_id","wish").put("visibility","ACADEMY").put("updated_at","2026-06-03T00:00:00Z");
        var r=JSON.createObjectNode().put("viewer_id","viewer").put("academy_id","academy").put("recommendation_at","2026-07-01T00:00:00Z").put("timezone","Asia/Seoul").put("feature_version","feed-features-v1").put("classifier_version","wish-category-v1@sha256:de23b80260907e3d818892c0ea6ba2d9d28251e49d75a812fb925e1a47733f61");
        r.set("viewer_previous_month",monthly("2026-06","PARTIAL"));
        var c=r.putArray("candidates").addObject().put("card_id","card").put("author_id","author").put("category_id","도서").put("basic_similarity",0.0).put("title_similarity",0.0).put("visited_author_before",false).put("visited_category_before",false).put("state","IN_PROGRESS").put("created_at","2026-06-03T00:00:00Z").put("content_updated_at","2026-06-03T00:00:00Z").putNull("target_date").putNull("closed_at");
        c.set("author_previous_month",monthly("2026-06","PARTIAL"));return new Fixture(r,s);
    }
    @Test void independentlyAcceptsVisibleCandidateAndPartialCollectionMonth() {
        var f=fixture();assertThat(SimulationFeedInputVerifier.verify(f.request(),f.source())).containsEntry("candidateCount",1).containsEntry("monthlyMetricValuesVerified",true);
    }
    @Test void refusesMissingAndExtraCandidates() {
        var f=fixture();f.request().putArray("candidates");assertThatThrownBy(()->SimulationFeedInputVerifier.verify(f.request(),f.source())).hasMessage("FEED_INPUT_CANDIDATE_COUNT");
    }
    @Test void inverseBlockAndUnfollowedPrivateAudienceCannotLeak() {
        var f=fixture();((ArrayNode)f.source().get("student_block")).addObject().put("blocker_id","author").put("blocked_id","viewer").putNull("released_at");
        assertThatThrownBy(()->SimulationFeedInputVerifier.verify(f.request(),f.source())).hasMessage("FEED_INPUT_CANDIDATE_COUNT");
        var g=fixture();((ObjectNode)g.source().get("shared_card").get(0)).put("visibility","FOLLOWERS");
        assertThatThrownBy(()->SimulationFeedInputVerifier.verify(g.request(),g.source())).hasMessage("FEED_INPUT_CANDIDATE_COUNT");
        ((ArrayNode)g.source().get("student_follow")).addObject().put("source_id","viewer").put("target_id","author").put("academy_id","academy").putNull("ended_at");
        assertThat(SimulationFeedInputVerifier.verify(g.request(),g.source())).containsEntry("candidateCount",1);
    }
    @Test void rejectsZeroFilledUnobservedValuesAndIncorrectCoverage() {
        var f=fixture();((ObjectNode)f.request().get("viewer_previous_month")).putObject("values");
        assertThatThrownBy(()->SimulationFeedInputVerifier.verify(f.request(),f.source())).hasMessage("FEED_INPUT_VALUES_OBSERVABILITY");
        var g=fixture();((ObjectNode)g.request().get("viewer_previous_month")).put("coverage","COMPLETE");
        assertThatThrownBy(()->SimulationFeedInputVerifier.verify(g.request(),g.source())).hasMessage("FEED_INPUT_COVERAGE");
    }
    @Test void completedAuthorsUseMonthBeforeClosureEvenWhenViewedLater() {
        var f=fixture();var w=(ObjectNode)f.source().get("wish").get(0);w.put("completed_at","2026-06-30T14:59:59Z").put("state","COMPLETED");
        var c=(ObjectNode)f.request().get("candidates").get(0);c.put("closed_at","2026-06-30T14:59:59Z").put("state","COMPLETED");
        assertThatThrownBy(()->SimulationFeedInputVerifier.verify(f.request(),f.source())).hasMessage("FEED_INPUT_MONTH");
        c.set("author_previous_month",monthly("2026-05","UNOBSERVED"));
        assertThat(SimulationFeedInputVerifier.verify(f.request(),f.source())).containsEntry("candidateCount",1);
    }
    @Test void seoulBoundaryAndRetentionSkewChangeCoverage() {
        var f=fixture();((ObjectNode)f.source().get("behavior_collection").get(0)).put("started_at","2026-05-01T00:00:00Z");
        f.request().set("viewer_previous_month",monthly("2026-06","COMPLETE"));
        ((ObjectNode)f.request().get("candidates").get(0)).set("author_previous_month",monthly("2026-06","COMPLETE"));
        assertThat(SimulationFeedInputVerifier.verify(f.request(),f.source())).containsEntry("monthlyWindowsVerified",true);
        f.request().put("recommendation_at","2026-06-30T14:59:59Z");
        assertThatThrownBy(()->SimulationFeedInputVerifier.verify(f.request(),f.source())).hasMessage("FEED_INPUT_MONTH");
    }
    @Test void appliesExactHundredCandidateBoundaryAndRejectsOrderDrift() {
        var f=fixture();var template=f.source().get("shared_card").get(0).deepCopy();
        var candidate=f.request().get("candidates").get(0).deepCopy();
        var cards=f.source().putArray("shared_card");var requested=f.request().putArray("candidates");
        for(int n=0;n<101;n++) {var row=template.deepCopy();((ObjectNode)row).put("id",String.format("card-%03d",n));cards.add(row);}
        for(int n=100;n>=1;n--){var c=candidate.deepCopy();((ObjectNode)c).put("card_id",String.format("card-%03d",n));requested.add(c);}
        assertThat(SimulationFeedInputVerifier.verify(f.request(),f.source())).containsEntry("candidateCount",100);
        ((ObjectNode)requested.get(99)).put("card_id","card-000");
        assertThatThrownBy(()->SimulationFeedInputVerifier.verify(f.request(),f.source())).hasMessage("FEED_INPUT_CANDIDATE_ORDER_OR_ID");
    }
    @Test void closedAccountsAndDepartedMembersAndAbandonedWishesAreExcluded() {
        for(String condition:List.of("account","membership","abandoned","deleted")) {
            var f=fixture();
            switch(condition) {
                case "account" -> ((ObjectNode)f.source().get("card_balance_account").get(1)).put("closed_at","2026-06-20T00:00:00Z");
                case "membership" -> ((ObjectNode)f.source().get("academy_membership").get(1)).put("left_at","2026-06-20T00:00:00Z");
                case "abandoned" -> ((ObjectNode)f.source().get("wish").get(0)).put("state","ABANDONED");
                case "deleted" -> ((ObjectNode)f.source().get("wish").get(0)).put("deleted_at","2026-06-20T00:00:00Z");
            }
            assertThatThrownBy(()->SimulationFeedInputVerifier.verify(f.request(),f.source())).hasMessage("FEED_INPUT_CANDIDATE_COUNT");
            f.request().putArray("candidates");assertThat(SimulationFeedInputVerifier.verify(f.request(),f.source())).containsEntry("candidateCount",0);
        }
    }
}
