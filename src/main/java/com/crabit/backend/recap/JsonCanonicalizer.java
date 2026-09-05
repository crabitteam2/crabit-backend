package com.crabit.backend.recap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/** RFC 8785 JSON Canonicalization Scheme encoder. */
final class JsonCanonicalizer {
	private JsonCanonicalizer() {}

	static byte[] canonicalize(ObjectMapper json, Object value) {
		try {
			Object normalized = json.readValue(json.writeValueAsBytes(value), Object.class);
			StringBuilder out = new StringBuilder();
			append(normalized, out);
			return out.toString().getBytes(StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalArgumentException("Value cannot be canonicalized as RFC 8785 JSON", e);
		}
	}

	private static void append(Object value, StringBuilder out) {
		if (value == null) { out.append("null"); return; }
		if (value instanceof String string) { appendString(string, out); return; }
		if (value instanceof Boolean bool) { out.append(bool); return; }
		if (value instanceof Number number) { out.append(number(number)); return; }
		if (value instanceof List<?> list) {
			out.append('[');
			for (int index = 0; index < list.size(); index++) {
				if (index > 0) out.append(',');
				append(list.get(index), out);
			}
			out.append(']');
			return;
		}
		if (value instanceof Map<?, ?> map) {
			TreeMap<String, Object> sorted = new TreeMap<>();
			for (var entry : map.entrySet()) {
				if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON object key is not text");
				sorted.put(key, entry.getValue());
			}
			out.append('{'); boolean first = true;
			for (var entry : sorted.entrySet()) {
				if (!first) out.append(','); first = false;
				appendString(entry.getKey(), out); out.append(':'); append(entry.getValue(), out);
			}
			out.append('}');
			return;
		}
		throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
	}

	private static String number(Number number) {
		if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long)
			return number.toString();
		double value = number.doubleValue();
		if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite JSON number");
		if (value == 0) return "0";
		BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
		int exponent = decimal.precision() - decimal.scale() - 1;
		if (exponent >= 21 || exponent <= -7) {
			String digits = decimal.unscaledValue().abs().toString();
			StringBuilder result = new StringBuilder();
			if (decimal.signum() < 0) result.append('-');
			result.append(digits.charAt(0));
			if (digits.length() > 1) result.append('.').append(digits, 1, digits.length());
			return result.append('e').append(exponent >= 0 ? "+" : "").append(exponent).toString();
		}
		return decimal.toPlainString();
	}

	private static void appendString(String value, StringBuilder out) {
		out.append('"');
		for (int index = 0; index < value.length(); index++) {
			char ch = value.charAt(index);
			switch (ch) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\b' -> out.append("\\b");
				case '\t' -> out.append("\\t");
				case '\n' -> out.append("\\n");
				case '\f' -> out.append("\\f");
				case '\r' -> out.append("\\r");
				default -> {
					if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
					else if (Character.isHighSurrogate(ch)) {
						if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1)))
							throw new IllegalArgumentException("Unpaired Unicode surrogate");
						out.append(ch).append(value.charAt(++index));
					} else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("Unpaired Unicode surrogate");
					else out.append(ch);
				}
			}
		}
		out.append('"');
	}
}
