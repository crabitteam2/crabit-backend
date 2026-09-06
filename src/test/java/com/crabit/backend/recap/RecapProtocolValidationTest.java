package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RecapProtocolValidationTest {
	private static final ObjectMapper JSON = new ObjectMapper();

	@Test void realHttpRejectsMalformedAndContractViolatingSuccessBodiesWithoutRetryOrUncaughtExceptions() throws Exception {
		var response = new AtomicReference<String>();
		var received = new AtomicReference<String>();
		var idempotency = new AtomicReference<String>();
		HttpServer server = server(response, received, idempotency);
		try {
			var client = client(server); var claim = claim(RecapKind.WEEKLY);
			List<String> invalid = new ArrayList<>(List.of("null", "true", "[]", "\"scalar\"", "{}", "{", "{} {}"));
			String weekly = RecapProtocolFixtures.WEEKLY_RESPONSE;
			invalid.add(weekly.replace("\"net_savings\": 0", "\"net_savings\": 9007199254740990.5"));
			invalid.add(weekly.replace("\"net_savings\": 0", "\"net_savings\": 1e-999"));
			invalid.add(weekly.replace("\"schema_version\": 1", "\"schema_version\": 1, \"schema_version\": 1"));
			invalid.add(weekly.replace("\"save_count\": 0", "\"save_count\": 0, \"save_count\": 0"));
			invalid.add(change(RecapKind.WEEKLY, root -> root.put("unknown", 1)));
			invalid.add(change(RecapKind.WEEKLY, root -> root.remove("algorithm_version")));
			for (String field : List.of("generation_id", "input_digest", "student_id", "card_balance_account_id", "academy_id", "kind", "schema_version", "algorithm_version"))
				invalid.add(change(RecapKind.WEEKLY, root -> root.put(field, "wrong")));
			invalid.add(change(RecapKind.WEEKLY, root -> map(root.get("period")).put("timezone", "UTC")));
			invalid.add(change(RecapKind.WEEKLY, root -> map(root.get("period")).put("start_date", "2020-01-01")));
			invalid.add(change(RecapKind.WEEKLY, root -> root.put("view", "not an object")));
			invalid.add(change(RecapKind.WEEKLY, root -> root.put("internal_metrics", List.of())));
			invalid.add(change(RecapKind.WEEKLY, root -> root.put("internal_metrics", null)));
			invalid.add(change(RecapKind.WEEKLY, root -> map(root.get("view")).remove("page1_last_week_performance")));
			invalid.add(change(RecapKind.WEEKLY, root -> path(root, "view", "period").put("week_end", "2026-08-31")));
			invalid.add(change(RecapKind.WEEKLY, root -> path(root, "view", "page1_last_week_performance", "achievement").put("save_count", -1)));
			invalid.add(change(RecapKind.WEEKLY, root -> path(root, "view", "page1_last_week_performance", "achievement").put("new_wish_count", 0.5)));
			invalid.add(change(RecapKind.WEEKLY, root -> path(root, "view", "page1_last_week_performance", "achievement").put("net_savings", 9007199254740992L)));
			invalid.add(change(RecapKind.WEEKLY, root -> path(root, "view", "page2_growth_report").put("message_visits", "")));
			invalid.add(change(RecapKind.WEEKLY, root -> path(root, "view", "page3_academy_success_stories").put("stories", List.of(Map.of("wish_id", UUID.randomUUID().toString(), "type_title", "x", "owner_student_id", "invented")))));
			for (String body : invalid) {
				response.set(body);
				Throwable error = catchThrowable(() -> client.generate(claim));
				assertThat(error).as(body).isInstanceOf(RecapTransportException.class);
				assertThat(((RecapTransportException) error).retryable()).isFalse();
			}
			response.set(weekly);
			assertThat(client.generate(claim).viewJson()).contains("save_count");
			assertThat(received.get()).isEqualTo(claim.requestJson()); assertThat(idempotency.get()).isEqualTo(claim.id().toString());
		} finally { server.stop(0); }
	}

	@Test void acceptsCurrentPythonWeeklyMonthlyNullableResultsAndOpenMetricsButRejectsInvalidMonthlyRelations() throws Exception {
		var response = new AtomicReference<String>();
		HttpServer server = server(response, new AtomicReference<>(), new AtomicReference<>());
		try {
			var client = client(server);
			for (var kind : RecapKind.values()) {
				response.set(source(kind)); assertThat(client.generate(claim(kind)).viewJson()).isNotBlank();
				response.set(change(kind, root -> root.put("internal_metrics", Map.of())));
				assertThat(client.generate(claim(kind)).internalMetricsJson()).isEqualTo("{}");
				response.set(change(kind, root -> root.put("internal_metrics", Map.of("future_metric", List.of(1, "ok")))));
				assertThat(client.generate(claim(kind)).internalMetricsJson()).contains("future_metric");
			}
			response.set(change(RecapKind.MONTHLY, root -> {
				path(root,"view","objective_performance").put("prev_rate_pct", -10);
				path(root,"view","objective_performance").put("curr_rate_pct", 150);
				path(root,"view","group_comparison").put("habit_percentile_status", "all_tied");
				path(root,"view","group_comparison").put("habit_percentile", null);
			}));
			assertThat(client.generate(claim(RecapKind.MONTHLY)).viewJson()).contains("150");
			List<Consumer<Map<String,Object>>> invalid = List.of(
				root -> path(root,"view","period").put("month", 7),
				root -> path(root,"view","type_section").put("type_title", "new algorithm"),
				root -> path(root,"view","pattern_analysis").put("top_week", 6),
				root -> path(root,"view","pace_prediction").put("required_daily_amount", -1),
				root -> path(root,"view","pace_prediction").put("expected_completion_date", "2026-02-30"),
				root -> path(root,"view","group_comparison").put("habit_percentile_status", "ok"),
				root -> path(root,"view","group_comparison").put("habit_percentile", 50));
			for (var mutation : invalid) {
				response.set(change(RecapKind.MONTHLY, mutation));
				assertThatThrownBy(() -> client.generate(claim(RecapKind.MONTHLY))).isInstanceOf(RecapTransportException.class);
			}
		} finally { server.stop(0); }
	}

	static RecapGenerationCoordinator.Claim claim(RecapKind kind) {
		String request = kind == RecapKind.WEEKLY ? RecapProtocolFixtures.WEEKLY_REQUEST : RecapProtocolFixtures.MONTHLY_REQUEST;
		var root = map(JSON.readValue(request, Object.class));
		return new RecapGenerationCoordinator.Claim(UUID.fromString((String)root.get("generation_id")), UUID.fromString((String)root.get("card_balance_account_id")),
				UUID.fromString((String)root.get("student_id")), UUID.fromString((String)root.get("academy_id")), kind, (String)root.get("input_digest"), request, 1);
	}
	private static String source(RecapKind kind) { return kind == RecapKind.WEEKLY ? RecapProtocolFixtures.WEEKLY_RESPONSE : RecapProtocolFixtures.MONTHLY_RESPONSE; }
	private static String change(RecapKind kind, Consumer<Map<String,Object>> mutation) {
		var root = map(JSON.readValue(source(kind), Object.class)); mutation.accept(root); return JSON.writeValueAsString(root);
	}
	private static Map<String,Object> path(Map<String,Object> map, String... names) { for (String name : names) map = map(map.get(name)); return map; }
	@SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
	private static RecapPythonClient client(HttpServer server) {
		return new RecapPythonClient(new RecapServiceSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/generate", "test-token"), JSON);
	}
	private static HttpServer server(AtomicReference<String> response, AtomicReference<String> request, AtomicReference<String> idempotency) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/generate", exchange -> {
			request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
			byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
			try (var body = exchange.getResponseBody()) { body.write(bytes); }
		});
		server.start(); return server;
	}
}
