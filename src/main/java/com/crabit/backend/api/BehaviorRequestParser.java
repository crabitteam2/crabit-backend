package com.crabit.backend.api;

import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.behavior.BehaviorException;
import com.crabit.backend.behavior.BehaviorService;
import com.crabit.backend.wish.WishLifecycleException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

final class BehaviorRequestParser {
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    private BehaviorRequestParser() {}

    static UUID actor(HttpServletRequest request) {
        if (!(request.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE)
                instanceof CurrentPrincipal p))
            throw new WishLifecycleException(
                    WishLifecycleException.Code.AUTH_REQUIRED, "A known Bearer token is required.");
        if (p.role() != CurrentPrincipal.Role.STUDENT)
            throw new WishLifecycleException(
                    WishLifecycleException.Code.FORBIDDEN,
                    "The authenticated principal is not a student.");
        return p.subjectId();
    }

    static JsonNode body(byte[] bytes, HttpServletRequest request) {
        query(request, Set.of());
        try {
            var media = MediaType.parseMediaType(Objects.requireNonNull(request.getContentType()));
            if (!media.getType().equalsIgnoreCase("application")
                    || !media.getSubtype().equalsIgnoreCase("json"))
                throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new WishLifecycleException(
                    WishLifecycleException.Code.UNSUPPORTED_MEDIA_TYPE,
                    "Content-Type must be application/json.");
        }
        try {
            var n = bytes == null ? null : JSON.readTree(bytes);
            if (n == null || !n.isObject()) throw BehaviorException.malformed();
            return n;
        } catch (RuntimeException e) {
            throw BehaviorException.malformed();
        }
    }

    static void fields(JsonNode n, Set<String> required, Set<String> optional) {
        var names = n.propertyNames();
        if (!names.containsAll(required)
                || !names.stream().allMatch(k -> required.contains(k) || optional.contains(k))
                || names.stream().anyMatch(k -> n.get(k).isNull()))
            throw BehaviorException.malformed();
    }

    static String string(JsonNode n, String key) {
        var v = n.get(key);
        if (v == null || !v.isTextual()) throw BehaviorException.malformed();
        return v.stringValue();
    }

    static UUID uuid(String text) {
        if (text == null
                || !text.matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw BehaviorException.malformed();
        return UUID.fromString(text);
    }

    static UUID uuid(JsonNode n, String key) {
        return uuid(string(n, key));
    }

    static Instant time(JsonNode n) {
        String value = string(n, "occurredAt");
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"))
            throw BehaviorException.malformed();
        int fraction = value.indexOf('.');
        if (fraction >= 0 && value.length() - fraction - 2 > 6) {
            value = value.substring(0, fraction + 7) + "Z";
        }
        try {
            return BehaviorService.micros(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeException e) {
            throw BehaviorException.malformed();
        }
    }

    static int integer(JsonNode n, String key, int min, int max) {
        var v = n.get(key);
        if (v == null
                || !v.isIntegralNumber()
                || !v.canConvertToInt()
                || v.intValue() < min
                || v.intValue() > max) throw BehaviorException.malformed();
        return v.intValue();
    }

    static void query(HttpServletRequest request, Set<String> allowed) {
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1))
            throw BehaviorException.malformed();
    }

    static LocalDate date(HttpServletRequest request, String key) {
        String value = request.getParameter(key);
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw BehaviorException.malformed();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            throw BehaviorException.malformed();
        }
    }
}
