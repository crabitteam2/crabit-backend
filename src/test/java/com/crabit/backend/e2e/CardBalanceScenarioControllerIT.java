package com.crabit.backend.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.crabit.backend.balance.CardBalanceProviderResult;
import com.crabit.backend.balance.DeterministicCardBalanceAdapter;
import com.crabit.backend.wish.KrwAmount;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class CardBalanceScenarioControllerIT {

	private static final UUID FIRST_ACCOUNT =
			UUID.fromString("11111111-1111-4111-8111-111111111111");
	private static final UUID SECOND_ACCOUNT =
			UUID.fromString("22222222-2222-4222-8222-222222222222");
	private static final String PATH =
			"/e2e/card-balance-accounts/{accountId}/balance-scenario";

	private DeterministicCardBalanceAdapter adapter;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		adapter = new DeterministicCardBalanceAdapter();
		CardBalanceScenarioController controller = new CardBalanceScenarioController(
				adapter, JsonMapper.builder().build());
		SeedBearerAuthenticationFilter filter = new SeedBearerAuthenticationFilter(
				new SeedTokenRegistry(new SeedFixtureCatalog()));
		mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build();
	}

	@Test
	void replacesReadsConsumesAndDeletesOnlyTheNamedAccountsOrderedScenario() throws Exception {
		adapter.replace(SECOND_ACCOUNT, List.of(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(999))));

		mvc.perform(put(PATH, FIRST_ACCOUNT)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"steps":[
						  {"type":"SUCCESS","balance":100000},
						  {"type":"FAILURE"},
						  {"type":"SUCCESS","balance":125000}
						]}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cardBalanceAccountId").value(FIRST_ACCOUNT.toString()))
				.andExpect(jsonPath("$.steps.length()").value(3))
				.andExpect(jsonPath("$.steps[0].type").value("SUCCESS"))
				.andExpect(jsonPath("$.steps[0].balance").value(100000))
				.andExpect(jsonPath("$.steps[1].type").value("FAILURE"))
				.andExpect(jsonPath("$.steps[1].balance").doesNotExist())
				.andExpect(jsonPath("$.steps[2].balance").value(125000));

		for (int invocation = 0; invocation < 2; invocation++) {
			mvc.perform(get(PATH, FIRST_ACCOUNT))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.steps.length()").value(3))
					.andExpect(jsonPath("$.steps[0].balance").value(100000));
		}

		assertThat(adapter.lookup(FIRST_ACCOUNT)).isEqualTo(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(100000)));
		mvc.perform(get(PATH, FIRST_ACCOUNT))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps.length()").value(2))
				.andExpect(jsonPath("$.steps[0].type").value("FAILURE"))
				.andExpect(jsonPath("$.steps[1].balance").value(125000));
		assertThat(adapter.lookup(FIRST_ACCOUNT)).isEqualTo(CardBalanceProviderResult.failure());
		assertThat(adapter.lookup(FIRST_ACCOUNT)).isEqualTo(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(125000)));
		assertThat(adapter.lookup(FIRST_ACCOUNT)).isEqualTo(CardBalanceProviderResult.failure());
		mvc.perform(get(PATH, FIRST_ACCOUNT))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps").isEmpty());

		mvc.perform(delete(PATH, FIRST_ACCOUNT))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
		mvc.perform(delete(PATH, FIRST_ACCOUNT))
				.andExpect(status().isNoContent());
		mvc.perform(get(PATH, FIRST_ACCOUNT))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps").isEmpty());
		assertThat(adapter.remaining(SECOND_ACCOUNT)).containsExactly(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(999)));
	}

	@Test
	void acceptsZeroAndTheMaximumJavaScriptSafeBalance() throws Exception {
		mvc.perform(put(PATH, FIRST_ACCOUNT)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"steps":[
						  {"type":"SUCCESS","balance":0},
						  {"type":"SUCCESS","balance":9007199254740991}
						]}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.steps[0].balance").value(0))
				.andExpect(jsonPath("$.steps[1].balance").value(9007199254740991L));
	}

	@Test
	void rejectsEveryInvalidStrictStepShapeWithoutReplacingThePreviousScenario() throws Exception {
		adapter.replace(FIRST_ACCOUNT, List.of(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(77))));
		List<InvalidScenario> cases = List.of(
				new InvalidScenario("{\"steps\":[]}", "steps"),
				new InvalidScenario(
						"{\"steps\":[{\"type\":\"FAILURE\"}],\"extra\":true}", "extra"),
				new InvalidScenario("{\"steps\":[{}]}", "steps[0].type"),
				new InvalidScenario(
						"{\"steps\":[{\"type\":\"UNKNOWN\"}]}", "steps[0].type"),
				new InvalidScenario(
						"{\"steps\":[{\"type\":\"SUCCESS\"}]}", "steps[0].balance"),
				new InvalidScenario(
						"{\"steps\":[{\"type\":\"SUCCESS\",\"balance\":-1}]}",
						"steps[0].balance"),
				new InvalidScenario(
						"{\"steps\":[{\"type\":\"SUCCESS\",\"balance\":1.5}]}",
						"steps[0].balance"),
				new InvalidScenario(
						"{\"steps\":[{\"type\":\"SUCCESS\",\"balance\":\"10\"}]}",
						"steps[0].balance"),
				new InvalidScenario(
						"{\"steps\":[{\"type\":\"SUCCESS\",\"balance\":9007199254740992}]}",
						"steps[0].balance"),
				new InvalidScenario(
						"{\"steps\":[{\"type\":\"SUCCESS\",\"balance\":10,\"extra\":true}]}",
						"steps[0].extra"),
				new InvalidScenario(
						"{\"steps\":[{\"type\":\"FAILURE\",\"balance\":10}]}",
						"steps[0].balance"));

		for (InvalidScenario invalid : cases) {
			mvc.perform(put(PATH, FIRST_ACCOUNT)
					.contentType(MediaType.APPLICATION_JSON)
					.content(invalid.body()))
					.andExpect(status().isUnprocessableContent())
					.andExpect(jsonPath("$.error.code").value("INVALID_BALANCE_SCENARIO"))
					.andExpect(jsonPath("$.error.message")
							.value("The balance scenario is invalid."))
					.andExpect(jsonPath("$.error.retryable").value(false))
					.andExpect(jsonPath("$.error.traceId").isNotEmpty())
					.andExpect(jsonPath("$.error.fieldErrors[0].field").value(invalid.field()))
					.andExpect(jsonPath("$.error.details").isMap());
		}

		assertThat(adapter.remaining(FIRST_ACCOUNT)).containsExactly(
				new CardBalanceProviderResult.Success(KrwAmount.nonNegative(77)));
	}

	@Test
	void returnsStableMalformedAndMediaTypeErrors() throws Exception {
		mvc.perform(put(PATH, FIRST_ACCOUNT)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
				.andExpect(jsonPath("$.error.message").value("The request is malformed."))
				.andExpect(jsonPath("$.error.fieldErrors").isEmpty());

		mvc.perform(put(PATH, FIRST_ACCOUNT)
				.contentType(MediaType.APPLICATION_JSON)
				.content("[]"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));

		mvc.perform(get(PATH, "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));

		mvc.perform(put(PATH, FIRST_ACCOUNT)
				.contentType(MediaType.TEXT_PLAIN)
				.content("{\"steps\":[{\"type\":\"FAILURE\"}]}"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"))
				.andExpect(jsonPath("$.error.message")
						.value("Content-Type must be application/json."))
				.andExpect(jsonPath("$.error.fieldErrors").isEmpty());

		mvc.perform(put(PATH, FIRST_ACCOUNT)
				.content("{\"steps\":[{\"type\":\"FAILURE\"}]}"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	private record InvalidScenario(String body, String field) {
	}
}
