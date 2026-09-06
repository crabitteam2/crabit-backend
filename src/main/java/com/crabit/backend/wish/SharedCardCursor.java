package com.crabit.backend.wish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Opaque, authenticated continuation bound to the requesting principal and feed. */
@Component
public class SharedCardCursor {
    private final JdbcTemplate jdbc;

    public SharedCardCursor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String encode(SharedCardQueryRepository.Row row, UUID viewer, UUID academy, UUID owner) {
        String payload = "1|listAcademySharedCards|" + viewer + "|" + academy + "|"
                + (owner == null ? "*" : owner) + "|" + row.contentUpdatedAt() + "|" + row.sharedCardId();
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    public SharedCardQueryRepository.CursorBoundary decode(String cursor, UUID viewer, UUID academy, UUID owner) {
        if (cursor == null) return null;
        try {
            String[] signed = cursor.split("\\.", -1);
            if (signed.length != 2 || !MessageDigest.isEqual(sign(signed[0]).getBytes(StandardCharsets.UTF_8),
                    signed[1].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
            String[] p = new String(Base64.getUrlDecoder().decode(signed[0]), StandardCharsets.UTF_8).split("\\|", -1);
            if (p.length != 7 || !p[0].equals("1") || !p[1].equals("listAcademySharedCards")
                    || !p[2].equals(viewer.toString()) || !p[3].equals(academy.toString())
                    || !p[4].equals(owner == null ? "*" : owner.toString())) throw new IllegalArgumentException();
            return new SharedCardQueryRepository.CursorBoundary(Instant.parse(p[5]), UUID.fromString(p[6]));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException exception) {
            throw new WishLifecycleException(WishLifecycleException.Code.MALFORMED_REQUEST,
                    "cursor is malformed or bound to another request.", "cursor");
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jdbc.queryForObject(
                    "SELECT secret FROM relationship_cursor_key WHERE id = 1", String.class)
                    .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
