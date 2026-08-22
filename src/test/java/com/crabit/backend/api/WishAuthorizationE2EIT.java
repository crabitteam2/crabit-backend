package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.BLOCKED_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.NONFRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.STAFF_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.e2e.SeedFixtureCatalog;
import com.crabit.backend.notification.DeterministicMismatchNotificationAdapter;
import com.crabit.backend.notification.MismatchNotification;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Aggregates the owner-only command/query coverage under the normative suite ID. */
class WishAuthorizationE2EIT extends WishOwnershipIT {

	@Autowired
	private DeterministicMismatchNotificationAdapter notificationAdapter;

	@Override
	@Test
	void exposesOwnerResourcesWhileHidingThemFromOtherStudentsAndAcademies()
			throws Exception {
		super.exposesOwnerResourcesWhileHidingThemFromOtherStudentsAndAcademies();
	}

	@Override
	@Test
	void rejectsStaffAndUnknownCallersAndLeavesRejectedCrossOwnerCommandsUnchanged()
			throws Exception {
		super.rejectsStaffAndUnknownCallersAndLeavesRejectedCrossOwnerCommandsUnchanged();
	}

	@Test
	void createDeleteCompleteAndAbandonAuthorizationMatrixPreservesRejectedState()
			throws Exception {
		List<CommandCase> commands = List.of(
				new CommandCase("create", status().isCreated(), () -> post(WISHES_PATH)
						.header("Idempotency-Key", "auth-create")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"purpose\":\"인가 생성\",\"targetAmount\":100000}")),
				new CommandCase("delete", status().isOk(), () -> delete(
						WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.header(HttpHeaders.IF_MATCH, "0")
						.header("Idempotency-Key", "auth-delete")),
				new CommandCase("complete", status().isOk(), () -> post(
						WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
						.header("Idempotency-Key", "auth-complete")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":0}")),
				new CommandCase("abandon", status().isOk(), () -> post(
						WISHES_PATH + "/" + LAPTOP_WISH_ID + "/abandonment")
						.header("Idempotency-Key", "auth-abandon")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":0}")));

		for (CommandCase command : commands) {
			for (RejectedPrincipal principal : RejectedPrincipal.values()) {
				resetFixture();
				notificationAdapter.clear();
				setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
				AuthorizationState before = authorizationState();

				ResultActions result = principal.token == null
						? mockMvc.perform(command.request.get())
						: asToken(principal.token, command.request.get());
				result.andExpect(principal.status)
						.andExpect(jsonPath("$.error.code").value(principal.errorCode));

				assertThat(authorizationState())
						.as(command.name + " rejected for " + principal.name())
						.isEqualTo(before);
			}

			resetFixture();
			notificationAdapter.clear();
			setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
			AuthorizationState before = authorizationState();
			asOwner(command.request.get()).andExpect(command.ownerStatus)
					.andExpect(header().string("Idempotency-Replayed", "false"));
			assertThat(authorizationState())
					.as(command.name + " accepted for owner")
					.isNotEqualTo(before);
		}
	}

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

	private AuthorizationState authorizationState() throws Exception {
		List<Map<String, Object>> wishes = jdbc.queryForList("""
				SELECT id, purpose, target_amount, wish_amount, state, visibility,
				       completed_at, deleted_at, deleted_purpose_snapshot, version
				FROM wish ORDER BY id
				""");
		List<Map<String, Object>> ledger = jdbc.queryForList("""
				SELECT event.event_type, event.account_delta, effect.wish_id, effect.wish_delta
				FROM ledger_event event
				LEFT JOIN ledger_wish_effect effect ON effect.event_id = event.id
				ORDER BY event.occurred_at, event.event_type, effect.wish_id
				""");
		List<Map<String, Object>> observations = jdbc.queryForList("""
				SELECT status, lookup_method, actual_card_balance, failure_code,
				       account_lookup_version, balance_change_event_id
				FROM balance_observation ORDER BY observed_at, id
				""");
		List<Map<String, Object>> idempotency = jdbc.queryForList("""
				SELECT id, wish_idempotency_records::text AS records
				FROM student ORDER BY id
				""");
		List<Map<String, Object>> cards = jdbc.queryForList("""
				SELECT id, wish_id, kind, visibility, updated_at FROM shared_card ORDER BY id
				""");
		List<Map<String, Object>> adjustments = jdbc.queryForList("""
				SELECT status, opened_shortage, opened_at, resolved_at
				FROM balance_adjustment_case ORDER BY opened_at, id
				""");
		List<Map<String, Object>> outbox = jdbc.queryForList("""
				SELECT created_at, published_at FROM mismatch_notification_outbox
				ORDER BY created_at, id
				""");
		List<Map<String, Object>> providerSteps = json(mockMvc.perform(get(
				"/e2e/card-balance-accounts/{accountId}/balance-scenario", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.steps");
		List<MismatchNotification> deliveries = notificationAdapter.deliveries();
		return new AuthorizationState(
				wishes, ledger, observations, idempotency, cards, adjustments, outbox,
				providerSteps, deliveries);
	}

	private enum RejectedPrincipal {
		UNAUTHENTICATED(null, status().isUnauthorized(), "AUTH_REQUIRED"),
		NON_OWNER(FRIEND_TOKEN, status().isNotFound(), "CARD_BALANCE_ACCOUNT_NOT_FOUND"),
		STAFF(STAFF_TOKEN, status().isForbidden(), "FORBIDDEN");

		private final String token;
		private final org.springframework.test.web.servlet.ResultMatcher status;
		private final String errorCode;

		RejectedPrincipal(
				String token,
				org.springframework.test.web.servlet.ResultMatcher status,
				String errorCode) {
			this.token = token;
			this.status = status;
			this.errorCode = errorCode;
		}
	}

	private record CommandCase(
			String name,
			org.springframework.test.web.servlet.ResultMatcher ownerStatus,
			Supplier<MockHttpServletRequestBuilder> request) {
	}

	private record AuthorizationState(
			List<Map<String, Object>> wishes,
			List<Map<String, Object>> ledger,
			List<Map<String, Object>> observations,
			List<Map<String, Object>> idempotency,
			List<Map<String, Object>> sharedCards,
			List<Map<String, Object>> adjustments,
			List<Map<String, Object>> outbox,
			List<Map<String, Object>> providerSteps,
			List<MismatchNotification> deliveries) {
	}
}
