package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.STAFF_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;

class RepresentativeWishApiIT extends WishApiIntegrationSupport {

	private static final String REPRESENTATIVE_PATH =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/representative-wish";

	@Test
	void returnsNoContentWhenTheOwnedAccountHasNoRepresentativeWish() throws Exception {
		jdbc.update("DELETE FROM representative_wish_selection WHERE account_id = ?",
				OWNER_ACCOUNT_ID);
		asOwner(get(REPRESENTATIVE_PATH))
				.andExpect(status().isNoContent());
	}

	@Test
	void keepsTheExistingRepresentativeWhenAnotherActiveWishIsCreated() throws Exception {
		asOwner(get(REPRESENTATIVE_PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(LAPTOP_WISH_ID.toString()));

		createWish("preserve-representative", "추가 위시", 100_000);

		asOwner(get(REPRESENTATIVE_PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(LAPTOP_WISH_ID.toString()));
	}

	@Test
	void selectsAnActiveWishAndReturnsItDirectlyWithoutMutatingTheWish() throws Exception {
		asOwner(put(REPRESENTATIVE_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"wishId\":\"" + LAPTOP_WISH_ID + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(LAPTOP_WISH_ID.toString()))
				.andExpect(jsonPath("$.version").value(0));

		asOwner(get(REPRESENTATIVE_PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(LAPTOP_WISH_ID.toString()))
				.andExpect(jsonPath("$.version").value(0));
	}

	@Test
	void atomicallyReplacesTheSelectionAndTreatsReselectionAsANoop() throws Exception {
		var before = jdbc.queryForMap(
				"SELECT updated_at, version FROM wish WHERE id = ?", CAMP_WISH_ID);

		select(LAPTOP_WISH_ID)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(LAPTOP_WISH_ID.toString()));
		select(CAMP_WISH_ID)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(CAMP_WISH_ID.toString()));
		select(CAMP_WISH_ID)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(CAMP_WISH_ID.toString()));

		assertThat(jdbc.queryForObject(
				"SELECT wish_id FROM representative_wish_selection WHERE account_id = ?",
				String.class, OWNER_ACCOUNT_ID)).isEqualTo(CAMP_WISH_ID.toString());
		assertThat(jdbc.queryForMap(
				"SELECT updated_at, version FROM wish WHERE id = ?", CAMP_WISH_ID))
				.isEqualTo(before);
	}

	@Test
	void reselectsTheOnlyRemainingActiveWishWhenTheRepresentativeCompletes()
			throws Exception {
		select(CAMP_WISH_ID).andExpect(status().isOk());

		asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "representative-completion")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk());

		asOwner(get(REPRESENTATIVE_PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(LAPTOP_WISH_ID.toString()));
	}

	@Test
	void selectsANewWishWhenItBecomesTheOnlyActiveWish() throws Exception {
		asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "singleton-completion")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk());
		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/abandonment")
				.header("Idempotency-Key", "singleton-abandonment")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk());
		asOwner(get(REPRESENTATIVE_PATH)).andExpect(status().isNoContent());

		String onlyActiveWishId = createWish("singleton-create", "유일한 위시", 100_000);

		asOwner(get(REPRESENTATIVE_PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(onlyActiveWishId));
	}

	@Test
	void enforcesAuthenticationHidingValidationAndEligibilityBoundaries() throws Exception {
		mockMvc.perform(get(REPRESENTATIVE_PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
		asToken(STAFF_TOKEN, get(REPRESENTATIVE_PATH))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		asToken(FRIEND_TOKEN, get(REPRESENTATIVE_PATH))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code")
						.value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));

		asOwner(put(REPRESENTATIVE_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"wishId\":\"not-a-uuid\",\"extra\":true}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
		asOwner(put(REPRESENTATIVE_PATH)
				.contentType(MediaType.TEXT_PLAIN)
				.content("{\"wishId\":\"" + LAPTOP_WISH_ID + "\"}"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
		select(java.util.UUID.randomUUID())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("WISH_NOT_FOUND"));

		asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "terminal-selection-setup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk());
		select(CAMP_WISH_ID)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
	}

	@Test
	void allowsPrivateSelectionDuringBalanceMismatchWithoutDomainSideEffects()
			throws Exception {
		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":0,\"visibility\":\"PRIVATE\"}"))
				.andExpect(status().isOk());
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":700000}]");
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes",
				OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.account.balanceAdjustmentInProgress").value(true));
		long ledgerEvents = jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ?",
				Long.class, OWNER_ACCOUNT_ID);
		long notifications = jdbc.queryForObject(
				"SELECT count(*) FROM mismatch_notification_outbox", Long.class);

		select(LAPTOP_WISH_ID)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").value(true))
				.andExpect(jsonPath("$.version").value(1));

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isEqualTo(ledgerEvents);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM mismatch_notification_outbox", Long.class))
				.isEqualTo(notifications);
	}

	@Test
	void clearsADeletedRepresentativeWhenMultipleActiveWishesRemain() throws Exception {
		createWish("representative-delete-third", "세 번째 위시", 100_000);

		asOwner(delete(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.header(HttpHeaders.IF_MATCH, "0")
				.header("Idempotency-Key", "delete-current-representative"))
				.andExpect(status().isOk());

		asOwner(get(REPRESENTATIVE_PATH)).andExpect(status().isNoContent());
	}

	private org.springframework.test.web.servlet.ResultActions select(java.util.UUID wishId)
			throws Exception {
		return asOwner(put(REPRESENTATIVE_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"wishId\":\"" + wishId + "\"}"));
	}
}
