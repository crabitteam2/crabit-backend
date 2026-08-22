package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.PRIMARY_ACADEMY_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.notification.DeterministicMismatchNotificationAdapter;
import com.crabit.backend.notification.MismatchNotification;
import com.crabit.backend.notification.MismatchNotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Aggregates the complete mismatch command matrix and case lifecycle. */
class WishMismatchE2EIT extends MismatchOperationMatrixIT {

	@Autowired
	private MismatchNotificationDispatcher notifications;

	@Autowired
	private DeterministicMismatchNotificationAdapter notificationAdapter;

	@Override
	@Test
	void blocksCreationDepositTransferAndEveryPatchButReplaysPriorSuccess()
			throws Exception {
		super.blocksCreationDepositTransferAndEveryPatchButReplaysPriorSuccess();
	}

	@Override
	@Test
	void allowsRefreshReadsWithdrawalCompletionZeroReturnDeleteAndAbandonment()
			throws Exception {
		super.allowsRefreshReadsWithdrawalCompletionZeroReturnDeleteAndAbandonment();
	}

	@BeforeEach
	void clearDeliveredNotifications() {
		notificationAdapter.clear();
	}

	@Test
	void partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain()
			throws Exception {
		refreshTo(700_000);
		assertThat(notifications.dispatchPending()).isOne();
		assertThat(notifications.dispatchPending()).isZero();

		withdraw("normative-partial", 20_000, 0)
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(true));
		asOwner(get("/v1/card-balance-accounts/{accountId}", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.actualCardBalance").value(700_000))
				.andExpect(jsonPath("$.ledgerAvailableBalance").value(-30_000))
				.andExpect(jsonPath("$.displayAvailableBalance").value(0))
				.andExpect(jsonPath("$.unresolvedShortage").value(30_000));
		withdraw("normative-excess", 40_000, 1)
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(false));
		asOwner(get("/v1/card-balance-accounts/{accountId}", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.actualCardBalance").value(700_000))
				.andExpect(jsonPath("$.ledgerAvailableBalance").value(10_000))
				.andExpect(jsonPath("$.displayAvailableBalance").value(10_000))
				.andExpect(jsonPath("$.unresolvedShortage").value(0))
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").value(false));
		assertThat(jdbc.queryForMap("""
				SELECT status, opened_shortage, resolution_event_id IS NOT NULL AS has_resolution
				FROM balance_adjustment_case WHERE account_id = ? ORDER BY opened_at LIMIT 1
				""", OWNER_ACCOUNT_ID))
				.containsEntry("status", "RESOLVED")
				.containsEntry("opened_shortage", 50_000L)
				.containsEntry("has_resolution", true);

		refreshTo(680_000);
		assertThat(notifications.dispatchPending()).isOne();
		assertThat(notifications.dispatchPending()).isZero();
		assertThat(notificationAdapter.deliveries()).hasSize(2).allSatisfy(notification -> {
			assertThat(notification.title()).isEqualTo(MismatchNotification.GENERIC_TITLE);
			assertThat(notification.body()).isEqualTo(MismatchNotification.GENERIC_BODY);
			assertThat(notification.title() + notification.body())
					.doesNotContain("10000", "10,000", "50000", "50,000");
		});
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM balance_adjustment_case
				WHERE account_id = ?
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(2L);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM mismatch_notification_outbox outbox
				JOIN balance_adjustment_case adjustment ON adjustment.id = outbox.adjustment_case_id
				WHERE adjustment.account_id = ?
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(2L);
	}

	@Test
	void completionDuringMismatchResolvesExactExcessAndReplacesCardAtomically()
			throws Exception {
		String cardId = jdbc.queryForObject(
				"SELECT id::text FROM shared_card WHERE wish_id = ?", String.class, CAMP_WISH_ID);
		refreshTo(700_000);

		asOwner(get("/v1/card-balance-accounts/{accountId}", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ledgerAvailableBalance").value(-50_000))
				.andExpect(jsonPath("$.displayAvailableBalance").value(0))
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").value(true));

		asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "normative-mismatch-completion")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("COMPLETED"))
				.andExpect(jsonPath("$.wish.amount").value(0))
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(false));

		asOwner(get("/v1/card-balance-accounts/{accountId}", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.actualCardBalance").value(700_000))
				.andExpect(jsonPath("$.ledgerAvailableBalance").value(450_000))
				.andExpect(jsonPath("$.displayAvailableBalance").value(450_000))
				.andExpect(jsonPath("$.unresolvedShortage").value(0))
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").value(false));

		asToken(NONFRIEND_TOKEN, get(
				"/v1/academies/{academyId}/shared-cards/{cardId}", PRIMARY_ACADEMY_ID, cardId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sharedCardId").value(cardId))
				.andExpect(jsonPath("$.kind").value("COMPLETION"));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM shared_card WHERE wish_id = ?", Long.class, CAMP_WISH_ID))
				.isOne();
		assertThat(jdbc.queryForMap("""
				SELECT id::text AS id, kind, visibility FROM shared_card WHERE wish_id = ?
				""", CAMP_WISH_ID))
				.containsEntry("id", cardId)
				.containsEntry("kind", "COMPLETION")
				.containsEntry("visibility", "ACADEMY");
		assertThat(jdbc.queryForMap("""
				SELECT event.event_type, event.account_delta, effect.wish_delta,
				       case_event.event_role
				FROM ledger_event event
				JOIN balance_adjustment_case_event case_event ON case_event.event_id = event.id
				JOIN ledger_wish_effect effect ON effect.event_id = event.id
				WHERE event.account_id = ? AND case_event.event_role = 'RESOLUTION'
				""", OWNER_ACCOUNT_ID))
				.containsEntry("event_type", "WISH_COMPLETION_RETURN")
				.containsEntry("account_delta", 0L)
				.containsEntry("wish_delta", -500_000L)
				.containsEntry("event_role", "RESOLUTION");
		assertThat(jdbc.queryForMap("""
				SELECT state, wish_amount, version FROM wish WHERE id = ?
				""", CAMP_WISH_ID))
				.containsEntry("state", "COMPLETED")
				.containsEntry("wish_amount", 0L)
				.containsEntry("version", 1L);
	}

	private void refreshTo(long balance) throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":" + balance + "}]");
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk());
	}

	private org.springframework.test.web.servlet.ResultActions withdraw(
			String key, long amount, long version) throws Exception {
		return asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/withdrawals")
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":%d,\"expectedVersion\":%d}"
						.formatted(amount, version)))
				.andExpect(status().isOk());
	}
}
