package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.*;
import static org.assertj.core.api.Assertions.*;

import com.crabit.backend.behavior.BehaviorModels.Event;
import com.crabit.backend.behavior.BehaviorService;
import com.crabit.backend.behavior.BehaviorRetention;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class FeedVisitEvidencePostgresIT extends WishApiIntegrationSupport {
    @Autowired BehaviorService service;
    @Autowired BehaviorRetention retention;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.crabit.backend.recommendation.FeedVisitSignals signals;
    @Autowired com.crabit.backend.recommendation.FeedPageContextRepository feedPages;

    Instant dbNow() { return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(); }
    Event event(UUID id, Instant occurred) {
        return new Event(id,"PROFILE_VISIT",occurred,OWNER_ID,null,null,null,null,null);
    }
    Map<String,Object> evidence(UUID id) {
        return jdbc.queryForMap("SELECT *,category_ids::text categories,source_versions::text versions FROM feed_visit_evidence WHERE actor_id=? AND event_id=?",FRIEND_ID,id);
    }
    void title(String title) { jdbc.update("UPDATE wish SET purpose=? WHERE account_id=?",title,OWNER_ACCOUNT_ID); }

    @Test void feedPageTransitionsReplayAtomicallyAndSuccessorsMayChangeLimit() {
        Instant now = dbNow();
        UUID ranked = UUID.randomUUID(), tail = UUID.randomUUID();
        var initial = feedPages.create(FRIEND_ID, PRIMARY_ACADEMY_ID, now,
                UUID.randomUUID(), "feed-rules-v1", java.util.List.of(ranked));
        var first = feedPages.transition(initial.stateId(), initial.contextId(), initial.expiresAt(), FRIEND_ID, PRIMARY_ACADEMY_ID,
                now, 7, state -> new com.crabit.backend.recommendation.FeedPageContextRepository.Page(
                        java.util.List.of(ranked), 1, now, tail, java.util.List.of(ranked), true, true));
        assertThatThrownBy(() -> feedPages.transition(initial.stateId(), UUID.randomUUID(), initial.expiresAt(),
                FRIEND_ID, PRIMARY_ACADEMY_ID, now, 7, state -> { throw new AssertionError(); }))
                .isInstanceOf(com.crabit.backend.recommendation.FeedPageContextRepository.ContextExpired.class);
        assertThatThrownBy(() -> feedPages.transition(initial.stateId(), initial.contextId(), initial.expiresAt().plusSeconds(1),
                FRIEND_ID, PRIMARY_ACADEMY_ID, now, 7, state -> { throw new AssertionError(); }))
                .isInstanceOf(com.crabit.backend.recommendation.FeedPageContextRepository.ContextExpired.class);
        assertThat(feedPages.transition(initial.stateId(), initial.contextId(), initial.expiresAt(), FRIEND_ID, PRIMARY_ACADEMY_ID,
                now.plusSeconds(1), 7, state -> { throw new AssertionError("replay recomputed"); }))
                .isEqualTo(first);
        assertThatThrownBy(() -> feedPages.transition(initial.stateId(), initial.contextId(), initial.expiresAt(), FRIEND_ID,
                PRIMARY_ACADEMY_ID, now, 8, state -> { throw new AssertionError(); }))
                .isInstanceOf(com.crabit.backend.recommendation.FeedPageContextRepository.LimitReplayMismatch.class);

        assertThatThrownBy(() -> feedPages.transition(first.successorStateId(), initial.contextId(), initial.expiresAt(), FRIEND_ID,
                PRIMARY_ACADEMY_ID, now, 3, state -> { throw new IllegalStateException("photo failed"); }))
                .hasRootCauseMessage("photo failed");
        assertThat(feedPages.transition(first.successorStateId(), initial.contextId(), initial.expiresAt(), FRIEND_ID, PRIMARY_ACADEMY_ID,
                now, 3, state -> new com.crabit.backend.recommendation.FeedPageContextRepository.Page(
                        java.util.List.of(tail), 1, now, tail,
                        java.util.List.of(ranked, tail), false, false)).limit()).isEqualTo(3);

        var expired = feedPages.create(FRIEND_ID, PRIMARY_ACADEMY_ID, now.minusSeconds(301),
                null, null, java.util.List.of());
        assertThatThrownBy(() -> feedPages.transition(expired.stateId(), expired.contextId(), expired.expiresAt(), FRIEND_ID,
                PRIMARY_ACADEMY_ID, now, 1, state -> { throw new AssertionError(); }))
                .isInstanceOf(com.crabit.backend.recommendation.FeedPageContextRepository.ContextExpired.class);
    }
    void accept(UUID id, Instant occurred) {
        clock.set(dbNow()); service.collect(FRIEND_ID,PRIMARY_ACADEMY_ID,event(id,occurred));
    }

    @Test void oldTitleAndVisibilityAreCapturedAtOccurrenceAndReplayNeverRecaptures() {
        title("노트북");
        Instant occurred=dbNow();
        title("자전거");
        UUID id=UUID.randomUUID(); accept(id,occurred);
        var original=evidence(id);
        assertThat(original).containsEntry("evidence_status","COMPLETE").containsEntry("categories","[\"전자기기\"]");
        jdbc.update("DELETE FROM shared_card WHERE wish_id IN (SELECT id FROM wish WHERE account_id=?)",OWNER_ACCOUNT_ID);
        assertThat(service.collect(FRIEND_ID,PRIMARY_ACADEMY_ID,event(id,occurred)).replayed()).isTrue();
        assertThat(evidence(id)).isEqualTo(original);
        UUID delayed=UUID.randomUUID(); accept(delayed,occurred);
        assertThat(evidence(delayed)).containsEntry("categories","[\"전자기기\"]");
    }

    @Test void absenceAfterUnsharingIsKnownEmptyAndBaselineOrFutureArePermanentlyUnknown() {
        jdbc.update("DELETE FROM shared_card WHERE wish_id IN (SELECT id FROM wish WHERE account_id=?)",OWNER_ACCOUNT_ID);
        UUID empty=UUID.randomUUID(); accept(empty,dbNow());
        assertThat(evidence(empty)).containsEntry("evidence_status","COMPLETE").containsEntry("categories","[]");
        Instant baseline=jdbc.queryForObject("SELECT started_at FROM feed_history_collection WHERE id=1",Timestamp.class).toInstant();
        UUID early=UUID.randomUUID(); accept(early,baseline.minusNanos(1000));
        assertThat(evidence(early)).containsEntry("unknown_reason","BEFORE_BASELINE").containsEntry("categories",null);
        UUID future=UUID.randomUUID(); Instant occurred=dbNow().plusSeconds(240); accept(future,occurred);
        var original=evidence(future);
        assertThat(original).containsEntry("unknown_reason","FUTURE_OCCURRED_AT").containsEntry("categories",null);
        clock.set(occurred.plusSeconds(1));
        assertThat(service.collect(FRIEND_ID,PRIMARY_ACADEMY_ID,event(future,occurred)).replayed()).isTrue();
        assertThat(evidence(future)).isEqualTo(original);
    }

    @Test void exactTitleBoundaryUsesNewVersionAndStoredEvidenceRejectsUpdates() {
        title("노트북"); title("자전거");
        Instant boundary=jdbc.queryForObject("SELECT max(valid_from) FROM feed_source_history WHERE source_kind='wish' AND source_id IN (SELECT id FROM wish WHERE account_id=?)",Timestamp.class,OWNER_ACCOUNT_ID).toInstant();
        UUID id=UUID.randomUUID(); accept(id,boundary);
        assertThat(evidence(id)).containsEntry("categories","[\"스포츠\"]");
        assertThatThrownBy(() -> jdbc.update("UPDATE feed_visit_evidence SET category_ids='[]'::jsonb WHERE event_id=?",id))
                .hasMessageContaining("immutable");
    }

    @Test void publishedAbandonmentContributesHistoricalInterest() {
        title("노트북");
        new TransactionTemplate(manager).execute(status -> {
            jdbc.update("DELETE FROM representative_wish_selection WHERE account_id=?",OWNER_ACCOUNT_ID);
            jdbc.update("UPDATE wish SET state='ABANDONED',wish_amount=0,abandonment_amount=0,abandoned_at=clock_timestamp(),completed_at=NULL WHERE account_id=?",OWNER_ACCOUNT_ID);
            return null;
        });
        jdbc.update("UPDATE shared_card SET kind='ABANDONMENT' WHERE wish_id IN (SELECT id FROM wish WHERE account_id=?)",OWNER_ACCOUNT_ID);
        UUID id=UUID.randomUUID(); accept(id,dbNow());
        assertThat(evidence(id)).containsEntry("evidence_status","COMPLETE").containsEntry("categories","[\"전자기기\"]");
    }

    @Test void historicalClosedAccountAndMembershipAndBlockAreNotReplacedWithCurrentEligibility() {
        title("노트북");
        jdbc.update("UPDATE card_balance_account SET closed_at=clock_timestamp() WHERE id=?",OWNER_ACCOUNT_ID);
        Instant closed=dbNow();
        jdbc.update("UPDATE card_balance_account SET closed_at=NULL WHERE id=?",OWNER_ACCOUNT_ID);
        UUID account=UUID.randomUUID(); accept(account,closed);
        assertThat(evidence(account)).containsEntry("categories","[]");
        jdbc.update("UPDATE academy_membership SET left_at=clock_timestamp() WHERE student_id=? AND academy_id=?",OWNER_ID,PRIMARY_ACADEMY_ID);
        Instant left=dbNow();
        jdbc.update("UPDATE academy_membership SET left_at=NULL WHERE student_id=? AND academy_id=?",OWNER_ID,PRIMARY_ACADEMY_ID);
        UUID membership=UUID.randomUUID(); accept(membership,left);
        assertThat(evidence(membership)).containsEntry("categories","[]");
        UUID block=UUID.randomUUID();
        jdbc.update("INSERT INTO student_block(id,blocker_id,blocked_id,blocked_at) VALUES (?,?,?,clock_timestamp())",block,OWNER_ID,FRIEND_ID);
        Instant blocked=dbNow();
        jdbc.update("UPDATE student_block SET released_at=clock_timestamp() WHERE id=?",block);
        UUID blockedEvent=UUID.randomUUID(); accept(blockedEvent,blocked);
        assertThat(evidence(blockedEvent)).containsEntry("categories","[]");
    }

    @Test void followersSharingRequiresTheHistoricalDirectionalFollow() {
        title("노트북");
        jdbc.update("UPDATE shared_card SET visibility='FOLLOWERS' WHERE wish_id IN (SELECT id FROM wish WHERE account_id=?)",OWNER_ACCOUNT_ID);
        jdbc.update("UPDATE student_follow SET ended_at=clock_timestamp() WHERE source_id=? AND target_id=? AND academy_id=?",FRIEND_ID,OWNER_ID,PRIMARY_ACADEMY_ID);
        Instant unfollowed=dbNow();
        jdbc.update("UPDATE student_follow SET ended_at=NULL WHERE source_id=? AND target_id=? AND academy_id=?",FRIEND_ID,OWNER_ID,PRIMARY_ACADEMY_ID);
        UUID id=UUID.randomUUID(); accept(id,unfollowed);
        assertThat(evidence(id)).containsEntry("categories","[]");
        UUID followed=UUID.randomUUID(); accept(followed,dbNow());
        assertThat(evidence(followed)).containsEntry("categories","[\"전자기기\"]");
    }

    @Test void futureSkewRetentionIncludesTheLaterEndAndPreservesExactReplay() {
        UUID id=UUID.randomUUID(); Instant occurred=dbNow().plusSeconds(240); accept(id,occurred);
        var original=evidence(id);
        clock.set(occurred.plus(Duration.ofDays(90)));
        retention.cleanup();
        assertThat(service.collect(FRIEND_ID,PRIMARY_ACADEMY_ID,event(id,occurred)).replayed()).isTrue();
        assertThat(evidence(id)).isEqualTo(original);
        clock.set(clock.instant().plusNanos(1000)); retention.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_visit_evidence WHERE event_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM behavior_event WHERE event_id=?",Long.class,id)).isZero();
    }

    @Test void immutableInterestSurvivesUnsharingButFutureUnknownWaitsForOccurrence() {
        title("노트북"); Instant occurred=dbNow(); UUID id=UUID.randomUUID(); accept(id,occurred);
        jdbc.update("DELETE FROM shared_card WHERE wish_id IN (SELECT id FROM wish WHERE account_id=?)",OWNER_ACCOUNT_ID);
        var saved=signals.at(FRIEND_ID,PRIMARY_ACADEMY_ID,dbNow());
        assertThat(saved.authors()).containsExactly(OWNER_ID);
        assertThat(saved.categories()).containsExactly("전자기기");
        assertThat(signals.at(FRIEND_ID,PRIMARY_ACADEMY_ID,occurred.plus(Duration.ofDays(90))).authors()).containsExactly(OWNER_ID);
        assertThat(signals.at(FRIEND_ID,PRIMARY_ACADEMY_ID,occurred.plus(Duration.ofDays(90)).plusNanos(1000)).authors()).isEmpty();
        UUID future=UUID.randomUUID(); Instant futureAt=dbNow().plusSeconds(240);
        clock.set(dbNow());
        service.collect(NONFRIEND_ID,PRIMARY_ACADEMY_ID,event(future,futureAt));
        assertThat(signals.at(NONFRIEND_ID,PRIMARY_ACADEMY_ID,dbNow()).authors()).isEmpty();
        var futureSignal=signals.at(NONFRIEND_ID,PRIMARY_ACADEMY_ID,futureAt);
        assertThat(futureSignal.authors()).containsExactly(OWNER_ID);
        assertThat(futureSignal.categories()).isEmpty();
    }

    @Test void everyEligibilitySourceRecordsDatabaseTimeRatherThanBackdatedBusinessTime() {
        for (String table : java.util.List.of("academy_membership","card_balance_account","shared_card","student_block","student_follow","wish")) {
            UUID id=jdbc.queryForObject("SELECT id FROM "+table+" LIMIT 1",UUID.class);
            Instant before=dbNow();
            jdbc.update("UPDATE "+table+" SET id=id WHERE id=?",id);
            var versions=jdbc.queryForList("SELECT valid_from,valid_to FROM feed_source_history WHERE source_kind=? AND source_id=? ORDER BY version",table,id);
            assertThat(versions).hasSizeGreaterThanOrEqualTo(2);
            var last=versions.getLast(); var previous=versions.get(versions.size()-2);
            assertThat(((Timestamp)last.get("valid_from")).toInstant()).isAfterOrEqualTo(before);
            assertThat(previous.get("valid_to")).isEqualTo(last.get("valid_from"));
            assertThat(last.get("valid_to")).isNull();
        }
    }

    @Test void acceptanceWaitsForAnUncommittedSourceChangeAndReadsTheCompleteOldInterval() throws Exception {
        title("노트북"); Instant occurred=dbNow();
        var changed=new CountDownLatch(1); var release=new CountDownLatch(1);
        UUID id=UUID.randomUUID(); clock.set(dbNow());
        try (var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                title("자전거"); changed.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("release timed out"); }
                catch(InterruptedException e) { throw new RuntimeException(e); }
                return null;
            }));
            assertThat(changed.await(5,TimeUnit.SECONDS)).isTrue();
            var reader=pool.submit(() -> service.collect(FRIEND_ID,PRIMARY_ACADEMY_ID,event(id,occurred)));
            try { assertThatThrownBy(() -> reader.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            writer.get(5,TimeUnit.SECONDS); reader.get(5,TimeUnit.SECONDS);
        }
        assertThat(evidence(id)).containsEntry("evidence_status","COMPLETE").containsEntry("categories","[\"전자기기\"]");
    }
}
