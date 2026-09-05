package com.crabit.backend.history;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Canonical financial input: sorted object keys, preserved arrays and explicit nulls. */
final class HistoricalCanonicalJson {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private HistoricalCanonicalJson() {}
    static Object parse(String json) { return JSON.readValue(json, Object.class); }
    static String encode(Object value) { return JSON.writeValueAsString(sorted(value)); }
    static String digest(Object value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encode(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, child) -> result.put((String) key, sorted(child)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(HistoricalCanonicalJson::sorted).toList();
        return value;
    }
}
