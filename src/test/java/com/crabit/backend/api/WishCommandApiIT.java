package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class WishCommandApiIT extends WishApiIntegrationSupport {

	@Test
	void listsStableDescendingPagesAndFiltersByLifecycleState() throws Exception {
		clock.set(COMMAND_TIME.plusSeconds(1));
		createWish("page-one", "first new", 1000);
		clock.set(COMMAND_TIME.plusSeconds(2));
		createWish("page-two", "second new", 1000);
		clock.set(COMMAND_TIME.plusSeconds(3));
		createWish("page-three", "third new", 1000);

		MvcResult firstPage = asOwner(get(WISHES_PATH).queryParam("limit", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].purpose").value("third new"))
				.andExpect(jsonPath("$.items[1].purpose").value("second new"))
				.andExpect(jsonPath("$.nextCursor").isString())
				.andReturn();
		String cursor = json(firstPage.getResponse().getContentAsString(), "$.nextCursor");

		MvcResult secondPage = asOwner(get(WISHES_PATH)
				.queryParam("limit", "2")
				.queryParam("cursor", cursor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].purpose").value("first new"))
				.andReturn();

		List<String> firstIds = json(firstPage.getResponse().getContentAsString(), "$.items[*].id");
		List<String> secondIds = json(secondPage.getResponse().getContentAsString(), "$.items[*].id");
		assertThat(secondIds).doesNotContainAnyElementsOf(firstIds);

		asOwner(get(WISHES_PATH).queryParam("state", "AMOUNT_REACHED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].state").value("AMOUNT_REACHED"));
	}

	@Test
	void traversesEqualCreatedAtUuidBoundaryWithoutSkippingWishes() throws Exception {
		UUID higherPostgresUuid = UUID.fromString("ffffffff-ffff-4fff-8000-000000000001");
		UUID lowerPostgresUuid = UUID.fromString("00000000-0000-4000-8000-000000000001");
		var createdAt = COMMAND_TIME.plusSeconds(10);
		jdbc.update("""
				INSERT INTO wish
				(id, account_id, academy_id, purpose, target_amount, wish_amount, state,
				 visibility, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, 1000, 0, 'IN_PROGRESS', 'PRIVATE', ?, ?, 0)
				""", higherPostgresUuid, OWNER_ACCOUNT_ID, PRIMARY_ACADEMY_ID,
				"higher PostgreSQL UUID", Timestamp.from(createdAt), Timestamp.from(createdAt));
		jdbc.update("""
				INSERT INTO wish
				(id, account_id, academy_id, purpose, target_amount, wish_amount, state,
				 visibility, created_at, updated_at, version)
				VALUES (?, ?, ?, ?, 1000, 0, 'IN_PROGRESS', 'PRIVATE', ?, ?, 0)
				""", lowerPostgresUuid, OWNER_ACCOUNT_ID, PRIMARY_ACADEMY_ID,
				"lower PostgreSQL UUID", Timestamp.from(createdAt), Timestamp.from(createdAt));

		MvcResult firstPage = asOwner(get(WISHES_PATH).queryParam("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(higherPostgresUuid.toString()))
				.andExpect(jsonPath("$.nextCursor").isString())
				.andReturn();
		String cursor = json(firstPage.getResponse().getContentAsString(), "$.nextCursor");

		MvcResult secondPage = asOwner(get(WISHES_PATH)
				.queryParam("limit", "1")
				.queryParam("cursor", cursor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(lowerPostgresUuid.toString()))
				.andReturn();

		List<String> traversedIds = List.of(
				json(firstPage.getResponse().getContentAsString(), "$.items[0].id"),
				json(secondPage.getResponse().getContentAsString(), "$.items[0].id"));
		assertThat(traversedIds)
				.containsExactly(higherPostgresUuid.toString(), lowerPostgresUuid.toString())
				.doesNotHaveDuplicates();
	}

	@Test
	void rejectsWrongPatchMediaTypeMalformedVersionAndInvalidPurposeWithoutMutation()
			throws Exception {
		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0,\"purpose\":\"new\"}"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));

		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"purpose\":\"new\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));

		asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "bad-purpose")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"purpose\":\"line\\nbreak\",\"targetAmount\":1000}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error.code").value("INVALID_PURPOSE"));

		assertThat(jdbc.queryForObject(
				"SELECT purpose FROM wish WHERE id = ?", String.class, LAPTOP_WISH_ID))
				.isEqualTo("노트북");
	}
}
