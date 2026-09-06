package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.account.StudentRepository;
import com.crabit.backend.account.Student;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class StudentBlockConcurrencyIT extends WishApiIntegrationSupport {
    private static final String BLOCKS = "/v1/me/student-blocks";

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private StudentRepository students;

    @Test
    void earlierReblockRequestSerializesAfterLaterUnblockAndUsesPostLockTime() throws Exception {
        asOwner(blockRequest()).andExpect(status().isCreated());
        reverseArrival(blockRequest(), delete(BLOCKS + "/" + FRIEND_ID), 201, 204);
        assertThat(blockTime("blocked_at")).isEqualTo(COMMAND_TIME.plusSeconds(3));
        assertThat(blockTime("released_at")).isNull();
        assertNoCurrentFollows();
        asOwner(put(followPath())).andExpect(status().isNotFound());
    }

    @Test
    void earlierUnblockRequestSerializesAfterLaterBlockAndUsesPostLockTime() throws Exception {
        reverseArrival(delete(BLOCKS + "/" + FRIEND_ID), blockRequest(), 204, 201);
        assertThat(blockTime("blocked_at")).isEqualTo(COMMAND_TIME.plusSeconds(2));
        assertThat(blockTime("released_at")).isEqualTo(COMMAND_TIME.plusSeconds(3));
        assertNoCurrentFollows();
        asOwner(put(followPath())).andExpect(status().isNoContent());
    }

    @Test
    void regressingClockCannotInvertReleaseOrRepeatedBlockPeriods() throws Exception {
        clock.set(COMMAND_TIME.plusSeconds(3));
        asOwner(blockRequest()).andExpect(status().isCreated());
        clock.set(COMMAND_TIME.plusSeconds(2));
        asOwner(delete(BLOCKS + "/" + FRIEND_ID)).andExpect(status().isNoContent());
        assertThat(blockTime("released_at")).isEqualTo(COMMAND_TIME.plusSeconds(3));
        clock.set(COMMAND_TIME.plusSeconds(1));
        asOwner(blockRequest()).andExpect(status().isCreated());
        assertThat(blockTime("blocked_at")).isEqualTo(COMMAND_TIME.plusSeconds(3));
        assertThat(blockTime("released_at")).isNull();
        asOwner(delete(BLOCKS + "/" + FRIEND_ID)).andExpect(status().isNoContent());
        assertThat(blockTime("released_at")).isEqualTo(COMMAND_TIME.plusSeconds(3));
        assertNoCurrentFollows();
    }

    private void reverseArrival(MockHttpServletRequestBuilder earlier,
            MockHttpServletRequestBuilder later, int earlierStatus, int laterStatus) throws Exception {
        // Pause only the first request immediately before its canonical pair lock.
        // The intercepted lock still uses the real transaction and a PostgreSQL row lock.
        CountDownLatch arrivedAtLock = new CountDownLatch(1);
        CountDownLatch permitLock = new CountDownLatch(1);
        AtomicReference<Thread> earlierThread = new AtomicReference<>();
        doAnswer(invocation -> {
            if (Thread.currentThread() == earlierThread.get()) {
                arrivedAtLock.countDown();
                assertThat(permitLock.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return Optional.ofNullable(entityManager.find(Student.class, OWNER_ID, LockModeType.PESSIMISTIC_WRITE));
        }).when(students).lockById(OWNER_ID);
        clock.set(COMMAND_TIME.plusSeconds(1));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> first = executor.submit(() -> {
                earlierThread.set(Thread.currentThread());
                return asOwner(earlier).andReturn().getResponse().getStatus();
            });
            try {
                assertThat(arrivedAtLock.await(10, TimeUnit.SECONDS)).isTrue();
                clock.set(COMMAND_TIME.plusSeconds(2));
                assertThat(asOwner(later).andReturn().getResponse().getStatus()).isEqualTo(laterStatus);
                clock.set(COMMAND_TIME.plusSeconds(3));
            } finally {
                permitLock.countDown();
            }
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(earlierStatus);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_block WHERE released_at < blocked_at",
                Long.class)).isZero();
    }

    private MockHttpServletRequestBuilder blockRequest() {
        return post(BLOCKS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":\"" + FRIEND_ID + "\"}");
    }

    private Instant blockTime(String column) {
        Timestamp value = jdbc.queryForObject("SELECT " + column
                + " FROM student_block WHERE blocker_id=? AND blocked_id=?", Timestamp.class,
                OWNER_ID, FRIEND_ID);
        return value == null ? null : value.toInstant();
    }

    private void assertNoCurrentFollows() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_follow WHERE ended_at IS NULL"
                + " AND ((source_id=? AND target_id=?) OR (source_id=? AND target_id=?))",
                Long.class, OWNER_ID, FRIEND_ID, FRIEND_ID, OWNER_ID)).isZero();
    }

    private String followPath() {
        return "/v1/academies/" + PRIMARY_ACADEMY_ID + "/following/" + FRIEND_ID;
    }
}
