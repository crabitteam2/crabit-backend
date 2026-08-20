package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
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
		withdraw("normative-excess", 40_000, 1)
				.andExpect(jsonPath("$.wish.balanceAdjustmentInProgress").value(false));

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
