package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.crabit.backend.api.WishApiIntegrationSupport;
import com.crabit.backend.behavior.BehaviorRetention;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FeedRetentionPostgresIT extends WishApiIntegrationSupport {
    @Autowired BehaviorRetention retention;

    @Test
    void deletesExpiredTraversalChainsBeforeTheirStatesAndPreservesLiveChains() {
        UUID expired = traversal(COMMAND_TIME.minusSeconds(300));
        UUID live = traversal(COMMAND_TIME.minusSeconds(299));
        retention.cleanup();
        assertThat(jdbc.queryForList("SELECT id FROM feed_page_context", UUID.class))
                .contains(live).doesNotContain(expired);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_page_state WHERE context_id=?",
                Integer.class, expired)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_page_transition t JOIN feed_page_state s"
                + " ON s.id=t.input_state_id WHERE s.context_id=?", Integer.class, live)).isEqualTo(2);
        retention.cleanup();
        assertThat(jdbc.queryForList("SELECT id FROM feed_page_context", UUID.class)).contains(live);
        clock.set(COMMAND_TIME.plusSeconds(1));
        retention.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_page_context WHERE id=?",
                Integer.class, live)).isZero();
    }

    private UUID traversal(Instant created) {
        UUID context = UUID.randomUUID();
        jdbc.update("INSERT INTO feed_page_context VALUES (?,?,?,?,?,NULL,NULL,'[]','LATEST')",
                context, UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(created),
                Timestamp.from(created.plusSeconds(300)));
        // Insert successors before predecessors to reproduce the FK-sensitive deletion order.
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        for (UUID state : new UUID[] {third, second, first})
            jdbc.update("INSERT INTO feed_page_state VALUES (?,?,0,NULL,NULL,'[]')", state, context);
        jdbc.update("INSERT INTO feed_page_transition VALUES (?,1,'[]',?,false,?)",
                first, second, Timestamp.from(created));
        jdbc.update("INSERT INTO feed_page_transition VALUES (?,1,'[]',?,false,?)",
                second, third, Timestamp.from(created));
        return context;
    }
}
