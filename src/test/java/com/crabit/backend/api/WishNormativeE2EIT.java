package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.e2e.SeedFixtureCatalog;
import com.crabit.backend.balance.DailyBalanceRefreshJob;
import com.crabit.backend.notification.DeterministicMismatchNotificationAdapter;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Normative end-to-end acceptance suite. Inherited scenarios keep the immutable
 * account and Wish history projections tied to the same PostgreSQL ledger facts.
 */
class WishNormativeE2EIT extends FundMovementHistoryIT {

	@Autowired
	private DeterministicMismatchNotificationAdapter notificationAdapter;

	@Autowired
	private DailyBalanceRefreshJob dailyBalanceRefreshJob;

	@Override
	@Test
	void projectsEveryLedgerKindWithSignedAvailabilityAndOneTransferItem() throws Exception {
		super.projectsEveryLedgerKindWithSignedAvailabilityAndOneTransferItem();
	}

	@Override
	@Test
	void projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage() throws Exception {
		super.projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage();
	}

	@Test
	void uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent()
			throws Exception {
		String createBody = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "normative-lifecycle-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"purpose":"\u00a0Cafe\u0301 졸업 여행\u00a0","targetAmount":300000,
						 "targetDate":"2027-02-28"}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.wish.purpose").value("Café 졸업 여행"))
				.andExpect(jsonPath("$.wish.amount").value(0))
				.andExpect(jsonPath("$.wish.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.wish.targetDate").value("2027-02-28"))
				.andReturn().getResponse().getContentAsString();
		String wishId = json(createBody, "$.wish.id");
		assertThat(jdbc.queryForMap("""
				SELECT account_id, academy_id, purpose, target_amount, wish_amount, state,
				       visibility, created_at, target_date, version
				FROM wish WHERE id = ?::uuid
				""", wishId))
				.containsEntry("account_id", OWNER_ACCOUNT_ID)
				.containsEntry("academy_id", SeedFixtureCatalog.PRIMARY_ACADEMY_ID)
				.containsEntry("purpose", "Café 졸업 여행")
				.containsEntry("target_amount", 300_000L)
				.containsEntry("wish_amount", 0L)
				.containsEntry("state", "IN_PROGRESS")
				.containsEntry("visibility", "PRIVATE")
				.containsEntry("created_at", Timestamp.from(COMMAND_TIME))
				.containsEntry("target_date", java.sql.Date.valueOf("2027-02-28"))
				.containsEntry("version", 0L);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");

