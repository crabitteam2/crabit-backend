package com.crabit.backend.demo;

import static com.crabit.backend.e2e.SeedFixtureCatalog.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.crabit.backend.CrabitBackendApplication;
import com.crabit.backend.e2e.PostgresTestDatabase;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

class PublicWishAuthorDemoHttpIT {
    private int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String ownerToken = UUID.randomUUID().toString();
    private final String viewerToken = UUID.randomUUID().toString();

    @Test void realDemoHttpProjectsAllNullableDatePairsAndPreservesCompletionTiming() throws Exception {
        PostgresTestDatabase.fixtures().resetAndInitialize();
        try (var context = new SpringApplicationBuilder(CrabitBackendApplication.class).profiles("demo").run(
                "--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=warn",
                "--spring.datasource.url=" + PostgresTestDatabase.URL,
                "--spring.datasource.username=test", "--spring.datasource.password=test",
                "--crabit.demo.token.owner=" + ownerToken, "--crabit.demo.token.friend=" + viewerToken,
                "--crabit.demo.token.nonfriend=" + UUID.randomUUID(), "--crabit.demo.token.blocked=" + UUID.randomUUID(),
                "--crabit.demo.token.other-academy=" + UUID.randomUUID(), "--crabit.demo.token.staff=" + UUID.randomUUID(),
                "--crabit.demo.balance-provider.url=https://console.example.test/api/provider/balance-lookups",
                "--crabit.demo.balance-provider.token=" + UUID.randomUUID())) {
            port = ((WebServerApplicationContext) context).getWebServer().getPort();
            for (String[] dates : new String[][] {{null,null},{"2025-12-31",null},{null,"2026-01-01"},
                    {"2025-12-31","2026-01-01"},{"2025-01-01","2025-01-01"}}) {
                PostgresTestDatabase.fixtures().resetAndInitialize();
                String wishPath = "/v1/card-balance-accounts/" + OWNER_ACCOUNT_ID + "/wishes/" + CAMP_WISH_ID;
                // Explicitly set both, then clear/update through the real merge-patch handler.
                request("PATCH", wishPath, ownerToken, "{\"expectedVersion\":0,\"startDate\":\"2025-01-01\",\"targetDate\":\"2026-01-01\"}", 200);
                request("PATCH", wishPath, ownerToken, "{\"expectedVersion\":1,\"startDate\":" + value(dates[0]) + ",\"targetDate\":" + value(dates[1]) + "}", 200);
                verifyCard(dates, "PROGRESS");
                request("POST", wishPath + "/completion", ownerToken, "{\"expectedVersion\":2}", 200);
                verifyCard(dates, "COMPLETION");
            }
            String student = request("GET", "/v1/academies/" + PRIMARY_ACADEMY_ID + "/students/" + OWNER_ID, viewerToken, null, 200);
            assertThat((String) JsonPath.read(student, "$.studentId")).isEqualTo(OWNER_ID.toString());
        }
    }

    private void verifyCard(String[] dates, String kind) throws Exception {
        var jdbc = PostgresTestDatabase.JDBC;
        UUID card = jdbc.queryForObject("SELECT id FROM shared_card WHERE wish_id = ?", UUID.class, CAMP_WISH_ID);
        String result = request("GET", "/v1/academies/" + PRIMARY_ACADEMY_ID + "/shared-cards/" + card, viewerToken, null, 200);
        Map<String,Object> body = JsonPath.read(result, "$");
        assertThat(body).containsEntry("ownerId", OWNER_ID.toString()).containsEntry("kind", kind)
                .containsEntry("startDate", dates[0]).containsEntry("targetDate", dates[1]);
        Map<String,Object> row = jdbc.queryForMap("SELECT start_date, target_date, created_at, completed_at FROM wish WHERE id = ?", CAMP_WISH_ID);
        for (int i = 0; i < 2; i++) {
            Object stored = row.get(i == 0 ? "start_date" : "target_date");
            assertThat(stored == null ? null : stored.toString()).isEqualTo(dates[i]);
        }
        if (kind.equals("COMPLETION")) {
            Instant created = ((java.sql.Timestamp) row.get("created_at")).toInstant();
            Instant completed = ((java.sql.Timestamp) row.get("completed_at")).toInstant();
            assertThat(Instant.parse((String) body.get("createdAt"))).isEqualTo(created);
            assertThat(Instant.parse((String) body.get("completedAt"))).isEqualTo(completed);
            assertThat(((Number) body.get("actualDurationSeconds")).longValue()).isEqualTo(Math.max(0, Duration.between(created,completed).getSeconds()));
        }
        String page = request("GET", "/v1/academies/" + PRIMARY_ACADEMY_ID + "/shared-cards?ownerId=" + OWNER_ID, viewerToken, null, 200);
        List<Map<String,Object>> items = JsonPath.read(page, "$.items");
        assertThat(items).anySatisfy(item -> assertThat(item).containsEntry("sharedCardId", card.toString())
                .containsEntry("startDate", dates[0]).containsEntry("targetDate", dates[1]));
    }

    private String request(String method, String path, String token, String body, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", method.equals("PATCH") ? "application/merge-patch+json" : "application/json");
        if (method.equals("POST")) request.header("Idempotency-Key", UUID.randomUUID().toString());
        var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(expected);
        return response.body();
    }
    private String value(String value) { return value == null ? "null" : "\"" + value + "\""; }
}
