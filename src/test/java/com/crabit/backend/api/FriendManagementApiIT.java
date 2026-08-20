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
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

class FriendManagementApiIT extends SharedCardApiIntegrationSupport {

	private static final String ACADEMY = "/v1/academies/" + PRIMARY_ACADEMY_ID;
	private static final String REQUESTS = ACADEMY + "/friend-requests";
	private static final String BLOCKS = "/v1/me/student-blocks";

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

	private int sendConcurrently(
			String token, String targetId, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		start.await();
		return asToken(token, post(REQUESTS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\":\"" + targetId + "\"}"))
				.andReturn().getResponse().getStatus();
	}
}
