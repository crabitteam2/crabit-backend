package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.STAFF_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class WishOwnershipIT extends WishApiIntegrationSupport {

	@Test
	void exposesOwnerResourcesWhileHidingThemFromOtherStudentsAndAcademies() throws Exception {
		asOwner(get(WISHES_PATH + "/" + LAPTOP_WISH_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(LAPTOP_WISH_ID.toString()));

		asToken(FRIEND_TOKEN, get(WISHES_PATH + "/" + LAPTOP_WISH_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code")
						.value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));

		asToken(OTHER_ACADEMY_TOKEN, get(WISHES_PATH + "/" + LAPTOP_WISH_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code")
						.value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));
	}

	@Test
	void rejectsStaffAndUnknownCallersAndLeavesRejectedCrossOwnerCommandsUnchanged()
			throws Exception {
		asToken(STAFF_TOKEN, get(WISHES_PATH))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		mockMvc.perform(get(WISHES_PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));

		asToken(FRIEND_TOKEN, patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":0,\"purpose\":\"stolen\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code")
						.value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));

		assertThat(jdbc.queryForMap(
				"SELECT purpose, version FROM wish WHERE id = ?", LAPTOP_WISH_ID))
				.containsEntry("purpose", "노트북")
				.containsEntry("version", 0L);
	}
}