		clock.set(COMMAND_TIME.plusSeconds(1));
		asOwner(post(WISHES_PATH + "/" + wishId + "/deposits")
				.header("Idempotency-Key", "normative-lifecycle-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":300000,\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(300_000))
				.andExpect(jsonPath("$.wish.state").value("AMOUNT_REACHED"))
				.andExpect(jsonPath("$.wish.version").value(1));

		clock.set(COMMAND_TIME.plusSeconds(2));
		asOwner(post(WISHES_PATH + "/" + wishId + "/withdrawals")
				.header("Idempotency-Key", "normative-lifecycle-withdrawal")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":50000,\"expectedVersion\":1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(250_000))
				.andExpect(jsonPath("$.wish.state").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.wish.version").value(2));

		clock.set(COMMAND_TIME.plusSeconds(3));
		asOwner(patch(WISHES_PATH + "/" + wishId)
				.contentType("application/merge-patch+json")
				.content("""
						{"expectedVersion":2,"targetAmount":250000,"visibility":"FOLLOWERS"}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.targetAmount").value(250_000))
				.andExpect(jsonPath("$.wish.state").value("AMOUNT_REACHED"))
				.andExpect(jsonPath("$.wish.version").value(3));

		String progressCardId = jdbc.queryForObject(
				"SELECT id::text FROM shared_card WHERE wish_id = ?::uuid", String.class, wishId);
		assertThat(jdbc.queryForObject(
				"SELECT kind FROM shared_card WHERE id = ?::uuid", String.class, progressCardId))
				.isEqualTo("PROGRESS");

		clock.set(COMMAND_TIME.plusSeconds(4));
		asOwner(post(WISHES_PATH + "/" + wishId + "/completion")
				.header("Idempotency-Key", "normative-lifecycle-completion")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":3}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(0))
				.andExpect(jsonPath("$.wish.state").value("COMPLETED"))
				.andExpect(jsonPath("$.wish.version").value(4));

		asOwner(get("/v1/card-balance-accounts/{accountId}", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.actualCardBalance").value(2_000_000))
				.andExpect(jsonPath("$.ledgerAvailableBalance").value(1_250_000))
				.andExpect(jsonPath("$.displayAvailableBalance").value(1_250_000))
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").value(false));

		assertThat(jdbc.queryForMap("""
				SELECT state, wish_amount, target_amount, visibility, version,
				       completed_at IS NOT NULL AS completed
				FROM wish WHERE id = ?::uuid
				""", wishId))
				.containsEntry("state", "COMPLETED")
				.containsEntry("wish_amount", 0L)
				.containsEntry("target_amount", 250_000L)
				.containsEntry("visibility", "FOLLOWERS")
				.containsEntry("version", 4L)
				.containsEntry("completed", true);
		assertThat(jdbc.queryForObject(
				"SELECT id::text FROM shared_card WHERE wish_id = ?::uuid", String.class, wishId))
				.isEqualTo(progressCardId);
		assertThat(jdbc.queryForObject(
				"SELECT kind FROM shared_card WHERE id = ?::uuid", String.class, progressCardId))
				.isEqualTo("COMPLETION");
		assertThat(jdbc.queryForList("""
				SELECT event_type FROM ledger_event event
				WHERE account_id = ? AND EXISTS (
				    SELECT 1 FROM ledger_wish_effect effect
				    WHERE effect.event_id = event.id AND effect.wish_id = ?::uuid)
				ORDER BY occurred_at, event_type
				""", String.class, OWNER_ACCOUNT_ID, wishId))
				.containsExactly("WISH_DEPOSIT", "WISH_WITHDRAWAL", "WISH_COMPLETION_RETURN");
		assertThat(jdbc.queryForList("""
				SELECT effect.wish_delta FROM ledger_wish_effect effect
				JOIN ledger_event event ON event.id = effect.event_id
				WHERE effect.wish_id = ?::uuid
				ORDER BY event.occurred_at, event.event_type
				""", Long.class, wishId))
				.containsExactly(300_000L, -50_000L, -250_000L);
	}

	@Test
	void terminalPurposePatchesAreRejectedWithoutSideEffects() throws Exception {
		prepareTerminalWishes();

		assertTerminalBodyPatchRejected(
				SeedFixtureCatalog.CAMP_WISH_ID, "\"purpose\":\"금지된 수정\"");
		assertTerminalBodyPatchRejected(
				SeedFixtureCatalog.LAPTOP_WISH_ID, "\"purpose\":\"금지된 수정\"");
	}

	@Test
	void terminalTargetAmountPatchesAreRejectedWithoutSideEffects() throws Exception {
		prepareTerminalWishes();

		assertTerminalBodyPatchRejected(
				SeedFixtureCatalog.CAMP_WISH_ID, "\"targetAmount\":900000");
		assertTerminalBodyPatchRejected(
				SeedFixtureCatalog.LAPTOP_WISH_ID, "\"targetAmount\":900000");
	}

	@Test
	void terminalTargetDatePatchesAreRejectedWithoutSideEffects() throws Exception {
		prepareTerminalWishes();

		assertTerminalBodyPatchRejected(
				SeedFixtureCatalog.CAMP_WISH_ID, "\"targetDate\":\"2028-01-01\"");
		assertTerminalBodyPatchRejected(
				SeedFixtureCatalog.LAPTOP_WISH_ID, "\"targetDate\":\"2028-01-01\"");
	}

	@Test
	void terminalVisibilityPatchesApplyOnlyMetadataAndExactCardEffects() throws Exception {
		prepareTerminalWishes();
		List<Map<String, Object>> ledgerBefore = ledgerRows();
		List<Map<String, Object>> completedCardBefore = sharedCardRows(
				SeedFixtureCatalog.CAMP_WISH_ID);
		assertThat(completedCardBefore).singleElement().satisfies(card -> assertThat(card)
				.containsEntry("kind", "COMPLETION")
				.containsEntry("visibility", "ACADEMY"));
		assertThat(sharedCardRows(SeedFixtureCatalog.LAPTOP_WISH_ID)).isEmpty();

		clock.set(COMMAND_TIME.plusSeconds(1));
		asOwner(patch(WISHES_PATH + "/" + SeedFixtureCatalog.CAMP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":1,\"visibility\":\"FOLLOWERS\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andExpect(jsonPath("$.eventId").value((Object) null))
				.andExpect(jsonPath("$.wish.state").value("COMPLETED"))
				.andExpect(jsonPath("$.wish.visibility").value("FOLLOWERS"))
				.andExpect(jsonPath("$.wish.version").value(2));
		assertThat(jdbc.queryForMap("""
				SELECT state, visibility, updated_at, version FROM wish WHERE id = ?
				""", SeedFixtureCatalog.CAMP_WISH_ID))
				.containsEntry("state", "COMPLETED")
				.containsEntry("visibility", "FOLLOWERS")
				.containsEntry("updated_at", Timestamp.from(COMMAND_TIME.plusSeconds(1)))
				.containsEntry("version", 2L);
		assertThat(sharedCardRows(SeedFixtureCatalog.CAMP_WISH_ID)).singleElement()
				.satisfies(card -> assertThat(card)
				.containsEntry("id", completedCardBefore.getFirst().get("id"))
				.containsEntry("kind", "COMPLETION")
				.containsEntry("visibility", "FOLLOWERS")
				.containsEntry("updated_at", Timestamp.from(COMMAND_TIME.plusSeconds(1))));
		assertThat(ledgerRows()).isEqualTo(ledgerBefore);

		clock.set(COMMAND_TIME.plusSeconds(2));
		asOwner(patch(WISHES_PATH + "/" + SeedFixtureCatalog.CAMP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":2,\"visibility\":\"PRIVATE\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andExpect(jsonPath("$.eventId").value((Object) null))
				.andExpect(jsonPath("$.wish.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.wish.version").value(3));
		assertThat(sharedCardRows(SeedFixtureCatalog.CAMP_WISH_ID)).isEmpty();
		assertThat(ledgerRows()).isEqualTo(ledgerBefore);

		clock.set(COMMAND_TIME.plusSeconds(3));
		asOwner(patch(WISHES_PATH + "/" + SeedFixtureCatalog.LAPTOP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":1,\"visibility\":\"ACADEMY\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andExpect(jsonPath("$.eventId").value((Object) null))
				.andExpect(jsonPath("$.wish.state").value("ABANDONED"))
				.andExpect(jsonPath("$.wish.visibility").value("ACADEMY"))
				.andExpect(jsonPath("$.wish.version").value(2));
		assertThat(jdbc.queryForMap("""
				SELECT state, visibility, updated_at, version FROM wish WHERE id = ?
				""", SeedFixtureCatalog.LAPTOP_WISH_ID))
				.containsEntry("state", "ABANDONED")
				.containsEntry("visibility", "ACADEMY")
				.containsEntry("updated_at", Timestamp.from(COMMAND_TIME.plusSeconds(3)))
				.containsEntry("version", 2L);
		assertThat(sharedCardRows(SeedFixtureCatalog.LAPTOP_WISH_ID)).isEmpty();
		assertThat(ledgerRows()).isEqualTo(ledgerBefore);
	}

	@Test
	void terminalStatesRejectReversalWithoutSideEffects() throws Exception {
		prepareTerminalWishes();
		Map<String, Object> completedBefore = wishRow(
				SeedFixtureCatalog.CAMP_WISH_ID.toString());
		Map<String, Object> abandonedBefore = wishRow(
				SeedFixtureCatalog.LAPTOP_WISH_ID.toString());
		List<Map<String, Object>> ledgerBefore = ledgerRows();
		List<Map<String, Object>> completedCardBefore = sharedCardRows(
				SeedFixtureCatalog.CAMP_WISH_ID);

		asOwner(post(WISHES_PATH + "/" + SeedFixtureCatalog.CAMP_WISH_ID + "/abandonment")
				.header("Idempotency-Key", "terminal-proof-completed-to-abandoned")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":1}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
		asOwner(post(WISHES_PATH + "/" + SeedFixtureCatalog.LAPTOP_WISH_ID + "/completion")
				.header("Idempotency-Key", "terminal-proof-abandoned-to-completed")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":1}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));

		assertThat(wishRow(SeedFixtureCatalog.CAMP_WISH_ID.toString()))
				.isEqualTo(completedBefore);
		assertThat(wishRow(SeedFixtureCatalog.LAPTOP_WISH_ID.toString()))
				.isEqualTo(abandonedBefore);
		assertThat(ledgerRows()).isEqualTo(ledgerBefore);
		assertThat(sharedCardRows(SeedFixtureCatalog.CAMP_WISH_ID))
				.isEqualTo(completedCardBefore);
		assertThat(sharedCardRows(SeedFixtureCatalog.LAPTOP_WISH_ID)).isEmpty();
	}

	@Test
	void passingTargetDateDoesNotAutomaticallyTransitionLifecycleState() throws Exception {
		String body = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "target-date-non-transition")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"purpose":"기한이 지나도 유지","targetAmount":100000,
						 "targetDate":"2026-08-19"}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.wish.state").value("IN_PROGRESS"))
				.andReturn().getResponse().getContentAsString();
		String wishId = json(body, "$.wish.id");

		clock.set(COMMAND_TIME.plus(Duration.ofDays(2)));
		asOwner(get(WISHES_PATH + "/" + wishId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targetDate").value("2026-08-19"))
				.andExpect(jsonPath("$.state").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.version").value(0));

		assertThat(jdbc.queryForMap("""
				SELECT state, target_date, version FROM wish WHERE id = ?::uuid
				""", wishId))
				.containsEntry("state", "IN_PROGRESS")
				.containsEntry("target_date", java.sql.Date.valueOf("2026-08-19"))
				.containsEntry("version", 0L);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ledger_event WHERE account_id = ?
				""", Long.class, OWNER_ACCOUNT_ID)).isZero();
	}

	@Test
	void deletingEveryNondeletedLifecycleStateReturnsFundsAndRemovesProjections()
			throws Exception {
		for (String lifecycleState : List.of(
				"IN_PROGRESS", "AMOUNT_REACHED", "COMPLETED", "ABANDONED")) {
			resetFixture();
			String key = lifecycleState.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
			String purpose = lifecycleState + " 삭제";
			String wishId = createWish("delete-" + key + "-create", purpose, 100_000);
			asOwner(patch(WISHES_PATH + "/" + wishId)
					.contentType("application/merge-patch+json")
					.content("{\"expectedVersion\":0,\"visibility\":\"FOLLOWERS\"}"))
					.andExpect(status().isOk());
			long allocatedAmount = lifecycleState.equals("IN_PROGRESS")
					|| lifecycleState.equals("ABANDONED") ? 40_000L : 100_000L;
			setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
			asOwner(post(WISHES_PATH + "/" + wishId + "/deposits")
					.header("Idempotency-Key", "delete-" + key + "-deposit")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"amount\":%d,\"expectedVersion\":1}"
							.formatted(allocatedAmount)))
					.andExpect(status().isOk());

			long expectedVersion = 2L;
			if (lifecycleState.equals("COMPLETED")) {
				asOwner(post(WISHES_PATH + "/" + wishId + "/completion")
						.header("Idempotency-Key", "delete-completed-terminal")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":2}"))
						.andExpect(status().isOk());
				expectedVersion = 3L;
			} else if (lifecycleState.equals("ABANDONED")) {
				asOwner(post(WISHES_PATH + "/" + wishId + "/abandonment")
						.header("Idempotency-Key", "delete-abandoned-terminal")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":2}"))
						.andExpect(status().isOk());
				expectedVersion = 3L;
			}

			Map<String, Object> accountIdentity = jdbc.queryForMap("""
					SELECT id, student_id, academy_id FROM card_balance_account WHERE id = ?
					""", OWNER_ACCOUNT_ID);
			long actualCardBalance = ((Number) json(asOwner(get(
					"/v1/card-balance-accounts/{accountId}", OWNER_ACCOUNT_ID))
					.andExpect(status().isOk()).andReturn()
					.getResponse().getContentAsString(), "$.actualCardBalance")).longValue();
			long returnedAmount = lifecycleState.equals("IN_PROGRESS")
					|| lifecycleState.equals("AMOUNT_REACHED") ? allocatedAmount : 0L;
			long returnEventsBefore = jdbc.queryForObject("""
					SELECT count(*) FROM ledger_event WHERE account_id = ?
					  AND event_type = 'WISH_DELETION_RETURN'
					""", Long.class, OWNER_ACCOUNT_ID);

			String deleteBody = asOwner(delete(WISHES_PATH + "/" + wishId)
					.header(HttpHeaders.IF_MATCH, Long.toString(expectedVersion))
					.header("Idempotency-Key", "delete-" + key + "-command"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.wish.state").value(lifecycleState))
					.andExpect(jsonPath("$.wish.amount").value(0))
					.andReturn().getResponse().getContentAsString();

			Object eventId = json(deleteBody, "$.eventId");
			assertThat(jdbc.queryForObject("""
					SELECT count(*) FROM ledger_event WHERE account_id = ?
					  AND event_type = 'WISH_DELETION_RETURN'
					""", Long.class, OWNER_ACCOUNT_ID))
					.isEqualTo(returnEventsBefore + (returnedAmount == 0 ? 0 : 1));
			if (returnedAmount == 0) {
				assertThat(eventId).isNull();
			} else {
				assertThat(eventId).isInstanceOf(String.class);
				assertThat(jdbc.queryForMap("""
						SELECT event.event_type, event.account_delta, effect.wish_delta
						FROM ledger_event event
						JOIN ledger_wish_effect effect ON effect.event_id = event.id
						WHERE event.id = ?::uuid AND effect.wish_id = ?::uuid
						""", eventId, wishId))
						.containsEntry("event_type", "WISH_DELETION_RETURN")
						.containsEntry("account_delta", 0L)
						.containsEntry("wish_delta", -returnedAmount);
			}

			asOwner(get(WISHES_PATH + "/" + wishId))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code").value("WISH_NOT_FOUND"));
			assertThat(jdbc.queryForMap("""
					SELECT state, wish_amount, purpose, deleted_purpose_snapshot,
					       deleted_at IS NOT NULL AS deleted
					FROM wish WHERE id = ?::uuid
					""", wishId))
					.containsEntry("state", lifecycleState)
					.containsEntry("wish_amount", 0L)
					.containsEntry("purpose", purpose)
					.containsEntry("deleted_purpose_snapshot", purpose)
					.containsEntry("deleted", true);
			assertThat(jdbc.queryForObject(
					"SELECT count(*) FROM shared_card WHERE wish_id = ?::uuid",
					Long.class, wishId)).isZero();
			assertThat(jdbc.queryForMap("""
					SELECT id, student_id, academy_id FROM card_balance_account WHERE id = ?
					""", OWNER_ACCOUNT_ID)).isEqualTo(accountIdentity);
			assertThat(((Number) json(asOwner(get(
					"/v1/card-balance-accounts/{accountId}", OWNER_ACCOUNT_ID))
					.andExpect(status().isOk()).andReturn()
					.getResponse().getContentAsString(), "$.actualCardBalance")).longValue())
					.isEqualTo(actualCardBalance);
		}
	}

	@Test
	void transferRecordsOppositeWishEffectsWithoutChangingAccountBalances()
			throws Exception {
		String destinationWishId = createWish(
				"normative-transfer-destination", "규범 이동 대상", 300_000);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk());
		Map<String, Object> accountBefore = json(asOwner(get(
				"/v1/card-balance-accounts/{accountId}", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$");
		long allocationBefore = jdbc.queryForObject("""
				SELECT coalesce(sum(wish_amount), 0) FROM wish
				WHERE account_id = ? AND deleted_at IS NULL
				  AND state IN ('IN_PROGRESS', 'AMOUNT_REACHED')
				""", Long.class, OWNER_ACCOUNT_ID);

		String transferBody = asOwner(post(
				"/v1/card-balance-accounts/{accountId}/transfers", OWNER_ACCOUNT_ID)
				.header("Idempotency-Key", "normative-transfer-ledger-invariant")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sourceWishId":"%s","destinationWishId":"%s","amount":50000,
						 "sourceExpectedVersion":0,"destinationExpectedVersion":0}
						""".formatted(SeedFixtureCatalog.LAPTOP_WISH_ID, destinationWishId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceWish.amount").value(200_000))
				.andExpect(jsonPath("$.sourceWish.state").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.destinationWish.amount").value(50_000))
				.andExpect(jsonPath("$.destinationWish.state").value("IN_PROGRESS"))
				.andReturn().getResponse().getContentAsString();
		String eventId = json(transferBody, "$.eventId");

		assertThat(jdbc.queryForMap("""
				SELECT event_type, account_delta FROM ledger_event WHERE id = ?::uuid
				""", eventId))
				.containsEntry("event_type", "WISH_TRANSFER")
				.containsEntry("account_delta", 0L);
		assertThat(jdbc.queryForList("""
				SELECT wish_id::text, wish_delta FROM ledger_wish_effect
				WHERE event_id = ?::uuid ORDER BY wish_delta
				""", eventId))
				.extracting(row -> List.of(
						row.get("wish_id"), ((Number) row.get("wish_delta")).longValue()))
				.containsExactly(
						List.of(SeedFixtureCatalog.LAPTOP_WISH_ID.toString(), -50_000L),
						List.of(destinationWishId, 50_000L));
		assertThat(jdbc.queryForObject("""
				SELECT coalesce(sum(wish_amount), 0) FROM wish
				WHERE account_id = ? AND deleted_at IS NULL
				  AND state IN ('IN_PROGRESS', 'AMOUNT_REACHED')
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(allocationBefore);
		Map<String, Object> accountAfter = json(asOwner(get(
				"/v1/card-balance-accounts/{accountId}", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$");
		for (String balance : List.of(
				"actualCardBalance", "ledgerAvailableBalance", "displayAvailableBalance")) {
			assertThat(accountAfter.get(balance)).as(balance).isEqualTo(accountBefore.get(balance));
		}
		List<Map<String, Object>> accountHistory = json(asOwner(get(
				"/v1/card-balance-accounts/{accountId}/fund-movements", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.items");
		assertThat(accountHistory.stream()
				.filter(item -> eventId.equals(item.get("eventId"))).toList())
				.singleElement()
				.satisfies(item -> {
					assertThat(item).containsEntry("eventType", "WISH_TRANSFER")
							.containsEntry("accountAvailableBalanceDelta", 0);
					assertThat(((Map<?, ?>) item.get("sourceWish")).get("wishId"))
							.isEqualTo(SeedFixtureCatalog.LAPTOP_WISH_ID.toString());
					assertThat(((Map<?, ?>) item.get("destinationWish")).get("wishId"))
							.isEqualTo(destinationWishId);
				});
	}

	@Test
	void externalBalanceChangesShareEventIdentityAcrossCardAndFundHistory()
			throws Exception {
		List<Map<String, Object>> wishesBefore = jdbc.queryForList("""
				SELECT id, wish_amount, state, version FROM wish
				WHERE account_id = ? ORDER BY id
				""", OWNER_ACCOUNT_ID);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":1900000}]");
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk());
		clock.set(COMMAND_TIME.plusSeconds(1));
		asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk());

		List<Map<String, Object>> cardHistory = json(asOwner(get(
				"/v1/card-balance-accounts/{accountId}/card-balance-changes", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.items");
		List<Map<String, Object>> fundHistory = json(asOwner(get(
				"/v1/card-balance-accounts/{accountId}/fund-movements", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.items");
		assertThat(cardHistory).hasSize(2);
		assertThat(fundHistory).hasSize(2);
		assertThat(fundHistory).allSatisfy(item ->
				assertThat(item).containsEntry("eventType", "CARD_BALANCE_CHANGE"));
		assertThat(fundHistory).extracting(item -> item.get("eventId"))
				.containsExactlyElementsOf(cardHistory.stream()
						.map(item -> item.get("eventId")).toList());

		Map<String, Object> decrease = cardHistory.stream()
				.filter(item -> ((Number) item.get("actualCardBalanceDelta")).longValue()
						== -100_000L)
				.findFirst().orElseThrow();
		Map<String, Object> sameFundFact = fundHistory.stream()
				.filter(item -> decrease.get("eventId").equals(item.get("eventId")))
				.findFirst().orElseThrow();
		assertThat(sameFundFact)
				.containsEntry("actualCardBalanceDelta", -100_000)
				.containsEntry("accountAvailableBalanceDelta", -100_000)
				.containsEntry("actualCardBalanceAfter", 1_900_000)
				.containsEntry("accountAvailableBalanceAfter", 1_150_000);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ledger_wish_effect effect
				JOIN ledger_event event ON event.id = effect.event_id
				WHERE event.event_type = 'CARD_BALANCE_CHANGE'
				""", Long.class)).isZero();
		assertThat(jdbc.queryForList("""
				SELECT id, wish_amount, state, version FROM wish
				WHERE account_id = ? ORDER BY id
				""", OWNER_ACCOUNT_ID)).isEqualTo(wishesBefore);
	}

	@Test
	void publicProviderFailurePreservesEveryForbiddenStateAndOnlyRecordsAuditAttempt()
			throws Exception {
		String wishId = createWish("normative-provider-failure-create", "실패 원자성", 300_000);
		Map<String, Object> wishBefore = wishRow(wishId);
		List<Map<String, Object>> ledgerBefore = ledgerRows();
		String idempotencyBefore = ownerIdempotencyRecords();
		long outboxBefore = tableCount("mismatch_notification_outbox");
		long adjustmentBefore = tableCount("balance_adjustment_case");
		long sharedCardsBefore = tableCount("shared_card");
		int deliveriesBefore = notificationAdapter.deliveries().size();

		setBalanceScenario("[{\"type\":\"FAILURE\"}]");
		asOwner(post(WISHES_PATH + "/" + wishId + "/deposits")
				.header("Idempotency-Key", "normative-provider-failure")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":100000,\"expectedVersion\":0}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("BALANCE_SYNC_FAILED"))
				.andExpect(jsonPath("$.error.retryable").value(true));

		assertThat(wishRow(wishId)).isEqualTo(wishBefore);
		assertThat(ledgerRows()).isEqualTo(ledgerBefore);
		assertThat(ownerIdempotencyRecords()).isEqualTo(idempotencyBefore);
		assertThat(tableCount("mismatch_notification_outbox")).isEqualTo(outboxBefore);
		assertThat(tableCount("balance_adjustment_case")).isEqualTo(adjustmentBefore);
		assertThat(tableCount("shared_card")).isEqualTo(sharedCardsBefore);
		assertThat(notificationAdapter.deliveries()).hasSize(deliveriesBefore);

		assertThat(jdbc.queryForMap("""
				SELECT status, lookup_method, actual_card_balance, failure_code,
				       account_lookup_version, first_successful, previous_successful_observation_id,
				       balance_change_event_id
				FROM balance_observation WHERE account_id = ?
				""", OWNER_ACCOUNT_ID))
				.containsEntry("status", "FAILED")
				.containsEntry("lookup_method", "PRE_DEPOSIT")
				.containsEntry("failure_code", "BALANCE_SYNC_FAILED")
				.containsEntry("account_lookup_version", 1L)
				.containsEntry("actual_card_balance", null)
				.containsEntry("first_successful", null)
				.containsEntry("previous_successful_observation_id", null)
				.containsEntry("balance_change_event_id", null);
		assertThat(jdbc.queryForObject("""
				SELECT jsonb_exists(wish_idempotency_records, 'normative-provider-failure')
				FROM student WHERE id = ?
				""", Boolean.class, SeedFixtureCatalog.OWNER_ID)).isFalse();
		asOwner(get("/v1/card-balance-accounts/{accountId}", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceKnowledge").value("UNKNOWN"))
				.andExpect(jsonPath("$.actualCardBalance").doesNotExist())
				.andExpect(jsonPath("$.ledgerAvailableBalance").doesNotExist())
				.andExpect(jsonPath("$.displayAvailableBalance").doesNotExist())
				.andExpect(jsonPath("$.balanceAdjustmentInProgress").value(false))
				.andExpect(jsonPath("$.lastRefreshStatus").value("FAILED"));
		mockMvc.perform(get("/e2e/card-balance-accounts/{accountId}/balance-scenario",
				OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps").isEmpty());
	}

	@Test
	void sameSeedAndClockProduceSameNormalizedEndToEndSnapshot() throws Exception {
		Map<String, Object> first = runDeterministicLifecycle();
		Map<String, Object> second = runDeterministicLifecycle();

		assertThat(second).isEqualTo(first);
	}

	@Test
	void automaticDailyRefreshPersistsAutoDailyObservationAgainstPostgres() throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");

		dailyBalanceRefreshJob.refreshAllActiveAccounts();

		assertThat(jdbc.queryForMap("""
				SELECT status, lookup_method, actual_card_balance, account_lookup_version,
				       observed_at
				FROM balance_observation WHERE account_id = ?
				""", OWNER_ACCOUNT_ID))
				.containsEntry("status", "SUCCEEDED")
				.containsEntry("lookup_method", "AUTO_DAILY")
				.containsEntry("actual_card_balance", 2_000_000L)
				.containsEntry("account_lookup_version", 1L)
				.containsEntry("observed_at", Timestamp.from(COMMAND_TIME));
		assertThat(jdbc.queryForObject("""
				SELECT balance_lookup_version FROM card_balance_account WHERE id = ?
				""", Long.class, OWNER_ACCOUNT_ID)).isOne();
		mockMvc.perform(get("/e2e/card-balance-accounts/{accountId}/balance-scenario",
				OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps").isEmpty());
	}

	@Test
	void firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts()
			throws Exception {
		String wishId = createWish("normative-repeat-create", "다음 날에도 모으기", 300_000);
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":2000000}]");

		asOwner(post(WISHES_PATH + "/" + wishId + "/deposits")
				.header("Idempotency-Key", "normative-first-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":100000,\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(100_000));

		clock.set(COMMAND_TIME.plus(Duration.ofDays(1)));
		asOwner(post(WISHES_PATH + "/" + wishId + "/deposits")
				.header("Idempotency-Key", "normative-next-day-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":100000,\"expectedVersion\":1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(200_000))
				.andExpect(jsonPath("$.wish.version").value(2));

		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM balance_observation
				WHERE account_id = ? AND status = 'SUCCEEDED' AND lookup_method = 'PRE_DEPOSIT'
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(2L);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ledger_event
				WHERE account_id = ? AND event_type = 'CARD_BALANCE_CHANGE'
				  AND account_delta = 2000000
				""", Long.class, OWNER_ACCOUNT_ID)).isOne();
		assertThat(jdbc.queryForList("""
				SELECT event.occurred_at FROM ledger_event event
				JOIN ledger_wish_effect effect ON effect.event_id = event.id
				WHERE effect.wish_id = ?::uuid AND event.event_type = 'WISH_DEPOSIT'
				ORDER BY event.occurred_at
				""", Timestamp.class, wishId))
				.extracting(Timestamp::toInstant)
				.containsExactly(COMMAND_TIME, COMMAND_TIME.plus(Duration.ofDays(1)));
	}

	private void prepareTerminalWishes() throws Exception {
		asOwner(post(WISHES_PATH + "/" + SeedFixtureCatalog.CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "terminal-proof-completion")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("COMPLETED"));
		asOwner(post(WISHES_PATH + "/" + SeedFixtureCatalog.LAPTOP_WISH_ID + "/abandonment")
				.header("Idempotency-Key", "terminal-proof-abandonment")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("ABANDONED"));
	}

	private void assertTerminalBodyPatchRejected(UUID wishId, String requestedField)
			throws Exception {
		Map<String, Object> wishBefore = wishRow(wishId.toString());
		List<Map<String, Object>> ledgerBefore = ledgerRows();
		List<Map<String, Object>> cardsBefore = sharedCardRows(wishId);

		asOwner(patch(WISHES_PATH + "/" + wishId)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":1," + requestedField + "}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));

		assertThat(wishRow(wishId.toString())).isEqualTo(wishBefore);
		assertThat(ledgerRows()).isEqualTo(ledgerBefore);
		assertThat(sharedCardRows(wishId)).isEqualTo(cardsBefore);
	}

	private List<Map<String, Object>> sharedCardRows(UUID wishId) {
		return jdbc.queryForList("""
				SELECT id, wish_id, kind, visibility, updated_at
				FROM shared_card WHERE wish_id = ? ORDER BY id
				""", wishId);
	}

	private Map<String, Object> wishRow(String wishId) {
		return jdbc.queryForMap("""
				SELECT purpose, target_amount, wish_amount, state, visibility, target_date,
				       completed_at, deleted_at, deleted_purpose_snapshot, version
				FROM wish WHERE id = ?::uuid
				""", wishId);
	}

	private Map<String, Object> runDeterministicLifecycle() throws Exception {
		resetFixture();
		notificationAdapter.clear();
		List<Map<String, Object>> http = new ArrayList<>();

		var create = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "deterministic-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"purpose\":\"결정적 시나리오\",\"targetAmount\":100000}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse();
		String wishId = json(create.getContentAsString(), "$.wish.id");
		http.add(normalizedMutation("create", create.getStatus(),
				create.getHeader("Idempotency-Replayed"), create.getContentAsString()));

		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		clock.set(COMMAND_TIME.plusSeconds(1));
		var deposit = asOwner(post(WISHES_PATH + "/" + wishId + "/deposits")
				.header("Idempotency-Key", "deterministic-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":60000,\"expectedVersion\":0}"))
				.andExpect(status().isOk()).andReturn().getResponse();
		http.add(normalizedMutation("deposit", deposit.getStatus(),
				deposit.getHeader("Idempotency-Replayed"), deposit.getContentAsString()));

		clock.set(COMMAND_TIME.plusSeconds(2));
		var withdrawal = asOwner(post(WISHES_PATH + "/" + wishId + "/withdrawals")
				.header("Idempotency-Key", "deterministic-withdrawal")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":10000,\"expectedVersion\":1}"))
				.andExpect(status().isOk()).andReturn().getResponse();
		http.add(normalizedMutation("withdrawal", withdrawal.getStatus(),
				withdrawal.getHeader("Idempotency-Replayed"), withdrawal.getContentAsString()));

		clock.set(COMMAND_TIME.plusSeconds(3));
		var patchResponse = asOwner(patch(WISHES_PATH + "/" + wishId)
				.contentType("application/merge-patch+json")
				.content("""
						{"expectedVersion":2,"targetAmount":50000,"visibility":"FOLLOWERS"}
						"""))
				.andExpect(status().isOk()).andReturn().getResponse();
		http.add(normalizedMutation("patch", patchResponse.getStatus(),
				patchResponse.getHeader("Idempotency-Replayed"),
				patchResponse.getContentAsString()));

		String cardId = jdbc.queryForObject(
				"SELECT id::text FROM shared_card WHERE wish_id = ?::uuid", String.class, wishId);
		clock.set(COMMAND_TIME.plusSeconds(4));
		var completion = asOwner(post(WISHES_PATH + "/" + wishId + "/completion")
				.header("Idempotency-Key", "deterministic-completion")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":3}"))
				.andExpect(status().isOk()).andReturn().getResponse();
		http.add(normalizedMutation("completion", completion.getStatus(),
				completion.getHeader("Idempotency-Replayed"), completion.getContentAsString()));

		Map<String, Object> account = json(asOwner(get(
				"/v1/card-balance-accounts/{accountId}", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
		Map<String, Object> card = new LinkedHashMap<>(json(
				asToken(FRIEND_TOKEN, get(
						"/v1/academies/{academyId}/shared-cards/{cardId}",
						SeedFixtureCatalog.PRIMARY_ACADEMY_ID, cardId))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$"));
		card.put("sharedCardId", "card-1");

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("http", http);
		result.put("wish", wishRow(wishId));
		result.put("account", account);
		result.put("ledger", jdbc.queryForList("""
				SELECT event.event_type, event.account_delta, effect.wish_delta,
				       event.occurred_at
				FROM ledger_event event
				LEFT JOIN ledger_wish_effect effect ON effect.event_id = event.id
				WHERE event.account_id = ?
				ORDER BY event.occurred_at, event.event_type, effect.wish_delta
				""", OWNER_ACCOUNT_ID));
		result.put("idempotencyKeys", jdbc.queryForList("""
				SELECT jsonb_object_keys(wish_idempotency_records) AS key
				FROM student WHERE id = ? ORDER BY key
				""", String.class, SeedFixtureCatalog.OWNER_ID));
		result.put("sharedCard", card);
		result.put("adjustmentCases", tableCount("balance_adjustment_case"));
		result.put("notificationOutbox", tableCount("mismatch_notification_outbox"));
		result.put("notificationDeliveries", notificationAdapter.deliveries().size());
		result.put("providerSteps", json(mockMvc.perform(get(
				"/e2e/card-balance-accounts/{accountId}/balance-scenario", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.steps"));
		return result;
	}

	private Map<String, Object> normalizedMutation(
			String operation, int statusCode, String replayed, String body) {
		Map<String, Object> wish = new LinkedHashMap<>(json(body, "$.wish"));
		wish.put("id", "wish-1");
		Map<String, Object> normalized = new LinkedHashMap<>();
		normalized.put("operation", operation);
		normalized.put("status", statusCode);
		normalized.put("idempotencyReplayed", replayed);
		normalized.put("wish", wish);
		normalized.put("eventId", json(body, "$.eventId") == null ? null : "event-" + operation);
		return normalized;
	}

	private List<Map<String, Object>> ledgerRows() {
		return jdbc.queryForList("""
				SELECT event.event_type, event.account_delta, event.deposit_balance_observation_id,
				       effect.wish_id, effect.wish_delta
				FROM ledger_event event
				LEFT JOIN ledger_wish_effect effect ON effect.event_id = event.id
				WHERE event.account_id = ?
				ORDER BY event.occurred_at, event.event_type, effect.wish_id
				""", OWNER_ACCOUNT_ID);
	}

	private String ownerIdempotencyRecords() {
		return jdbc.queryForObject("""
				SELECT wish_idempotency_records::text FROM student WHERE id = ?
				""", String.class, SeedFixtureCatalog.OWNER_ID);
	}

	private long tableCount(String table) {
		if (!Set.of("mismatch_notification_outbox", "balance_adjustment_case", "shared_card")
				.contains(table)) {
			throw new IllegalArgumentException("Unsupported table: " + table);
		}
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}
}
