package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.BLOCKED_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Aggregates the owner-only command/query coverage under the normative suite ID. */
class WishAuthorizationE2EIT extends WishOwnershipIT {

	@Test
	void unknownBlockedNonfriendAndOtherAcademyPrincipalsCannotDiscoverOwnerState()
			throws Exception {
		mockMvc.perform(get(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer unknown-seed-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));

		for (String token : new String[] {
				NONFRIEND_TOKEN, BLOCKED_TOKEN, OTHER_ACADEMY_TOKEN
		}) {
			asToken(token, get(WISHES_PATH + "/" + LAPTOP_WISH_ID))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code")
							.value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));
		}

		assertThat(jdbc.queryForMap(
				"SELECT purpose, wish_amount, version FROM wish WHERE id = ?", LAPTOP_WISH_ID))
				.containsEntry("purpose", "노트북")
				.containsEntry("wish_amount", 250_000L)
				.containsEntry("version", 0L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_event", Long.class)).isZero();
	}
}
