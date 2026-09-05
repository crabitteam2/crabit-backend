package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JsonCanonicalizerTest {
	private final ObjectMapper json = new ObjectMapper();

	@Test void matchesTheRfc8785NumberAndPropertyOrderingVector() {
		var value = new LinkedHashMap<String, Object>();
		value.put("numbers", List.of(333333333.33333329, 1E30, 4.50, 2e-3, 1e-27));
		value.put("string", "€$\u000f\nA'B\"\\\"");
		value.put("literals", java.util.Arrays.asList(null, true, false));
		assertThat(new String(JsonCanonicalizer.canonicalize(json, value), StandardCharsets.UTF_8)).isEqualTo(
				"{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\"\"}");
	}

	@Test void ignoresObjectInsertionOrder() {
		assertThat(JsonCanonicalizer.canonicalize(json, new LinkedHashMap<>(java.util.Map.of("b", 1, "a", 2))))
				.isEqualTo("{\"a\":2,\"b\":1}".getBytes(StandardCharsets.UTF_8));
	}
}
