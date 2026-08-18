package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.FRIEND_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OTHER_ACADEMY_TOKEN;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.STAFF_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class WishFundMovementBoundaryIT extends WishApiIntegrationSupport {

	private static final String DEPOSITS = WISHES_PATH + "/" + LAPTOP_WISH_ID + "/deposits";
	private static final String WITHDRAWALS = WISHES_PATH + "/" + CAMP_WISH_ID + "/withdrawals";
	private static final String TRANSFERS =
			"/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/transfers";
	private static final String AMOUNT = "{\"amount\":1,\"expectedVersion\":0}";
	private static final String TRANSFER = """
			{"sourceWishId":"%s","destinationWishId":"%s","amount":1,
			"sourceExpectedVersion":0,"destinationExpectedVersion":0}
			""".formatted(LAPTOP_WISH_ID, CAMP_WISH_ID);

	@Test
	void movementEndpointsMapMissingAndUnsupportedContentTypesToMalformedRequestWithoutEffects()
			throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");

		for (MovementRequest movement : List.of(
				new MovementRequest(DEPOSITS, AMOUNT),
				new MovementRequest(WITHDRAWALS, AMOUNT),
				new MovementRequest(TRANSFERS, TRANSFER))) {
			assertMalformedMediaType(post(movement.path())
					.header("Idempotency-Key", "missing-content-type-" + movement.path().hashCode())
					.content(movement.body()));
			assertMalformedMediaType(post(movement.path())
					.header("Idempotency-Key", "unsupported-content-type-" + movement.path().hashCode())
					.contentType(MediaType.TEXT_PLAIN)
					.content(movement.body()));
		}

		assertThat(jdbc.queryForObject(
				"SELECT sum(wish_amount) FROM wish WHERE account_id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isEqualTo(750_000L);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ? "
						+ "AND event_type IN ('WISH_DEPOSIT', 'WISH_WITHDRAWAL', 'WISH_TRANSFER')",
				Long.class, OWNER_ACCOUNT_ID)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM balance_observation WHERE account_id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT balance_lookup_version FROM card_balance_account WHERE id = ?",
				Long.class, OWNER_ACCOUNT_ID)).isZero();
		mockMvc.perform(get("/e2e/card-balance-accounts/{accountId}/balance-scenario",
				OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps.length()").value(1));

		asOwner(patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0,\"purpose\":\"새 노트북\"}"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void rejectsTheIdempotencyAndJsonBoundaryMatrixBeforeAnyPersistentOrProviderEffect()
			throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");

		for (BoundaryCase boundary : boundaryCases()) {
			MovementState before = movementState();
			int remainingSteps = remainingProviderSteps();
			MockHttpServletRequestBuilder request = post(boundary.path())
					.contentType(MediaType.APPLICATION_JSON)
					.content(boundary.body());
			if (boundary.idempotencyKey() != null) {
				request.header("Idempotency-Key", boundary.idempotencyKey());
			}

			asOwner(request)
					.andExpect(status().is(boundary.status()))
					.andExpect(jsonPath("$.error.code").value(boundary.code()));

			assertThat(movementState()).as(boundary.label()).isEqualTo(before);
			assertThat(remainingProviderSteps()).as(boundary.label() + " provider steps")
					.isEqualTo(remainingSteps);
		}
	}

	@Test
	void conflictingIdempotencyReuseIsRejectedWithoutRepeatingAnyMovementEffect()
			throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":2000000}]");

		assertConflictAfterSuccess(DEPOSITS, "deposit-conflict",
				"{\"amount\":1,\"expectedVersion\":0}",
				"{\"amount\":2,\"expectedVersion\":0}");
		assertConflictAfterSuccess(WITHDRAWALS, "withdrawal-conflict",
				"{\"amount\":1,\"expectedVersion\":0}",
				"{\"amount\":2,\"expectedVersion\":0}");
		assertConflictAfterSuccess(TRANSFERS, "transfer-conflict",
				transferBody("1", "1", "1"),
				transferBody("2", "1", "1"));

		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM ledger_event WHERE account_id = ? "
						+ "AND event_type IN ('WISH_DEPOSIT', 'WISH_WITHDRAWAL', 'WISH_TRANSFER')",
				Long.class, OWNER_ACCOUNT_ID)).isEqualTo(3L);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM student,
				LATERAL jsonb_object_keys(wish_idempotency_records)
				WHERE id = (SELECT student_id FROM card_balance_account WHERE id = ?)
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(3L);
		assertThat(remainingProviderSteps()).isOne();
	}

	@Test
	void enforcesAuthenticationOwnershipVisibilityTombstonesAndTerminalStatesForEveryOperation()
			throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		List<Endpoint> owned = List.of(
				new Endpoint("deposit", DEPOSITS, AMOUNT),
				new Endpoint("withdrawal", WITHDRAWALS, AMOUNT),
				new Endpoint("transfer", TRANSFERS, TRANSFER));
		int sequence = 0;
		for (Endpoint endpoint : owned) {
			assertRejectedWithoutEffects(endpoint, null, false, 401, "AUTH_REQUIRED",
					"auth-" + sequence++);
			assertRejectedWithoutEffects(endpoint, STAFF_TOKEN, true, 403, "FORBIDDEN",
					"staff-" + sequence++);
			assertRejectedWithoutEffects(endpoint, FRIEND_TOKEN, true, 404,
					"CARD_BALANCE_ACCOUNT_NOT_FOUND", "friend-" + sequence++);
			assertRejectedWithoutEffects(endpoint, OTHER_ACADEMY_TOKEN, true, 404,
					"CARD_BALANCE_ACCOUNT_NOT_FOUND", "other-academy-" + sequence++);
		}

		UUID missingAccount = UUID.randomUUID();
		for (Endpoint endpoint : owned) {
			assertRejectedWithoutEffects(new Endpoint(endpoint.name(),
					endpoint.path().replace(OWNER_ACCOUNT_ID.toString(), missingAccount.toString()),
					endpoint.validBody()), null, true, 404,
					"CARD_BALANCE_ACCOUNT_NOT_FOUND", "missing-account-" + sequence++);
		}

		UUID missingWish = UUID.randomUUID();
		assertRejectedWithoutEffects(new Endpoint("deposit",
				WISHES_PATH + "/" + missingWish + "/deposits", AMOUNT), null, true,
				404, "WISH_NOT_FOUND", "missing-deposit-wish");
		assertRejectedWithoutEffects(new Endpoint("withdrawal",
				WISHES_PATH + "/" + missingWish + "/withdrawals", AMOUNT), null, true,
				404, "WISH_NOT_FOUND", "missing-withdrawal-wish");
		assertRejectedWithoutEffects(new Endpoint("transfer", TRANSFERS,
				transferBody(missingWish, CAMP_WISH_ID, "1", "0", "0")), null, true,
				404, "WISH_NOT_FOUND", "missing-transfer-wish");

		asOwner(delete(WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.header(HttpHeaders.IF_MATCH, "0")
				.header("Idempotency-Key", "delete-for-movement-matrix"))
				.andExpect(status().isOk());
		for (Endpoint endpoint : List.of(
				new Endpoint("deposit", DEPOSITS, "{\"amount\":1,\"expectedVersion\":1}"),
				new Endpoint("withdrawal", WISHES_PATH + "/" + LAPTOP_WISH_ID + "/withdrawals",
						"{\"amount\":1,\"expectedVersion\":1}"),
				new Endpoint("transfer", TRANSFERS,
						transferBody(LAPTOP_WISH_ID, CAMP_WISH_ID, "1", "1", "0")))) {
			assertRejectedWithoutEffects(endpoint, null, true, 404, "WISH_NOT_FOUND",
					"deleted-" + endpoint.name());
		}

		asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "complete-for-movement-matrix")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk());
		UUID abandonedWish = UUID.fromString(createWish(
				"create-abandoned-for-movement-matrix", "포기할 목표", 100_000));
		asOwner(post(WISHES_PATH + "/" + abandonedWish + "/abandonment")
				.header("Idempotency-Key", "abandon-for-movement-matrix")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk());
		UUID activeDestination = UUID.fromString(createWish(
				"create-active-for-movement-matrix", "진행 중 목표", 100_000));

		for (TerminalWish terminal : List.of(
				new TerminalWish("completed", CAMP_WISH_ID),
				new TerminalWish("abandoned", abandonedWish))) {
			for (Endpoint endpoint : List.of(
					new Endpoint("deposit", WISHES_PATH + "/" + terminal.id() + "/deposits",
							"{\"amount\":1,\"expectedVersion\":1}"),
					new Endpoint("withdrawal", WISHES_PATH + "/" + terminal.id() + "/withdrawals",
							"{\"amount\":1,\"expectedVersion\":1}"),
					new Endpoint("transfer", TRANSFERS,
							transferBody(terminal.id(), activeDestination, "1", "1", "0")))) {
				assertRejectedWithoutEffects(endpoint, null, true, 409,
						"INVALID_STATE_TRANSITION",
						terminal.name() + "-" + endpoint.name());
			}
		}
	}

	private void assertMalformedMediaType(MockHttpServletRequestBuilder request) throws Exception {
		asOwner(request)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
	}

	private void assertConflictAfterSuccess(
			String path, String key, String acceptedBody, String conflictingBody) throws Exception {
		asOwner(post(path)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(acceptedBody))
				.andExpect(status().isOk());
		MovementState afterSuccess = movementState();
		int remainingSteps = remainingProviderSteps();

		asOwner(post(path)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(conflictingBody))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

		assertThat(movementState()).isEqualTo(afterSuccess);
		assertThat(remainingProviderSteps()).isEqualTo(remainingSteps);
	}

	private void assertRejectedWithoutEffects(
			Endpoint endpoint,
			String token,
			boolean authenticated,
			int expectedStatus,
			String expectedCode,
			String key) throws Exception {
		MovementState before = movementState();
		int remainingSteps = remainingProviderSteps();
		MockHttpServletRequestBuilder request = post(endpoint.path())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(endpoint.validBody());
		if (!authenticated) {
			mockMvc.perform(request)
					.andExpect(status().is(expectedStatus))
					.andExpect(jsonPath("$.error.code").value(expectedCode));
		} else if (token == null) {
			asOwner(request)
					.andExpect(status().is(expectedStatus))
					.andExpect(jsonPath("$.error.code").value(expectedCode));
		} else {
			asToken(token, request)
					.andExpect(status().is(expectedStatus))
					.andExpect(jsonPath("$.error.code").value(expectedCode));
		}
		assertThat(movementState()).as(key).isEqualTo(before);
		assertThat(remainingProviderSteps()).as(key + " provider steps")
				.isEqualTo(remainingSteps);
	}

	private List<BoundaryCase> boundaryCases() {
		List<BoundaryCase> cases = new java.util.ArrayList<>();
		for (Endpoint endpoint : List.of(
				new Endpoint("deposit", DEPOSITS, AMOUNT),
				new Endpoint("withdrawal", WITHDRAWALS, AMOUNT),
				new Endpoint("transfer", TRANSFERS, TRANSFER))) {
			cases.add(new BoundaryCase(endpoint.name() + " missing key", endpoint.path(),
					null, endpoint.validBody(), 400, "IDEMPOTENCY_KEY_REQUIRED"));
			cases.add(new BoundaryCase(endpoint.name() + " blank key", endpoint.path(),
					" ", endpoint.validBody(), 400, "IDEMPOTENCY_KEY_REQUIRED"));
			cases.add(new BoundaryCase(endpoint.name() + " oversized key", endpoint.path(),
					"k".repeat(201), endpoint.validBody(), 400, "MALFORMED_REQUEST"));
			cases.add(new BoundaryCase(endpoint.name() + " malformed JSON", endpoint.path(),
					endpoint.name() + "-malformed", "{", 400, "MALFORMED_REQUEST"));
			cases.add(new BoundaryCase(endpoint.name() + " unknown field", endpoint.path(),
					endpoint.name() + "-unknown", withUnknownField(endpoint.validBody()),
					400, "MALFORMED_REQUEST"));
		}

		for (Endpoint endpoint : List.of(
				new Endpoint("deposit", DEPOSITS, AMOUNT),
				new Endpoint("withdrawal", WITHDRAWALS, AMOUNT))) {
			cases.add(amountCase(endpoint, "missing amount", "{\"expectedVersion\":0}",
					400, "MALFORMED_REQUEST"));
			cases.add(amountCase(endpoint, "zero amount",
					"{\"amount\":0,\"expectedVersion\":0}", 422, "INVALID_AMOUNT"));
			cases.add(amountCase(endpoint, "negative amount",
					"{\"amount\":-1,\"expectedVersion\":0}", 422, "INVALID_AMOUNT"));
			cases.add(amountCase(endpoint, "overflow amount",
					"{\"amount\":9007199254740992,\"expectedVersion\":0}",
					422, "INVALID_AMOUNT"));
			cases.add(amountCase(endpoint, "fractional amount",
					"{\"amount\":1.5,\"expectedVersion\":0}", 400, "MALFORMED_REQUEST"));
			cases.add(amountCase(endpoint, "missing version", "{\"amount\":1}",
					400, "MALFORMED_REQUEST"));
			cases.add(amountCase(endpoint, "negative version",
					"{\"amount\":1,\"expectedVersion\":-1}", 422, "INVALID_VERSION"));
			cases.add(amountCase(endpoint, "fractional version",
					"{\"amount\":1,\"expectedVersion\":0.5}", 400, "MALFORMED_REQUEST"));
			cases.add(amountCase(endpoint, "stale version",
					"{\"amount\":1,\"expectedVersion\":1}", 409, "VERSION_CONFLICT"));
		}

		cases.add(transferCase("missing amount", transferBody(null, "0", "0"),
				400, "MALFORMED_REQUEST"));
		cases.add(transferCase("zero amount", transferBody("0", "0", "0"),
				422, "INVALID_AMOUNT"));
		cases.add(transferCase("negative amount", transferBody("-1", "0", "0"),
				422, "INVALID_AMOUNT"));
		cases.add(transferCase("overflow amount",
				transferBody("9007199254740992", "0", "0"), 422, "INVALID_AMOUNT"));
		cases.add(transferCase("fractional amount", transferBody("1.5", "0", "0"),
				400, "MALFORMED_REQUEST"));
		cases.add(transferCase("missing source version", transferBody("1", null, "0"),
				400, "MALFORMED_REQUEST"));
		cases.add(transferCase("negative source version", transferBody("1", "-1", "0"),
				422, "INVALID_VERSION"));
		cases.add(transferCase("negative destination version", transferBody("1", "0", "-1"),
				422, "INVALID_VERSION"));
		cases.add(transferCase("stale source version", transferBody("1", "1", "0"),
				409, "VERSION_CONFLICT"));
		cases.add(transferCase("stale destination version", transferBody("1", "0", "1"),
				409, "VERSION_CONFLICT"));
		return List.copyOf(cases);
	}

	private static BoundaryCase amountCase(
			Endpoint endpoint, String label, String body, int status, String code) {
		return new BoundaryCase(endpoint.name() + " " + label, endpoint.path(),
				endpoint.name() + "-" + label.replace(' ', '-'), body, status, code);
	}

	private static BoundaryCase transferCase(
			String label, String body, int status, String code) {
		return new BoundaryCase("transfer " + label, TRANSFERS,
				"transfer-" + label.replace(' ', '-'), body, status, code);
	}

	private static String transferBody(
			String amount, String sourceVersion, String destinationVersion) {
		return transferBody(LAPTOP_WISH_ID, CAMP_WISH_ID,
				amount, sourceVersion, destinationVersion);
	}

	private static String transferBody(
			UUID sourceWishId,
			UUID destinationWishId,
			String amount,
			String sourceVersion,
			String destinationVersion) {
		List<String> fields = new java.util.ArrayList<>(List.of(
				"\"sourceWishId\":\"" + sourceWishId + "\"",
				"\"destinationWishId\":\"" + destinationWishId + "\""));
		if (amount != null) fields.add("\"amount\":" + amount);
		if (sourceVersion != null) fields.add("\"sourceExpectedVersion\":" + sourceVersion);
		if (destinationVersion != null) {
			fields.add("\"destinationExpectedVersion\":" + destinationVersion);
		}
		return "{" + String.join(",", fields) + "}";
	}

	private static String withUnknownField(String json) {
		return json.substring(0, json.lastIndexOf('}')) + ",\"unknown\":true}";
	}

	private MovementState movementState() {
		List<String> wishes = jdbc.query("""
				SELECT id, wish_amount, state, version, deleted_at
				FROM wish WHERE account_id = ? ORDER BY id
				""", (row, index) -> String.join(":",
				row.getString("id"),
				Long.toString(row.getLong("wish_amount")),
				row.getString("state"),
				Long.toString(row.getLong("version")),
				String.valueOf(row.getTimestamp("deleted_at"))), OWNER_ACCOUNT_ID);
		String idempotency = jdbc.queryForObject("""
				SELECT wish_idempotency_records::text FROM student
				WHERE id = (SELECT student_id FROM card_balance_account WHERE id = ?)
				""", String.class, OWNER_ACCOUNT_ID);
		long ledger = count("ledger_event");
		long observations = count("balance_observation");
		long adjustments = count("balance_adjustment_case");
		long lookupVersion = jdbc.queryForObject(
				"SELECT balance_lookup_version FROM card_balance_account WHERE id = ?",
				Long.class, OWNER_ACCOUNT_ID);
		return new MovementState(wishes, idempotency, ledger, observations,
				adjustments, lookupVersion);
	}

	private long count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE account_id = ?",
				Long.class, OWNER_ACCOUNT_ID);
	}

	private int remainingProviderSteps() throws Exception {
		MvcResult result = mockMvc.perform(get(
				"/e2e/card-balance-accounts/{accountId}/balance-scenario", OWNER_ACCOUNT_ID))
				.andExpect(status().isOk())
				.andReturn();
		return ((Number) json(result.getResponse().getContentAsString(), "$.steps.length()"))
				.intValue();
	}

	private record MovementRequest(String path, String body) {
	}

	private record Endpoint(String name, String path, String validBody) {
	}

	private record BoundaryCase(
			String label, String path, String idempotencyKey, String body, int status, String code) {
	}

	private record MovementState(
			List<String> wishes,
			String idempotency,
			long ledger,
			long observations,
			long adjustments,
			long lookupVersion) {
	}

	private record TerminalWish(String name, UUID id) {
	}
}
