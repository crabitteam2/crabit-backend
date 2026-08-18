package com.crabit.backend.notification;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.api.WishApiIntegrationSupport;
import com.crabit.backend.wish.MismatchNotificationOutbox;
import com.crabit.backend.wish.MismatchNotificationOutboxRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MismatchNotificationIT extends WishApiIntegrationSupport {

	@Autowired
	private MismatchNotificationDispatcher dispatcher;

	@Autowired
	private DeterministicMismatchNotificationAdapter adapter;

	@Autowired
	private MismatchNotificationOutboxRepository outbox;

	@BeforeEach
	void resetNotificationAdapter() {
		adapter.clear();
	}

	@Test
	void publishesOneGenericNotificationPerCaseAndRepeatedDispatchIsIdempotent()
			throws Exception {
		UUID adjustmentCaseId = openMismatch();
		UUID outboxId = outboxId(adjustmentCaseId);

		assertThat(dispatcher.dispatchPending()).isOne();
		assertThat(dispatcher.dispatchPending()).isZero();

		assertThat(adapter.deliveries()).singleElement().satisfies(notification -> {
			assertThat(notification.idempotencyKey()).isEqualTo(adjustmentCaseId);
			assertThat(notification.cardBalanceAccountId()).isEqualTo(OWNER_ACCOUNT_ID);
			assertThat(notification.title()).isEqualTo(MismatchNotification.GENERIC_TITLE);
			assertThat(notification.body()).isEqualTo(MismatchNotification.GENERIC_BODY);
			assertThat(notification.title()).doesNotContain("50000", "50,000");
			assertThat(notification.body()).doesNotContain("50000", "50,000");
		});
		assertThat(outbox.findById(outboxId))
				.get()
				.extracting(MismatchNotificationOutbox::publishedAt)
				.isEqualTo(COMMAND_TIME);
	}

	@Test
	void failedDeliveryRemainsUnpublishedAndRetriesTheSameIdempotencyKey()
			throws Exception {
		UUID adjustmentCaseId = openMismatch();
		UUID outboxId = outboxId(adjustmentCaseId);
		adapter.enqueueFailure(adjustmentCaseId);

		assertThatThrownBy(dispatcher::dispatchPending)
				.isInstanceOf(MismatchNotificationDeliveryException.class);
		assertThat(adapter.deliveries()).isEmpty();
		assertThat(outbox.findById(outboxId))
				.get()
				.extracting(MismatchNotificationOutbox::publishedAt)
				.isNull();

		assertThat(dispatcher.dispatchPending()).isOne();
		assertThat(adapter.deliveries())
				.singleElement()
				.extracting(MismatchNotification::idempotencyKey)
				.isEqualTo(adjustmentCaseId);
		assertThat(outbox.findById(outboxId))
				.get()
				.extracting(MismatchNotificationOutbox::publishedAt)
				.isEqualTo(COMMAND_TIME);
	}

	private UUID openMismatch() throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":700000}]");
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk());
		return jdbc.queryForObject("""
				SELECT id FROM balance_adjustment_case
				WHERE account_id = ? AND status = 'OPEN'
				""", UUID.class, OWNER_ACCOUNT_ID);
	}

	private UUID outboxId(UUID adjustmentCaseId) {
		return jdbc.queryForObject(
				"SELECT id FROM mismatch_notification_outbox WHERE adjustment_case_id = ?",
				UUID.class, adjustmentCaseId);
	}
}
