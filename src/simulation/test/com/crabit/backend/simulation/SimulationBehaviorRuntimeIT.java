package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;

import com.crabit.backend.behavior.*;
import com.crabit.backend.relationship.RelationshipCommandService;
import com.crabit.backend.wish.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SimulationBehaviorRuntimeIT {
    private static final Instant START=SimulationCashOracle.START;

    @Test void capturesRealHistoricalCategoriesOnceAndRollsBackDeniedVisits() {
        UUID academy=UUID.randomUUID(), viewer=UUID.randomUUID(), author=UUID.randomUUID(), account=UUID.randomUUID();
        UUID visit=UUID.randomUUID();
        try(var runtime=new SimulationDomainRuntime()) {
            runtime.executeAt(START,s->{
                var j=s.jdbc();
                j.update("INSERT INTO academy(id,name) VALUES (?,'synthetic')",academy);
                for(UUID student:List.of(viewer,author)) {
                    j.update("INSERT INTO student(id,nickname,age,age_provenance) VALUES (?,'synthetic',9,'PROVIDED')",student);
                    j.update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,?)",UUID.randomUUID(),student,academy,Timestamp.from(START));
                }
                j.update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",account,author,academy,Timestamp.from(START));
                assertThat(s.context().getBeansOfType(BehaviorService.class)).hasSize(1);
                return null;
            });
            var created=runtime.executeAt(START.plusSeconds(1),s->s.service(WishLifecycleService.class)
                .create(author,academy,account,"create","노트북",20000,(LocalDate)null));
            var shared=runtime.executeAt(START.plusSeconds(2),s->s.service(WishLifecycleService.class)
                .patch(author,academy,account,created.wish().id(),created.wish().version(),new WishPatch(null,null,false,null,WishVisibility.ACADEMY)));
            Instant occurred=START.plusSeconds(3);
            var event=new BehaviorModels.Event(visit,"PROFILE_VISIT",occurred,author,null,null,null,null,null);
            var first=runtime.executeAt(occurred,s->s.service(BehaviorService.class).collect(viewer,academy,event));
            assertThat(first.replayed()).isFalse();
            assertThat(first.body().occurredAt()).isEqualTo(occurred);
            assertThat(first.body().receivedAt()).isEqualTo(occurred);
            var original=runtime.executeAt(occurred,s->s.jdbc().queryForMap(
                "SELECT *,category_ids::text categories,source_versions::text versions FROM feed_visit_evidence WHERE actor_id=? AND event_id=?",viewer,visit));
            assertThat(original).containsEntry("evidence_status","COMPLETE").containsEntry("categories","[\"전자기기\"]");
            assertThat(((Timestamp)original.get("captured_at")).toInstant()).isEqualTo(occurred);
            assertThat(((Timestamp)original.get("history_coverage_start")).toInstant()).isEqualTo(START);
            assertThat(original.get("versions").toString()).contains("wish:","card:","history:");
            // A later privacy change cannot rewrite already captured interest evidence.
            runtime.executeAt(START.plusSeconds(4),s->s.service(WishLifecycleService.class)
                .patch(author,academy,account,created.wish().id(),shared.wish().version(),new WishPatch(null,null,false,null,WishVisibility.PRIVATE)));
            runtime.executeAt(START.plusSeconds(5),s->{
                assertThat(s.service(BehaviorService.class).collect(viewer,academy,event).replayed()).isTrue();
                assertThat(s.jdbc().queryForMap("SELECT *,category_ids::text categories,source_versions::text versions FROM feed_visit_evidence WHERE actor_id=? AND event_id=?",viewer,visit)).isEqualTo(original);
                var fresh=new BehaviorModels.Event(UUID.randomUUID(),"PROFILE_VISIT",s.clock().instant(),author,null,null,null,null,null);
                s.service(BehaviorService.class).collect(viewer,academy,fresh);
                assertThat(s.jdbc().queryForObject("SELECT category_ids::text FROM feed_visit_evidence WHERE actor_id=? AND event_id=?",String.class,viewer,fresh.eventId())).isEqualTo("[]");
                return null;
            });
            runtime.executeAt(START.plusSeconds(6),s->{s.service(RelationshipCommandService.class).blockStudent(author,viewer,s.clock().instant());return null;});
            runtime.executeAt(START.plusSeconds(7),s->{
                UUID denied=UUID.randomUUID();
                var blocked=new BehaviorModels.Event(denied,"PROFILE_VISIT",s.clock().instant(),author,null,null,null,null,null);
                assertThatThrownBy(()->s.service(BehaviorService.class).collect(viewer,academy,blocked))
                    .isInstanceOfSatisfying(BehaviorException.class,e->assertThat(e.code()).isEqualTo("PROFILE_NOT_FOUND"));
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM behavior_event WHERE event_id=?",Long.class,denied)).isZero();
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM feed_visit_evidence WHERE event_id=?",Long.class,denied)).isZero();
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM behavior_event",Long.class)).isEqualTo(2);
                return null;
            });
        }
    }
    @Test void unmatchedClickPersistsNoExposureAndDeniedActionsLeaveNoRows() {
        UUID academy=UUID.randomUUID(),viewer=UUID.randomUUID(),author=UUID.randomUUID(),account=UUID.randomUUID();
        try(var runtime=new SimulationDomainRuntime()) {
            runtime.executeAt(START,s->{
                s.jdbc().update("INSERT INTO academy(id,name) VALUES (?,'synthetic')",academy);
                for(UUID student:List.of(viewer,author)) {
                    s.jdbc().update("INSERT INTO student(id,nickname,age,age_provenance) VALUES (?,'synthetic',9,'PROVIDED')",student);
                    s.jdbc().update("INSERT INTO academy_membership(id,student_id,academy_id,joined_at) VALUES (?,?,?,?)",UUID.randomUUID(),student,academy,Timestamp.from(START));
                }
                s.jdbc().update("INSERT INTO card_balance_account(id,student_id,academy_id,opened_at) VALUES (?,?,?,?)",account,author,academy,Timestamp.from(START));return null;
            });
            var created=runtime.executeAt(START.plusSeconds(1),s->s.service(WishLifecycleService.class).create(author,academy,account,"create","노트북",20000,(LocalDate)null));
            var shared=runtime.executeAt(START.plusSeconds(2),s->s.service(WishLifecycleService.class).patch(author,academy,account,created.wish().id(),0,new WishPatch(null,null,false,null,WishVisibility.ACADEMY)));
            var feed=runtime.executeAt(START.plusSeconds(3),s->s.service(BehaviorService.class).createResult(viewer,academy,null,100));
            UUID card=feed.items().getFirst().sharedCardId(),impression=UUID.randomUUID(),clickId=UUID.randomUUID();
            var click=new BehaviorModels.Event(clickId,"FEED_CLICK",START.plusSeconds(4),null,feed.resultContextId(),card,0,impression,"AUTHOR_PROFILE");
            runtime.executeAt(START.plusSeconds(4),s->{
                var accepted=s.service(BehaviorService.class).collect(viewer,academy,click);
                assertThat(accepted.body().receivedAt()).isEqualTo(START.plusSeconds(4));
                var row=s.jdbc().queryForMap("SELECT * FROM behavior_impression WHERE actor_id=? AND impression_id=?",viewer,impression);
                assertThat(row.get("exposed_event_id")).isNull();
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM behavior_event WHERE event_type='FEED_EXPOSURE'",Long.class)).isZero();
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM behavior_event WHERE event_type='FEED_CLICK'",Long.class)).isEqualTo(1);
                assertThat(s.service(BehaviorService.class).collect(viewer,academy,click).replayed()).isTrue();
                return null;
            });
            runtime.executeAt(START.plusSeconds(5),s->{
                var exposure=new BehaviorModels.Event(UUID.randomUUID(),"FEED_EXPOSURE",s.clock().instant(),null,feed.resultContextId(),card,0,impression,null);
                s.service(BehaviorService.class).collect(viewer,academy,exposure);
                assertThat(s.jdbc().queryForObject("SELECT exposed_event_id FROM behavior_impression WHERE actor_id=? AND impression_id=?",UUID.class,viewer,impression)).isEqualTo(exposure.eventId());
                var duplicate=new BehaviorModels.Event(UUID.randomUUID(),"FEED_EXPOSURE",s.clock().instant(),null,feed.resultContextId(),card,0,impression,null);
                assertThatThrownBy(()->s.service(BehaviorService.class).collect(viewer,academy,duplicate)).isInstanceOfSatisfying(BehaviorException.class,e->assertThat(e.code()).isEqualTo("IMPRESSION_ALREADY_EXPOSED"));
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM behavior_event WHERE event_id=?",Long.class,duplicate.eventId())).isZero();return null;
            });
            runtime.executeAt(START.plusSeconds(6),s->s.service(WishLifecycleService.class).patch(author,academy,account,created.wish().id(),shared.wish().version(),new WishPatch(null,null,false,null,WishVisibility.PRIVATE)));
            runtime.executeAt(START.plusSeconds(7),s->{
                UUID deniedId=UUID.randomUUID(),deniedImpression=UUID.randomUUID();
                var denied=new BehaviorModels.Event(deniedId,"FEED_CLICK",s.clock().instant(),null,feed.resultContextId(),card,0,deniedImpression,"AUTHOR_PROFILE");
                assertThatThrownBy(()->s.service(BehaviorService.class).collect(viewer,academy,denied)).isInstanceOfSatisfying(BehaviorException.class,e->assertThat(e.code()).isEqualTo("SHARED_CARD_NOT_FOUND"));
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM behavior_event WHERE event_id=?",Long.class,deniedId)).isZero();
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM behavior_impression WHERE impression_id=?",Long.class,deniedImpression)).isZero();
                assertThat(s.jdbc().queryForObject("SELECT count(*) FROM behavior_event",Long.class)).isEqualTo(2);return null;
            });
        }
    }

}
