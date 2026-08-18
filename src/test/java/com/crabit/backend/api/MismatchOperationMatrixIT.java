package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class MismatchOperationMatrixIT extends WishApiIntegrationSupport {

	private static final String TRANSFERS =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/transfers";

	@Test
	void blocksCreationDepositTransferAndEveryPatchButReplaysPriorSuccess() throws Exception {
		String createBody = "{\"purpose\":\"조정 전 위시\",\"targetAmount\":100000}";
		MvcResult original = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "created-before-mismatch")
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(false))
				.andReturn();
		String destinationId = JsonPath.read(
				original.getResponse().getContentAsString(), "$.wish.id");

		openMismatch(700_000);

		MvcResult replay = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "created-before-mismatch")
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(false))
				.andReturn();
		assertThat(replay.getResponse().getContentAsString())
				.isEqualTo(original.getResponse().getContentAsString());

		asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "blocked-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"purpose\":\"차단 생성\",\"targetAmount\":1000}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BALANCE_MISMATCH_LOCKED"));

		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/deposits")
				.header("Idempotency-Key", "blocked-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":1,\"expectedVersion\":0}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BALANCE_MISMATCH_LOCKED"));
		mockMvc.perform(get("/e2e/card-balance-accounts/{accountId}/balance-scenario",
				OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps.length()").value(1));

		asOwner(post(TRANSFERS)
				.header("Idempotency-Key", "blocked-transfer")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sourceWishId":"%s","destinationWishId":"%s","amount":1,
						"sourceExpectedVersion":0,"destinationExpectedVersion":0}
						""".formatted(LAPTOP_WISH_ID, destinationId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BALANCE_MISMATCH_LOCKED"));

		for (String patchBody : new String[] {
				"{\"expectedVersion\":0,\"purpose\":\"차단 수정\"}",
				"{\"expectedVersion\":0,\"visibility\":\"PRIVATE\"}"}) {
			asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
					.contentType("application/merge-patch+json")
					.content(patchBody))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.error.code").value("BALANCE_MISMATCH_LOCKED"));
		}

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM wish WHERE account_id = ? AND deleted_at IS NULL",
				Long.class, OWNER_ACCOUNT_ID)).isEqualTo(3L);
		assertThat(jdbc.queryForMap(
				"SELECT purpose, visibility, version FROM wish WHERE id = ?", LAPTOP_WISH_ID))
				.containsEntry("purpose", "노트북")
				.containsEntry("visibility", "FRIENDS")
				.containsEntry("version", 0L);
	}

	@Test
	void allowsRefreshReadsWithdrawalCompletionZeroReturnDeleteAndAbandonment() throws Exception {
		String emptyWishId = createWish("zero-return-delete", "빈 위시", 1000);
		openMismatch(0);

		setBalanceScenario("[{\"type\":\"FAILURE\"}]");
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("BALANCE_SYNC_FAILED"));

		asOwner(get("/v1/me/card-balance-accounts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].actualCardBalance").value(0))
				.andExpect(jsonPath("$.items[0].ledgerAvailableBalance").value(-750_000))
				.andExpect(jsonPath("$.items[0].lastRefreshStatus").value("FAILED"))
				.andExpect(jsonPath("$.items[0].balanceAdjustmentInProgress").value(true));
		asOwner(get(WISHES_PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].balanceAdjustmentInProgress")
						.value(org.hamcrest.Matchers.everyItem(
								org.hamcrest.Matchers.is(true))));
		asOwner(get(WISHES_PATH + "/" + LAPTOP_WISH_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").value(true));

		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/withdrawals")
				.header("Idempotency-Key", "allowed-withdraw")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":1,\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(true));

		asOwner(delete(WISHES_PATH + "/" + emptyWishId)
				.header(HttpHeaders.IF_MATCH, "0")
				.header("Idempotency-Key", "allowed-zero-return-delete"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventId").value((Object) null))
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(true));

		asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "allowed-completion")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(true));

		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/abandonment")
				.header("Idempotency-Key", "allowed-abandonment")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(false));

		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM balance_adjustment_case
				WHERE account_id = ? AND status = 'OPEN'
				""", Long.class, OWNER_ACCOUNT_ID)).isZero();
	}

	private void openMismatch(long balance) throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":" + balance + "}]");
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.account.balanceAdjustmentInProgress").value(true));
	}
}
