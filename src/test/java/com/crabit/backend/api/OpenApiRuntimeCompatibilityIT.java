package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.LAPTOP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.CAMP_WISH_ID;
import static com.crabit.backend.e2e.SeedFixtureCatalog.OWNER_ACCOUNT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.e2e.CardBalanceScenarioController;
import com.crabit.backend.e2e.SeedFixtureCatalog;
import com.jayway.jsonpath.JsonPath;
import io.swagger.v3.oas.annotations.Hidden;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.yaml.snakeyaml.Yaml;

class OpenApiRuntimeCompatibilityIT extends WishApiIntegrationSupport {

	private static final Set<String> HTTP_METHODS = Set.of(
			"get", "post", "put", "patch", "delete", "options", "head", "trace");
	private static final String WISH_COLLECTION_PATH =
			"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes";
	private static final String WISH_PATH =
			WISH_COLLECTION_PATH + "/{wishId}";
	private static final String MOVEMENTS_PATH = WISH_PATH + "/fund-movements";

	@Test
	void allElevenWishOperationsExecuteDeclaredSuccessesAgainstPostgres() throws Exception {
		Map<String, Object> canonical = canonicalDocument();

		var listResponse = asOwner(get(WISHES_PATH))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isArray())
				.andReturn().getResponse();
		assertStatusDeclared(canonical, "get", WISH_COLLECTION_PATH, 200);
		assertCanonicalResponse(canonical, "get", WISH_COLLECTION_PATH, 200, listResponse);

