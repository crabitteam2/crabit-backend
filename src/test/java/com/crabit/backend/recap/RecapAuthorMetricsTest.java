package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RecapAuthorMetricsTest {
	@Test void matchesEveryOriginalPythonClassificationOracleIncludingSignedAndNullableMetrics() throws Exception {
		var oracle = new ObjectMapper().readTree(getClass().getResourceAsStream("/recap/author-metrics-oracle.json"));
		for (var example : oracle.get("cases")) {
			var tx = new ArrayList<RecapSnapshotService.EffectiveTransaction>();
			for (String field : List.of("deposits_day_amount", "withdrawals_day_amount"))
				for (var pair : example.get(field)) tx.add(new RecapSnapshotService.EffectiveTransaction(UUID.randomUUID(), UUID.randomUUID(),
						LocalDate.of(2026, 8, pair.get(0).asInt()).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),
						pair.get(1).asLong(), field.startsWith("deposits") ? "DEPOSIT" : "WITHDRAWAL"));
			var actual = RecapSnapshotService.authorMetrics(tx, LocalDate.of(2026,8,1),
					example.get("deleted_abandoned_wishes").asLong(), example.get("outgoing_visits").asLong());
			assertThat(actual.get("metrics_version")).isEqualTo("core-metrics-v1");
			for (var entry : actual.entrySet()) {
				if (entry.getKey().equals("metrics_version")) continue;
				var expected = example.get("metrics").get(entry.getKey().equals("deposit_count") ? "save_count" : entry.getKey());
				if (expected.isNull()) assertThat(entry.getValue()).as(example.get("case").asText()).isNull();
				else assertThat(((Number)entry.getValue()).doubleValue()).as(example.get("case").asText()+"/"+entry.getKey())
						.isCloseTo(expected.asDouble(), org.assertj.core.data.Offset.offset(1e-12));
			}
		}
	}
}
