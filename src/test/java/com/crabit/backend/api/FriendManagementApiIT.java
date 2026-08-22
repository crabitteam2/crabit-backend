package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.BLOCKED_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.STAFF_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class FriendManagementApiIT extends SharedCardApiIntegrationSupport {

	private static final String ACADEMY = "/v1/academies/" + PRIMARY_ACADEMY_ID;
	private static final String REQUESTS = ACADEMY + "/friend-requests";
	private static final String BLOCKS = "/v1/me/student-blocks";

	@Autowired
	private DataSource dataSource;

	@Test
	void requestAcceptanceBlockAndUnblockChangeSharedCardAccessWithoutRestoringFriendship() throws Exception {
		String created = asOwner(post(REQUESTS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + NONFRIEND_ID + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.counterpart.studentId").value(NONFRIEND_ID.toString()))
				.andReturn().getResponse().getContentAsString();
		String requestId = JsonPath.read(created, "$.friendRequestId");

		asOwner(get(REQUESTS + "/sent"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].friendRequestId").value(requestId));
		asToken(NONFRIEND_TOKEN, get(REQUESTS + "/received"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].counterpart.studentId").value(OWNER_ID.toString()));

		asToken(NONFRIEND_TOKEN, post(REQUESTS + "/" + requestId + "/acceptance"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.studentId").value(OWNER_ID.toString()));

		String friendsCard = cardIdForWish(LAPTOP_WISH_ID);
		getAs(NONFRIEND_TOKEN, friendsCard)
				.andExpect(status().isOk());

		asOwner(post(BLOCKS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + NONFRIEND_ID + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.studentId").value(NONFRIEND_ID.toString()));
		getAs(NONFRIEND_TOKEN, friendsCard)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHARED_CARD_NOT_FOUND"));

		asOwner(delete(BLOCKS + "/" + NONFRIEND_ID))
				.andExpect(status().isNoContent());
		getAs(NONFRIEND_TOKEN, friendsCard)
				.andExpect(status().isNotFound());

		asOwner(post(REQUESTS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + NONFRIEND_ID + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"));

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM friendship WHERE academy_id = ? AND ended_at IS NULL "
						+ "AND student_low_id = LEAST(?::uuid, ?::uuid) "
						+ "AND student_high_id = GREATEST(?::uuid, ?::uuid)",
				Long.class, PRIMARY_ACADEMY_ID, OWNER_ID, NONFRIEND_ID, OWNER_ID, NONFRIEND_ID))
				.isZero();
	}

	@Test
	void principalScopeAndPrivacyNormalizeForbiddenAcademyBlockedAndSelfTargets() throws Exception {
		asToken(STAFF_TOKEN, get(ACADEMY + "/friends"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		asOwner(get("/v1/academies/" + OTHER_ACADEMY_ID + "/friends"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ACADEMY_NOT_FOUND"));

		asOwner(post(REQUESTS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + BLOCKED_ID + "\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("STUDENT_NOT_FOUND"));

		asOwner(post(REQUESTS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + OWNER_ID + "\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SELF_RELATIONSHIP"));

		asOwner(get(ACADEMY + "/students").queryParam("nickname", "학원"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].studentId").value(NONFRIEND_ID.toString()))
				.andExpect(jsonPath("$.items[0].realName").doesNotExist())
				.andExpect(jsonPath("$.nextCursor").value((Object) null));
	}

	@Test
	void sendFriendRequestTreatsUnsupportedMediaTypeAsMalformedRequest() throws Exception {
		asOwner(post(REQUESTS)
				.contentType(MediaType.TEXT_PLAIN)
				.content("studentId=" + NONFRIEND_ID))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
				.andExpect(jsonPath("$.error.message").value("The request is malformed."));

		assertThat(pendingRequestCount()).isZero();
	}

	@Test
	void blockStudentTreatsUnsupportedMediaTypeAsMalformedRequest() throws Exception {
		asOwner(post(BLOCKS)
				.contentType(MediaType.TEXT_PLAIN)
				.content("studentId=" + NONFRIEND_ID))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
				.andExpect(jsonPath("$.error.message").value("The request is malformed."));

		assertThat(activeBlockCount(OWNER_ID, NONFRIEND_ID)).isZero();
	}

	@Test
	void sendFriendRequestRejectsClientSuppliedSenderId() throws Exception {
		asOwner(post(REQUESTS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"studentId":"%s","senderId":"%s"}
						""".formatted(NONFRIEND_ID, BLOCKED_ID)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));

		assertThat(pendingRequestCount()).isZero();
	}

	@Test
	void blockStudentRejectsClientSuppliedBlockerId() throws Exception {
		asOwner(post(BLOCKS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"studentId":"%s","blockerId":"%s"}
						""".formatted(NONFRIEND_ID, BLOCKED_ID)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));

		assertThat(activeBlockCount(OWNER_ID, NONFRIEND_ID)).isZero();
	}

	@Test
	void concurrentReverseRequestsProduceOnePendingRequestAndOneConflict() throws Exception {
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			Future<Integer> owner = executor.submit(() -> sendConcurrently(
					OWNER_TOKEN, NONFRIEND_ID.toString(), ready, start));
			Future<Integer> nonfriend = executor.submit(() -> sendConcurrently(
					NONFRIEND_TOKEN, OWNER_ID.toString(), ready, start));
			ready.await();
			start.countDown();

			assertThat(Set.of(owner.get(), nonfriend.get())).containsExactlyInAnyOrder(201, 409);
		}
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM friend_request WHERE academy_id = ? AND status = 'PENDING'",
				Long.class, PRIMARY_ACADEMY_ID)).isOne();
	}

	@Test
	void blockQueuedFirstSerializesAgainstSendAndPreventsAPendingRequest() throws Exception {
		RaceResult race = blockFirstAgainst(
				() -> block(OWNER_TOKEN, NONFRIEND_ID),
				() -> send(OWNER_TOKEN, NONFRIEND_ID));

		assertThat(race.block().status()).isEqualTo(201);
		assertThat(race.competing().status()).isEqualTo(404);
		assertThat(JsonPath.<String>read(race.competing().body(), "$.error.code"))
				.isEqualTo("STUDENT_NOT_FOUND");
		assertThat(pendingRequestCount()).isZero();
		assertThat(activeBlockCount(OWNER_ID, NONFRIEND_ID)).isOne();
	}

	@Test
	void blockQueuedFirstSerializesAgainstAcceptanceWithoutA5xx() throws Exception {
		String requestId = createPendingRequest();
		RaceResult race = blockFirstAgainst(
				() -> block(OWNER_TOKEN, NONFRIEND_ID),
				() -> requestMutation(NONFRIEND_TOKEN,
						REQUESTS + "/" + requestId + "/acceptance", false));

		assertThat(race.block().status()).isEqualTo(201);
		assertThat(race.competing().status()).isEqualTo(409);
		assertThat(pendingRequestCount()).isZero();
		assertThat(currentFriendshipCount()).isZero();
	}

	@Test
	void blockQueuedFirstSerializesAgainstCancellationWithoutA5xx() throws Exception {
		String requestId = createPendingRequest();
		RaceResult race = blockFirstAgainst(
				() -> block(NONFRIEND_TOKEN, OWNER_ID),
				() -> requestMutation(OWNER_TOKEN, REQUESTS + "/" + requestId, true));

		assertThat(race.block().status()).isEqualTo(201);
		assertThat(race.competing().status()).isEqualTo(409);
		assertThat(pendingRequestCount()).isZero();
		assertThat(activeBlockCount(NONFRIEND_ID, OWNER_ID)).isOne();
	}

	private int sendConcurrently(
			String token, String targetId, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		start.await();
		return asToken(token, post(REQUESTS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + targetId + "\"}"))
				.andReturn().getResponse().getStatus();
	}

	private RaceResult blockFirstAgainst(
			Callable<HttpResult> block, Callable<HttpResult> competing) throws Exception {
		try (Connection gate = dataSource.getConnection();
				ExecutorService executor = Executors.newFixedThreadPool(2)) {
			gate.setAutoCommit(false);
			try (PreparedStatement statement = gate.prepareStatement(
					"SELECT id FROM student WHERE id = ? FOR UPDATE")) {
				statement.setObject(1, OWNER_ID);
				statement.executeQuery().close();
			}
			Future<HttpResult> blocked = executor.submit(block);
			awaitDatabaseLockWaiters(1);
			Future<HttpResult> other = executor.submit(competing);
			awaitDatabaseLockWaiters(2);
			gate.commit();
			return new RaceResult(
					blocked.get(10, TimeUnit.SECONDS),
					other.get(10, TimeUnit.SECONDS));
		}
	}

	private void awaitDatabaseLockWaiters(long expected) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Long count = jdbc.queryForObject("""
					SELECT count(*) FROM pg_stat_activity
					WHERE datname = current_database()
					  AND pid <> pg_backend_pid()
					  AND wait_event_type = 'Lock'
					""", Long.class);
			if (count != null && count >= expected) {
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("Timed out waiting for " + expected + " PostgreSQL lock waiters");
	}

	private String createPendingRequest() throws Exception {
		String body = asOwner(post(REQUESTS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + NONFRIEND_ID + "\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.friendRequestId");
	}

	private HttpResult send(String token, UUID target) throws Exception {
		var response = asToken(token, post(REQUESTS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + target + "\"}"))
				.andReturn().getResponse();
		return new HttpResult(response.getStatus(), response.getContentAsString());
	}

	private HttpResult block(String token, UUID target) throws Exception {
		var response = asToken(token, post(BLOCKS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + target + "\"}"))
				.andReturn().getResponse();
		return new HttpResult(response.getStatus(), response.getContentAsString());
	}

	private HttpResult requestMutation(String token, String path, boolean cancellation) throws Exception {
		var response = asToken(token, cancellation ? delete(path) : post(path))
				.andReturn().getResponse();
		return new HttpResult(response.getStatus(), response.getContentAsString());
	}

	private long pendingRequestCount() {
		return jdbc.queryForObject(
				"SELECT count(*) FROM friend_request WHERE academy_id = ? AND status = 'PENDING'",
				Long.class, PRIMARY_ACADEMY_ID);
	}

	private long activeBlockCount(UUID blocker, UUID blocked) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM student_block WHERE blocker_id = ? AND blocked_id = ? AND released_at IS NULL",
				Long.class, blocker, blocked);
	}

	private long currentFriendshipCount() {
		return jdbc.queryForObject(
				"SELECT count(*) FROM friendship WHERE academy_id = ? AND ended_at IS NULL "
						+ "AND student_low_id = LEAST(?::uuid, ?::uuid) "
						+ "AND student_high_id = GREATEST(?::uuid, ?::uuid)",
				Long.class, PRIMARY_ACADEMY_ID, OWNER_ID, NONFRIEND_ID, OWNER_ID, NONFRIEND_ID);
	}

	private record HttpResult(int status, String body) {}
	private record RaceResult(HttpResult block, HttpResult competing) {}
}