		var createSource = asOwner(post(WISHES_PATH)
				.header("Idempotency-Key", "runtime-success-create-source")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"purpose\":\"런타임 원본\",\"targetAmount\":200000}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.wish.version").value(0))
				.andReturn().getResponse();
		String sourceWishId = JsonPath.read(createSource.getContentAsString(), "$.wish.id");
		String destinationWishId = createWish(
				"runtime-success-create-destination", "런타임 대상", 200_000);
		assertStatusDeclared(canonical, "post", WISH_COLLECTION_PATH, 201);
		assertCanonicalResponse(
				canonical, "post", WISH_COLLECTION_PATH, 201, createSource);

		var detailResponse = asOwner(get(WISHES_PATH + "/" + sourceWishId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(sourceWishId))
				.andReturn().getResponse();
		assertStatusDeclared(canonical, "get", WISH_PATH, 200);
		assertCanonicalResponse(canonical, "get", WISH_PATH, 200, detailResponse);

		var patchResponse = asOwner(patch(WISHES_PATH + "/" + sourceWishId)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":0,\"visibility\":\"FRIENDS\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.version").value(1))
				.andReturn().getResponse();
		assertStatusDeclared(canonical, "patch", WISH_PATH, 200);
		assertCanonicalResponse(canonical, "patch", WISH_PATH, 200, patchResponse);

		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":2000000}]");
		var depositResponse = asOwner(post(WISHES_PATH + "/" + sourceWishId + "/deposits")
				.header("Idempotency-Key", "runtime-success-deposit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":100000,\"expectedVersion\":1}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(100_000))
				.andExpect(jsonPath("$.wish.version").value(2))
				.andReturn().getResponse();
		assertStatusDeclared(canonical, "post", WISH_PATH + "/deposits", 200);
		assertCanonicalResponse(
				canonical, "post", WISH_PATH + "/deposits", 200, depositResponse);

		var withdrawalResponse = asOwner(post(WISHES_PATH + "/" + sourceWishId + "/withdrawals")
				.header("Idempotency-Key", "runtime-success-withdrawal")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":10000,\"expectedVersion\":2}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.amount").value(90_000))
				.andExpect(jsonPath("$.wish.version").value(3))
				.andReturn().getResponse();
		assertStatusDeclared(canonical, "post", WISH_PATH + "/withdrawals", 200);
		assertCanonicalResponse(
				canonical, "post", WISH_PATH + "/withdrawals", 200, withdrawalResponse);

		var transferResponse = asOwner(post(
				"/v1/card-balance-accounts/{accountId}/transfers", OWNER_ACCOUNT_ID)
				.header("Idempotency-Key", "runtime-success-transfer")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"sourceWishId":"%s","destinationWishId":"%s","amount":20000,
						 "sourceExpectedVersion":3,"destinationExpectedVersion":0}
						""".formatted(sourceWishId, destinationWishId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sourceWish.amount").value(70_000))
				.andExpect(jsonPath("$.destinationWish.amount").value(20_000))
				.andReturn().getResponse();
		assertStatusDeclared(canonical, "post",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 200);
		assertCanonicalResponse(canonical, "post",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 200,
				transferResponse);

		var historyResponse = asOwner(get(
				WISHES_PATH + "/" + sourceWishId + "/fund-movements"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isArray())
				.andReturn().getResponse();
		assertStatusDeclared(canonical, "get", MOVEMENTS_PATH, 200);
		assertCanonicalResponse(canonical, "get", MOVEMENTS_PATH, 200, historyResponse);

		var deleteResponse = asOwner(delete(WISHES_PATH + "/" + destinationWishId)
				.header(HttpHeaders.IF_MATCH, "1")
				.header("Idempotency-Key", "runtime-success-delete"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.version").value(2))
				.andReturn().getResponse();
		assertStatusDeclared(canonical, "delete", WISH_PATH, 200);
		assertCanonicalResponse(canonical, "delete", WISH_PATH, 200, deleteResponse);

		var completionResponse = asOwner(post(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", "runtime-success-completion")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("COMPLETED"))
				.andReturn().getResponse();
		assertStatusDeclared(canonical, "post", WISH_PATH + "/completion", 200);
		assertCanonicalResponse(
				canonical, "post", WISH_PATH + "/completion", 200, completionResponse);

		var abandonmentResponse = asOwner(post(
				WISHES_PATH + "/" + LAPTOP_WISH_ID + "/abandonment")
				.header("Idempotency-Key", "runtime-success-abandonment")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("ABANDONED"))
				.andReturn().getResponse();
		assertStatusDeclared(canonical, "post", WISH_PATH + "/abandonment", 200);
		assertCanonicalResponse(
				canonical, "post", WISH_PATH + "/abandonment", 200, abandonmentResponse);
	}

	@Test
	void abandonedVisibilityOnlyPatchMatchesCanonicalRuntimeContract() throws Exception {
		Map<String, Object> canonical = canonicalDocument();
		asOwner(post(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/abandonment")
				.header("Idempotency-Key", "runtime-abandoned-visibility-abandonment")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.wish.state").value("ABANDONED"))
				.andExpect(jsonPath("$.wish.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.wish.version").value(1));
		long ledgerEventsBefore = jdbc.queryForObject("""
				SELECT count(*) FROM ledger_event WHERE account_id = ?
				""", Long.class, OWNER_ACCOUNT_ID);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM shared_card WHERE wish_id = ?
				""", Long.class, LAPTOP_WISH_ID)).isZero();

		clock.set(COMMAND_TIME.plusSeconds(1));
		MockHttpServletResponse patchResponse = asOwner(patch(
				WISHES_PATH + "/" + LAPTOP_WISH_ID)
				.contentType("application/merge-patch+json")
				.content("{\"expectedVersion\":1,\"visibility\":\"ACADEMY\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "false"))
				.andExpect(jsonPath("$.eventId").value((Object) null))
				.andExpect(jsonPath("$.wish.state").value("ABANDONED"))
				.andExpect(jsonPath("$.wish.visibility").value("ACADEMY"))
				.andExpect(jsonPath("$.wish.version").value(2))
				.andReturn().getResponse();

		assertStatusDeclared(canonical, "patch", WISH_PATH, 200);
		assertCanonicalResponse(canonical, "patch", WISH_PATH, 200, patchResponse);
		assertThat(jdbc.queryForMap("""
				SELECT state, visibility, updated_at, version FROM wish WHERE id = ?
				""", LAPTOP_WISH_ID))
				.containsEntry("state", "ABANDONED")
				.containsEntry("visibility", "ACADEMY")
				.containsEntry("updated_at", Timestamp.from(COMMAND_TIME.plusSeconds(1)))
				.containsEntry("version", 2L);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ledger_event WHERE account_id = ?
				""", Long.class, OWNER_ACCOUNT_ID)).isEqualTo(ledgerEventsBefore);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM shared_card WHERE wish_id = ?
				""", Long.class, LAPTOP_WISH_ID)).isZero();
	}

	@Test
	void allElevenWishOperationsEnforceCanonicalCommonErrorBoundaries()
			throws Exception {
		Map<String, Object> canonical = canonicalDocument();
		String invalid = "/v1/card-balance-accounts/not-a-uuid";
		List<OperationCase> operations = List.of(
				new OperationCase("get", WISH_COLLECTION_PATH,
						() -> get(WISHES_PATH), () -> get(invalid + "/wishes")),
				new OperationCase("post", WISH_COLLECTION_PATH,
						() -> post(WISHES_PATH)
								.header("Idempotency-Key", "runtime-common-create")
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"purpose\":\"공통 오류\",\"targetAmount\":100000}"),
						() -> post(invalid + "/wishes")
								.header("Idempotency-Key", "runtime-common-create")
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"purpose\":\"공통 오류\",\"targetAmount\":100000}")),
				new OperationCase("get", WISH_PATH,
						() -> get(WISHES_PATH + "/" + LAPTOP_WISH_ID),
						() -> get(invalid + "/wishes/" + LAPTOP_WISH_ID)),
				new OperationCase("patch", WISH_PATH,
						() -> patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
								.contentType("application/merge-patch+json")
								.content("{\"expectedVersion\":0,\"purpose\":\"공통 오류\"}"),
						() -> patch(invalid + "/wishes/" + LAPTOP_WISH_ID)
								.contentType("application/merge-patch+json")
								.content("{\"expectedVersion\":0,\"purpose\":\"공통 오류\"}")),
				new OperationCase("delete", WISH_PATH,
						() -> delete(WISHES_PATH + "/" + LAPTOP_WISH_ID)
								.header(HttpHeaders.IF_MATCH, "0")
								.header("Idempotency-Key", "runtime-common-delete"),
						() -> delete(invalid + "/wishes/" + LAPTOP_WISH_ID)
								.header(HttpHeaders.IF_MATCH, "0")
								.header("Idempotency-Key", "runtime-common-delete")),
				new OperationCase("post", WISH_PATH + "/deposits",
						() -> amountCommand(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/deposits",
								"runtime-common-deposit", 10_000, 0),
						() -> amountCommand(invalid + "/wishes/" + LAPTOP_WISH_ID + "/deposits",
								"runtime-common-deposit", 10_000, 0)),
				new OperationCase("post", WISH_PATH + "/withdrawals",
						() -> amountCommand(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/withdrawals",
								"runtime-common-withdrawal", 10_000, 0),
						() -> amountCommand(invalid + "/wishes/" + LAPTOP_WISH_ID + "/withdrawals",
								"runtime-common-withdrawal", 10_000, 0)),
				new OperationCase("post",
						"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers",
						() -> transferCommand(WISHES_PATH.replace("/wishes", "/transfers")),
						() -> transferCommand(invalid + "/transfers")),
				new OperationCase("post", WISH_PATH + "/completion",
						() -> versionCommand(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion",
								"runtime-common-completion", 0),
						() -> versionCommand(invalid + "/wishes/" + CAMP_WISH_ID + "/completion",
								"runtime-common-completion", 0)),
				new OperationCase("post", WISH_PATH + "/abandonment",
						() -> versionCommand(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/abandonment",
								"runtime-common-abandonment", 0),
						() -> versionCommand(invalid + "/wishes/" + LAPTOP_WISH_ID + "/abandonment",
								"runtime-common-abandonment", 0)),
				new OperationCase("get", MOVEMENTS_PATH,
						() -> get(WISHES_PATH + "/" + LAPTOP_WISH_ID + "/fund-movements"),
						() -> get(invalid + "/wishes/" + LAPTOP_WISH_ID + "/fund-movements")));

		for (OperationCase operation : operations) {
			resetFixture();
			assertRuntimeError(canonical, operation, 400, "MALFORMED_REQUEST",
					asOwner(operation.malformed.get()));

			resetFixture();
			assertRuntimeError(canonical, operation, 401, "AUTH_REQUIRED",
					mockMvc.perform(operation.valid.get()));

			resetFixture();
			assertRuntimeError(canonical, operation, 403, "FORBIDDEN",
					asToken(SeedFixtureCatalog.STAFF_TOKEN, operation.valid.get()));

			resetFixture();
			assertRuntimeError(canonical, operation, 404, "CARD_BALANCE_ACCOUNT_NOT_FOUND",
					asToken(SeedFixtureCatalog.FRIEND_TOKEN, operation.valid.get()));
		}
	}

	@Test
	void lifecycleOperationsExerciseEveryRealizableSpecificCanonicalError()
			throws Exception {
		Map<String, Object> canonical = canonicalDocument();

		resetFixture();
		assertOwnedError(canonical, "post", WISH_COLLECTION_PATH, 400,
				"IDEMPOTENCY_KEY_REQUIRED", post(WISHES_PATH)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"purpose\":\"키 없음\",\"targetAmount\":100000}"));
		resetFixture();
		assertOwnedError(canonical, "post", WISH_COLLECTION_PATH, 415,
				"UNSUPPORTED_MEDIA_TYPE", post(WISHES_PATH)
						.header("Idempotency-Key", "create-media")
						.contentType(MediaType.TEXT_PLAIN)
						.content("{\"purpose\":\"미디어\",\"targetAmount\":100000}"));
		resetFixture();
		assertOwnedError(canonical, "post", WISH_COLLECTION_PATH, 422,
				"INVALID_AMOUNT", post(WISHES_PATH)
						.header("Idempotency-Key", "create-amount")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"purpose\":\"금액\",\"targetAmount\":0}"));
		resetFixture();
		assertOwnedError(canonical, "post", WISH_COLLECTION_PATH, 422,
				"INVALID_PURPOSE", post(WISHES_PATH)
						.header("Idempotency-Key", "create-purpose")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"purpose\":\"line\\nbreak\",\"targetAmount\":100000}"));
		resetFixture();
		createWish("create-reused", "첫 요청", 100_000);
		assertOwnedError(canonical, "post", WISH_COLLECTION_PATH, 409,
				"IDEMPOTENCY_KEY_REUSED", post(WISHES_PATH)
						.header("Idempotency-Key", "create-reused")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"purpose\":\"다른 요청\",\"targetAmount\":100000}"));
		resetFixture();
		openMismatch();
		assertOwnedError(canonical, "post", WISH_COLLECTION_PATH, 409,
				"BALANCE_MISMATCH_LOCKED", post(WISHES_PATH)
						.header("Idempotency-Key", "create-mismatch")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"purpose\":\"잠금\",\"targetAmount\":100000}"));

		resetFixture();
		assertOwnedError(canonical, "patch", WISH_PATH, 415,
				"UNSUPPORTED_MEDIA_TYPE", patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":0,\"purpose\":\"미디어\"}"));
		resetFixture();
		assertOwnedError(canonical, "patch", WISH_PATH, 422,
				"INVALID_AMOUNT", patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.contentType("application/merge-patch+json")
						.content("{\"expectedVersion\":0,\"targetAmount\":0}"));
		resetFixture();
		assertOwnedError(canonical, "patch", WISH_PATH, 422,
				"INVALID_PURPOSE", patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.contentType("application/merge-patch+json")
						.content("{\"expectedVersion\":0,\"purpose\":\"line\\nbreak\"}"));
		resetFixture();
		assertOwnedError(canonical, "patch", WISH_PATH, 422,
				"INVALID_VERSION", patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.contentType("application/merge-patch+json")
						.content("{\"expectedVersion\":-1,\"purpose\":\"버전\"}"));
		resetFixture();
		assertOwnedError(canonical, "patch", WISH_PATH, 409,
				"VERSION_CONFLICT", patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.contentType("application/merge-patch+json")
						.content("{\"expectedVersion\":1,\"purpose\":\"버전\"}"));
		resetFixture();
		asOwner(versionCommand(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion",
				"patch-terminal-setup", 0)).andExpect(status().isOk());
		assertOwnedError(canonical, "patch", WISH_PATH, 409,
				"INVALID_STATE_TRANSITION", patch(WISHES_PATH + "/" + CAMP_WISH_ID)
						.contentType("application/merge-patch+json")
						.content("{\"expectedVersion\":1,\"purpose\":\"완료 뒤 수정\"}"));
		resetFixture();
		openMismatch();
		assertOwnedError(canonical, "patch", WISH_PATH, 409,
				"BALANCE_MISMATCH_LOCKED", patch(WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.contentType("application/merge-patch+json")
						.content("{\"expectedVersion\":0,\"purpose\":\"잠금\"}"));

		resetFixture();
		assertOwnedError(canonical, "delete", WISH_PATH, 400,
				"EXPECTED_VERSION_REQUIRED", delete(WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.header("Idempotency-Key", "delete-version-required"));
		resetFixture();
		assertOwnedError(canonical, "delete", WISH_PATH, 400,
				"IDEMPOTENCY_KEY_REQUIRED", delete(WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.header(HttpHeaders.IF_MATCH, "0"));
		resetFixture();
		assertOwnedError(canonical, "delete", WISH_PATH, 422,
				"INVALID_VERSION", delete(WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.header(HttpHeaders.IF_MATCH, "-1")
						.header("Idempotency-Key", "delete-negative-version"));
		resetFixture();
		assertOwnedError(canonical, "delete", WISH_PATH, 409,
				"VERSION_CONFLICT", delete(WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.header(HttpHeaders.IF_MATCH, "1")
						.header("Idempotency-Key", "delete-stale-version"));
		resetFixture();
		createWish("delete-reused", "키 선점", 100_000);
		assertOwnedError(canonical, "delete", WISH_PATH, 409,
				"IDEMPOTENCY_KEY_REUSED", delete(WISHES_PATH + "/" + LAPTOP_WISH_ID)
						.header(HttpHeaders.IF_MATCH, "0")
						.header("Idempotency-Key", "delete-reused"));

		for (TerminalOperation terminal : terminalOperations()) {
			resetFixture();
			assertOwnedError(canonical, "post", terminal.path, 400,
					"IDEMPOTENCY_KEY_REQUIRED", post(terminal.runtimePath)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"expectedVersion\":0}"));
			resetFixture();
			assertOwnedError(canonical, "post", terminal.path, 415,
					"UNSUPPORTED_MEDIA_TYPE", post(terminal.runtimePath)
							.header("Idempotency-Key", terminal.name + "-media")
							.contentType(MediaType.TEXT_PLAIN)
							.content("{\"expectedVersion\":0}"));
			resetFixture();
			assertOwnedError(canonical, "post", terminal.path, 422,
					"INVALID_VERSION", versionCommand(terminal.runtimePath,
							terminal.name + "-negative", -1));
			resetFixture();
			assertOwnedError(canonical, "post", terminal.path, 409,
					"VERSION_CONFLICT", versionCommand(terminal.runtimePath,
							terminal.name + "-stale", 1));
			resetFixture();
			createWish(terminal.name + "-reused", "키 선점", 100_000);
			assertOwnedError(canonical, "post", terminal.path, 409,
					"IDEMPOTENCY_KEY_REUSED", versionCommand(terminal.runtimePath,
							terminal.name + "-reused", 0));
		}

		resetFixture();
		assertOwnedError(canonical, "post", WISH_PATH + "/completion", 409,
				"INVALID_STATE_TRANSITION", versionCommand(
						WISHES_PATH + "/" + LAPTOP_WISH_ID + "/completion",
						"completion-invalid-state", 0));
		resetFixture();
		asOwner(versionCommand(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion",
				"abandon-terminal-setup", 0)).andExpect(status().isOk());
		assertOwnedError(canonical, "post", WISH_PATH + "/abandonment", 409,
				"INVALID_STATE_TRANSITION", versionCommand(
						WISHES_PATH + "/" + CAMP_WISH_ID + "/abandonment",
						"abandon-invalid-state", 1));
	}

	@Test
	void fundMovementOperationsExerciseEveryRealizableSpecificCanonicalError()
			throws Exception {
		Map<String, Object> canonical = canonicalDocument();
		String deposits = WISHES_PATH + "/" + LAPTOP_WISH_ID + "/deposits";
		String withdrawals = WISHES_PATH + "/" + LAPTOP_WISH_ID + "/withdrawals";
		String transfers = WISHES_PATH.replace("/wishes", "/transfers");

		resetFixture();
		assertOwnedError(canonical, "post", WISH_PATH + "/deposits", 400,
				"IDEMPOTENCY_KEY_REQUIRED", post(deposits)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":1,\"expectedVersion\":0}"));
		resetFixture();
		assertOwnedError(canonical, "post", WISH_PATH + "/deposits", 422,
				"INVALID_AMOUNT", amountCommand(deposits, "deposit-invalid-amount", 0, 0));
		resetFixture();
		assertOwnedError(canonical, "post", WISH_PATH + "/deposits", 422,
				"INVALID_VERSION", amountCommand(deposits, "deposit-invalid-version", 1, -1));
		resetFixture();
		assertOwnedError(canonical, "post", WISH_PATH + "/deposits", 409,
				"VERSION_CONFLICT", amountCommand(deposits, "deposit-stale", 1, 1));
		resetFixture();
		asOwner(versionCommand(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion",
				"deposit-terminal-setup", 0)).andExpect(status().isOk());
		assertOwnedError(canonical, "post", WISH_PATH + "/deposits", 409,
				"INVALID_STATE_TRANSITION", amountCommand(
						WISHES_PATH + "/" + CAMP_WISH_ID + "/deposits",
						"deposit-terminal", 1, 1));
		resetFixture();
		openMismatch();
		assertOwnedError(canonical, "post", WISH_PATH + "/deposits", 409,
				"BALANCE_MISMATCH_LOCKED", amountCommand(
						deposits, "deposit-mismatch", 1, 0));
		resetFixture();
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":850000}]");
		assertOwnedError(canonical, "post", WISH_PATH + "/deposits", 409,
				"INSUFFICIENT_AVAILABLE_BALANCE", amountCommand(
						deposits, "deposit-insufficient-available", 100_001, 0));
		resetFixture();
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":3000000}]");
		assertOwnedError(canonical, "post", WISH_PATH + "/deposits", 409,
				"TARGET_AMOUNT_EXCEEDED", amountCommand(
						deposits, "deposit-target-exceeded", 1_250_001, 0));
		resetFixture();
		createWish("deposit-reused", "키 선점", 100_000);
		assertOwnedError(canonical, "post", WISH_PATH + "/deposits", 409,
				"IDEMPOTENCY_KEY_REUSED", amountCommand(
						deposits, "deposit-reused", 1, 0));
		resetFixture();
		setBalanceScenario("[{\"type\":\"FAILURE\"}]");
		assertOwnedError(canonical, "post", WISH_PATH + "/deposits", 503,
				"BALANCE_SYNC_FAILED", amountCommand(
						deposits, "deposit-provider-failure", 1, 0));

		resetFixture();
		assertOwnedError(canonical, "post", WISH_PATH + "/withdrawals", 400,
				"IDEMPOTENCY_KEY_REQUIRED", post(withdrawals)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":1,\"expectedVersion\":0}"));
		resetFixture();
		assertOwnedError(canonical, "post", WISH_PATH + "/withdrawals", 422,
				"INVALID_AMOUNT", amountCommand(withdrawals, "withdraw-invalid-amount", 0, 0));
		resetFixture();
		assertOwnedError(canonical, "post", WISH_PATH + "/withdrawals", 422,
				"INVALID_VERSION", amountCommand(withdrawals, "withdraw-invalid-version", 1, -1));
		resetFixture();
		assertOwnedError(canonical, "post", WISH_PATH + "/withdrawals", 409,
				"VERSION_CONFLICT", amountCommand(withdrawals, "withdraw-stale", 1, 1));
		resetFixture();
		asOwner(versionCommand(WISHES_PATH + "/" + CAMP_WISH_ID + "/completion",
				"withdraw-terminal-setup", 0)).andExpect(status().isOk());
		assertOwnedError(canonical, "post", WISH_PATH + "/withdrawals", 409,
				"INVALID_STATE_TRANSITION", amountCommand(
						WISHES_PATH + "/" + CAMP_WISH_ID + "/withdrawals",
						"withdraw-terminal", 1, 1));
		resetFixture();
		assertOwnedError(canonical, "post", WISH_PATH + "/withdrawals", 409,
				"INSUFFICIENT_WISH_AMOUNT", amountCommand(
						withdrawals, "withdraw-insufficient", 250_001, 0));
		resetFixture();
		createWish("withdraw-reused", "키 선점", 100_000);
		assertOwnedError(canonical, "post", WISH_PATH + "/withdrawals", 409,
				"IDEMPOTENCY_KEY_REUSED", amountCommand(
						withdrawals, "withdraw-reused", 1, 0));

		resetFixture();
		assertOwnedError(canonical, "post",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 400,
				"IDEMPOTENCY_KEY_REQUIRED", post(transfers)
						.contentType(MediaType.APPLICATION_JSON)
						.content(transferBody(LAPTOP_WISH_ID, CAMP_WISH_ID, 1, 0, 0)));
		resetFixture();
		assertOwnedError(canonical, "post",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 422,
				"INVALID_AMOUNT", transferCommand(transfers, "transfer-invalid-amount",
						LAPTOP_WISH_ID, CAMP_WISH_ID, 0, 0, 0));
		resetFixture();
		assertOwnedError(canonical, "post",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 422,
				"INVALID_VERSION", transferCommand(transfers, "transfer-invalid-version",
						LAPTOP_WISH_ID, CAMP_WISH_ID, 1, -1, 0));
		resetFixture();
		assertOwnedError(canonical, "post",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 409,
				"VERSION_CONFLICT", transferCommand(transfers, "transfer-stale",
						LAPTOP_WISH_ID, CAMP_WISH_ID, 1, 1, 0));
		resetFixture();
		assertOwnedError(canonical, "post",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 409,
				"INVALID_STATE_TRANSITION", transferCommand(transfers, "transfer-identical",
						LAPTOP_WISH_ID, LAPTOP_WISH_ID, 1, 0, 0));
		resetFixture();
		String smallDestination = createWish("transfer-small", "작은 목표", 99_999);
		assertOwnedError(canonical, "post",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 409,
				"TARGET_AMOUNT_EXCEEDED", transferCommand(transfers, "transfer-target-exceeded",
						LAPTOP_WISH_ID, UUID.fromString(smallDestination), 100_000, 0, 0));
		resetFixture();
		String largeDestination = createWish("transfer-large", "큰 목표", 500_000);
		assertOwnedError(canonical, "post",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 409,
				"INSUFFICIENT_WISH_AMOUNT", transferCommand(transfers, "transfer-insufficient",
						LAPTOP_WISH_ID, UUID.fromString(largeDestination), 250_001, 0, 0));
		resetFixture();
		String mismatchDestination = createWish(
				"transfer-mismatch-destination", "조정 중 대상", 100_000);
		openMismatch();
		assertOwnedError(canonical, "post",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 409,
				"BALANCE_MISMATCH_LOCKED", transferCommand(transfers, "transfer-mismatch",
						LAPTOP_WISH_ID, UUID.fromString(mismatchDestination), 1, 0, 0));
		resetFixture();
		createWish("transfer-reused", "키 선점", 100_000);
		assertOwnedError(canonical, "post",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 409,
				"IDEMPOTENCY_KEY_REUSED", transferCommand(transfers, "transfer-reused",
						LAPTOP_WISH_ID, CAMP_WISH_ID, 1, 0, 0));

		resetFixture();
		UUID otherAccountId = UUID.randomUUID();
		UUID otherWishId = UUID.randomUUID();
		insertCrossAccountWish(otherAccountId, otherWishId);
		try {
			assertOwnedError(canonical, "post",
					"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers", 409,
					"CROSS_ACCOUNT_TRANSFER_FORBIDDEN", transferCommand(transfers,
							"transfer-cross-account", LAPTOP_WISH_ID, otherWishId, 1, 0, 0));
		} finally {
			jdbc.update("DELETE FROM wish WHERE id = ?", otherWishId);
			jdbc.update("DELETE FROM card_balance_account WHERE id = ?", otherAccountId);
		}
	}

	@Test
	void everyOwnedWishTargetOperationReturnsItsCanonicalWishNotFoundCode()
			throws Exception {
		Map<String, Object> canonical = canonicalDocument();
		UUID missing = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
		String missingPath = WISHES_PATH + "/" + missing;
		String transfers = WISHES_PATH.replace("/wishes", "/transfers");

		List<MissingWishCase> cases = List.of(
				new MissingWishCase("get", WISH_PATH, () -> get(missingPath)),
				new MissingWishCase("patch", WISH_PATH, () -> patch(missingPath)
						.contentType("application/merge-patch+json")
						.content("{\"expectedVersion\":0,\"purpose\":\"없음\"}")),
				new MissingWishCase("delete", WISH_PATH, () -> delete(missingPath)
						.header(HttpHeaders.IF_MATCH, "0")
						.header("Idempotency-Key", "missing-delete")),
				new MissingWishCase("post", WISH_PATH + "/deposits", () -> amountCommand(
						missingPath + "/deposits", "missing-deposit", 1, 0)),
				new MissingWishCase("post", WISH_PATH + "/withdrawals", () -> amountCommand(
						missingPath + "/withdrawals", "missing-withdrawal", 1, 0)),
				new MissingWishCase("post", WISH_PATH + "/completion", () -> versionCommand(
						missingPath + "/completion", "missing-completion", 0)),
				new MissingWishCase("post", WISH_PATH + "/abandonment", () -> versionCommand(
						missingPath + "/abandonment", "missing-abandonment", 0)),
				new MissingWishCase("get", MOVEMENTS_PATH,
						() -> get(missingPath + "/fund-movements")),
				new MissingWishCase("post",
						"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers",
						() -> transferCommand(transfers, "missing-transfer", missing,
								CAMP_WISH_ID, 1, 0, 0)));

		for (MissingWishCase testCase : cases) {
			resetFixture();
			assertOwnedError(canonical, testCase.method, testCase.path, 404,
					"WISH_NOT_FOUND", testCase.request.get());
		}
	}

	@Test
	void canonicalGeneratedAndPostgresBackedRuntimeStayCompatible() throws Exception {
		Map<String, Object> canonical = canonicalDocument();
		String generatedBody = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		Map<String, Object> generated = JsonPath.read(generatedBody, "$");

		Map<String, Map<String, Object>> canonicalOperations = operations(canonical);
		Map<String, Map<String, Object>> generatedOperations = new LinkedHashMap<>();
		operations(generated).forEach((key, value) -> {
			if (!key.contains(" /e2e/")) generatedOperations.put(key, value);
		});
		assertThat(generatedOperations.keySet())
				.containsExactlyInAnyOrderElementsOf(canonicalOperations.keySet());

		canonicalOperations.forEach((key, expected) -> {
			Map<String, Object> actual = generatedOperations.get(key);
			assertThat(actual.get("operationId")).as(key + " operationId")
					.isEqualTo(expected.get("operationId"));
			assertThat(map(actual.get("responses")).keySet()).as(key + " statuses")
					.containsExactlyInAnyOrderElementsOf(map(expected.get("responses")).keySet());
			assertThat(list(actual.get("security"))).as(key + " security")
					.containsExactlyElementsOf(list(expected.get("security")));
		});

		for (String schema : List.of(
				"Wish", "ErrorEnvelope", "ProgressSharedCard", "CompletionSharedCard")) {
			assertThat(schemaProperties(generated, schema)).as(schema + " runtime schema")
					.containsExactlyInAnyOrderElementsOf(schemaProperties(canonical, schema));
		}

		String wishBody = asOwner(get(WISHES_PATH + "/" + LAPTOP_WISH_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(LAPTOP_WISH_ID.toString()))
				.andReturn().getResponse().getContentAsString();
		assertThat(OpenApiRuntimeCompatibilityIT.<String, Object>map(JsonPath.read(wishBody, "$"))
				.keySet()).containsExactlyInAnyOrderElementsOf(schemaProperties(canonical, "Wish"));

		String unauthorizedBody = mockMvc.perform(get(WISHES_PATH + "/" + LAPTOP_WISH_ID))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andReturn().getResponse().getContentAsString();
		String actualErrorCode = JsonPath.read(unauthorizedBody, "$.error.code");
		assertThat(errorCodes(canonical, response(canonical, "get",
				"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}", "401")))
				.as("GET Wish 401 canonical error codes")
				.contains(actualErrorCode);
	}

	@Test
	void generatedPublicDocumentExcludesIsolatedE2eControls() throws Exception {
		assumeTrue(CardBalanceScenarioController.class.isAnnotationPresent(Hidden.class),
				"Public-document exclusion is enforced after @Hidden materialization.");
		String generatedBody = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		Map<String, Object> generated = JsonPath.read(generatedBody, "$");
		assertThat(OpenApiRuntimeCompatibilityIT.<String, Object>map(generated.get("paths")).keySet())
				.noneMatch(path -> path.startsWith("/e2e"));
	}

	private static Map<String, Map<String, Object>> operations(Map<String, Object> document) {
		Map<String, Map<String, Object>> result = new LinkedHashMap<>();
		OpenApiRuntimeCompatibilityIT.<String, Object>map(document.get("paths"))
				.forEach((path, pathValue) ->
				OpenApiRuntimeCompatibilityIT.<String, Object>map(pathValue)
						.forEach((method, operation) -> {
					if (HTTP_METHODS.contains(method)) {
						result.put(method.toUpperCase() + " " + path, map(operation));
					}
				}));
		return result;
	}

	private static Map<String, Object> canonicalDocument() throws Exception {
		try (InputStream input = Files.newInputStream(Path.of("api", "openapi.yaml"))) {
			return map(new Yaml().load(input));
		}
	}

	private static void assertStatusDeclared(
			Map<String, Object> canonical, String method, String path, int statusCode) {
		assertThat(response(canonical, method, path, Integer.toString(statusCode)))
				.as(method.toUpperCase() + " " + path + " " + statusCode)
				.isNotNull();
	}

	private static void assertCanonicalResponse(
			Map<String, Object> canonical,
			String method,
			String path,
			int statusCode,
			MockHttpServletResponse actual) throws Exception {
		String label = method.toUpperCase() + " " + path + " " + statusCode;
		Map<String, Object> definition = resolvedSchema(canonical,
				map(response(canonical, method, path, Integer.toString(statusCode))));
		Map<String, Object> content = map(definition.get("content"));
		assertThat(content).as(label + " content").containsKey(MediaType.APPLICATION_JSON_VALUE);
		Map<String, Object> mediaType = map(content.get(MediaType.APPLICATION_JSON_VALUE));
		Map<String, Object> schema = map(mediaType.get("schema"));
		Object body = JsonPath.read(actual.getContentAsString(), "$");
		assertSchema(canonical, schema, body, label + " body");

		Map<String, Object> headers = definition.get("headers") instanceof Map<?, ?>
				? map(definition.get("headers")) : Map.of();
		headers.forEach((name, rawHeader) -> {
			String wireValue = actual.getHeader(name);
			assertThat(wireValue).as(label + " header " + name).isNotNull();
			Map<String, Object> header = resolvedSchema(canonical, map(rawHeader));
			Map<String, Object> headerSchema = map(header.get("schema"));
			assertSchema(canonical, headerSchema,
					coerceHeader(wireValue, resolvedSchema(canonical, headerSchema)),
					label + " header " + name);
		});
	}

	private static Object coerceHeader(String value, Map<String, Object> schema) {
		Object type = schema.get("type");
		if (Objects.equals(type, "boolean")) return Boolean.valueOf(value);
		if (Objects.equals(type, "integer")) return Long.valueOf(value);
		return value;
	}

	private static void assertSchema(
			Map<String, Object> document,
			Map<String, Object> rawSchema,
			Object value,
			String path) {
		Map<String, Object> schema = resolvedSchema(document, rawSchema);
		if (schema.get("allOf") instanceof List<?>) {
			for (Object part : list(schema.get("allOf"))) {
				assertSchema(document, map(part), value, path);
			}
		}
		if (schema.get("oneOf") instanceof List<?>) {
			assertSchema(document, selectOneOf(document, schema, value, path), value, path);
		}
		if (schema.containsKey("const")) {
			assertThat(valuesEqual(value, schema.get("const"))).as(path + " const").isTrue();
		}
		if (schema.get("enum") instanceof List<?>) {
			assertThat(list(schema.get("enum")).stream()
					.anyMatch(candidate -> valuesEqual(value, candidate)))
					.as(path + " enum").isTrue();
		}

		List<Object> allowedTypes = schema.get("type") instanceof List<?>
				? list(schema.get("type"))
				: schema.containsKey("type") ? List.of(schema.get("type")) : List.of();
		if (!allowedTypes.isEmpty()) {
			assertThat(allowedTypes.stream().anyMatch(type -> matchesType(type, value)))
					.as(path + " type " + allowedTypes).isTrue();
		}
		if (value == null) return;

		if (value instanceof Map<?, ?> object) {
			Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?>
					? map(schema.get("properties")) : Map.of();
			List<Object> required = schema.get("required") instanceof List<?>
					? list(schema.get("required")) : List.of();
			for (Object name : required) {
				assertThat(object.containsKey(name)).as(path + " required " + name).isTrue();
			}
			if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
				assertThat(object.keySet()).as(path + " additionalProperties")
						.allMatch(properties::containsKey);
			}
			object.forEach((name, child) -> {
				Object propertySchema = properties.get(name);
				if (propertySchema != null) {
					assertSchema(document, map(propertySchema), child, path + "." + name);
				}
			});
		}
		if (value instanceof List<?> array && schema.get("items") instanceof Map<?, ?>) {
			for (int index = 0; index < array.size(); index++) {
				assertSchema(document, map(schema.get("items")), array.get(index),
						path + "[" + index + "]");
			}
		}
		if (value instanceof String text) {
			if (schema.get("minLength") instanceof Number minimum) {
				assertThat(text.codePointCount(0, text.length())).as(path + " minLength")
						.isGreaterThanOrEqualTo(minimum.intValue());
			}
			if (schema.get("maxLength") instanceof Number maximum) {
				assertThat(text.codePointCount(0, text.length())).as(path + " maxLength")
						.isLessThanOrEqualTo(maximum.intValue());
			}
			if (schema.get("pattern") instanceof String pattern) {
				assertThat(java.util.regex.Pattern.compile(pattern).matcher(text).find())
						.as(path + " pattern").isTrue();
			}
			if (Objects.equals(schema.get("format"), "uuid")) {
				assertThat(UUID.fromString(text).toString()).as(path + " uuid").isEqualTo(text);
			} else if (Objects.equals(schema.get("format"), "date")) {
				assertThat(LocalDate.parse(text).toString()).as(path + " date").isEqualTo(text);
			} else if (Objects.equals(schema.get("format"), "date-time")) {
				assertThat(Instant.parse(text)).as(path + " date-time").isNotNull();
			}
		}
		if (value instanceof Number number) {
			BigDecimal decimal = new BigDecimal(number.toString());
			if (schema.get("minimum") instanceof Number minimum) {
				assertThat(decimal).as(path + " minimum")
						.isGreaterThanOrEqualTo(new BigDecimal(minimum.toString()));
			}
			if (schema.get("maximum") instanceof Number maximum) {
				assertThat(decimal).as(path + " maximum")
						.isLessThanOrEqualTo(new BigDecimal(maximum.toString()));
			}
		}
	}

	private static Map<String, Object> selectOneOf(
			Map<String, Object> document,
			Map<String, Object> schema,
			Object value,
			String path) {
		List<Object> candidates = list(schema.get("oneOf"));
		if (schema.get("discriminator") instanceof Map<?, ?> discriminator
				&& value instanceof Map<?, ?> object) {
			String property = (String) discriminator.get("propertyName");
			Object discriminatorValue = object.get(property);
			Map<String, Object> mapping = map(discriminator.get("mapping"));
			assertThat(mapping.containsKey(discriminatorValue))
					.as(path + " discriminator mapping").isTrue();
			return Map.of("$ref", mapping.get(discriminatorValue));
		}
		List<Map<String, Object>> typeMatches = candidates.stream()
				.map(OpenApiRuntimeCompatibilityIT::<String, Object>map)
				.filter(candidate -> acceptsType(document, candidate, value))
				.toList();
		assertThat(typeMatches).as(path + " oneOf selection").hasSize(1);
		return typeMatches.getFirst();
	}

	private static boolean acceptsType(
			Map<String, Object> document, Map<String, Object> rawSchema, Object value) {
		Map<String, Object> schema = resolvedSchema(document, rawSchema);
		Object type = schema.get("type");
		if (type instanceof List<?>) {
			return list(type).stream().anyMatch(candidate -> matchesType(candidate, value));
		}
		if (type != null) return matchesType(type, value);
		return value instanceof Map<?, ?> && (schema.containsKey("properties")
				|| schema.containsKey("required"));
	}

	private static boolean matchesType(Object type, Object value) {
		return switch (String.valueOf(type)) {
			case "null" -> value == null;
			case "object" -> value instanceof Map<?, ?>;
			case "array" -> value instanceof List<?>;
			case "string" -> value instanceof String;
			case "boolean" -> value instanceof Boolean;
			case "number" -> value instanceof Number;
			case "integer" -> value instanceof Byte || value instanceof Short
					|| value instanceof Integer || value instanceof Long
					|| value instanceof java.math.BigInteger;
			default -> false;
		};
	}

	private static boolean valuesEqual(Object actual, Object expected) {
		if (actual instanceof Number actualNumber && expected instanceof Number expectedNumber) {
			return new BigDecimal(actualNumber.toString())
					.compareTo(new BigDecimal(expectedNumber.toString())) == 0;
		}
		return Objects.equals(actual, expected);
	}

	private static Map<String, Object> resolvedSchema(
			Map<String, Object> document, Map<String, Object> rawSchema) {
		if (rawSchema == null || !(rawSchema.get("$ref") instanceof String)) return rawSchema;
		Map<String, Object> merged = new LinkedHashMap<>(resolve(document, rawSchema));
		rawSchema.forEach((key, value) -> {
			if (!key.equals("$ref")) merged.put(key, value);
		});
		return merged;
	}

	private void assertRuntimeError(
			Map<String, Object> canonical,
			OperationCase operation,
			int expectedStatus,
			String expectedCode,
			ResultActions action) throws Exception {
		String body = action.andExpect(status().is(expectedStatus))
				.andExpect(jsonPath("$.error.code").value(expectedCode))
				.andReturn().getResponse().getContentAsString();
		String actualCode = JsonPath.read(body, "$.error.code");
		assertThat(errorCodes(canonical, response(canonical,
				operation.method, operation.path, Integer.toString(expectedStatus))))
				.as(operation.method.toUpperCase() + " " + operation.path + " " + expectedStatus)
				.contains(actualCode);
	}

	private void assertOwnedError(
			Map<String, Object> canonical,
			String method,
			String path,
			int expectedStatus,
			String expectedCode,
			MockHttpServletRequestBuilder request) throws Exception {
		assertRuntimeError(canonical,
				new OperationCase(method, path, null, null),
				expectedStatus, expectedCode, asOwner(request));
	}

	private void openMismatch() throws Exception {
		setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":800000},"
				+ "{\"type\":\"SUCCESS\",\"balance\":700000}]");
		for (int attempt = 0; attempt < 2; attempt++) {
			asOwner(post("/v1/card-balance-accounts/{accountId}/balance-refreshes",
					OWNER_ACCOUNT_ID)).andExpect(status().isOk());
		}
	}

	private static List<TerminalOperation> terminalOperations() {
		return List.of(
				new TerminalOperation("completion", WISH_PATH + "/completion",
						WISHES_PATH + "/" + CAMP_WISH_ID + "/completion"),
				new TerminalOperation("abandonment", WISH_PATH + "/abandonment",
						WISHES_PATH + "/" + LAPTOP_WISH_ID + "/abandonment"));
	}

	private static MockHttpServletRequestBuilder amountCommand(
			String path, String key, long amount, long version) {
		return post(path)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":%d,\"expectedVersion\":%d}".formatted(amount, version));
	}

	private static MockHttpServletRequestBuilder versionCommand(
			String path, String key, long version) {
		return post(path)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":%d}".formatted(version));
	}

	private static MockHttpServletRequestBuilder transferCommand(String path) {
		return transferCommand(path, "runtime-common-transfer",
				LAPTOP_WISH_ID, CAMP_WISH_ID, 10_000, 0, 0);
	}

	private static MockHttpServletRequestBuilder transferCommand(
			String path,
			String key,
			UUID sourceWishId,
			UUID destinationWishId,
			long amount,
			long sourceVersion,
			long destinationVersion) {
		return post(path)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(transferBody(sourceWishId, destinationWishId, amount,
						sourceVersion, destinationVersion));
	}

	private static String transferBody(
			UUID sourceWishId,
			UUID destinationWishId,
			long amount,
			long sourceVersion,
			long destinationVersion) {
		return """
				{"sourceWishId":"%s","destinationWishId":"%s","amount":%d,
				 "sourceExpectedVersion":%d,"destinationExpectedVersion":%d}
				""".formatted(sourceWishId, destinationWishId, amount,
				sourceVersion, destinationVersion);
	}

	private void insertCrossAccountWish(UUID accountId, UUID wishId) {
		jdbc.update("""
				INSERT INTO card_balance_account
				(id, student_id, academy_id, opened_at, closed_at, balance_lookup_version, version)
				VALUES (?, ?, ?, ?, NULL, 0, 0)
				""", accountId, SeedFixtureCatalog.FRIEND_ID,
				SeedFixtureCatalog.PRIMARY_ACADEMY_ID, Timestamp.from(COMMAND_TIME));
		jdbc.update("""
				INSERT INTO wish
				(id, account_id, academy_id, purpose, target_amount, wish_amount, state,
				 visibility, created_at, version)
				VALUES (?, ?, ?, '다른 계정 위시', 100000, 0, 'IN_PROGRESS', 'PRIVATE', ?, 0)
				""", wishId, accountId, SeedFixtureCatalog.PRIMARY_ACADEMY_ID,
				Timestamp.from(COMMAND_TIME));
	}

	private record OperationCase(
			String method,
			String path,
			Supplier<MockHttpServletRequestBuilder> valid,
			Supplier<MockHttpServletRequestBuilder> malformed) {
	}

	private record TerminalOperation(String name, String path, String runtimePath) {
	}

	private record MissingWishCase(
			String method,
			String path,
			Supplier<MockHttpServletRequestBuilder> request) {
	}

	private static Set<String> schemaProperties(Map<String, Object> document, String name) {
		Map<String, Object> components = map(document.get("components"));
		Map<String, Object> schemas = map(components.get("schemas"));
		Map<String, Object> schema = map(schemas.get(name));
		return schema.containsKey("properties")
				? OpenApiRuntimeCompatibilityIT.<String, Object>map(schema.get("properties")).keySet()
				: Set.of();
	}

	private static List<Object> errorCodes(Map<String, Object> document, Object responseValue) {
		Map<String, Object> response = resolve(document, map(responseValue));
		Object codes = response.get("x-error-codes");
		return codes == null ? List.of() : list(codes);
	}

	private static Object response(
			Map<String, Object> document, String method, String path, String status) {
		Map<String, Object> pathItem = map(map(document.get("paths")).get(path));
		Map<String, Object> operation = map(pathItem.get(method));
		return map(operation.get("responses")).get(status);
	}

	private static Map<String, Object> resolve(
			Map<String, Object> document, Map<String, Object> value) {
		Object reference = value.get("$ref");
		if (!(reference instanceof String path) || !path.startsWith("#/")) return value;
		Object current = document;
		for (String segment : path.substring(2).split("/")) current = map(current).get(segment);
		return map(current);
	}

	@SuppressWarnings("unchecked")
	private static <K, V> Map<K, V> map(Object value) {
		return (Map<K, V>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return (List<Object>) value;
	}
}
