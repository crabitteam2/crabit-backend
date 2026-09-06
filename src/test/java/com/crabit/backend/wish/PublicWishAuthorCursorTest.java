package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PublicWishAuthorCursorTest {
    @Test void signedCursorBindsEveryContextAndRejectsLegacyAndUnsupportedPayloads() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT secret FROM relationship_cursor_key WHERE id = 1", String.class)).thenReturn("test-secret");
        var codec = new SharedCardCursor(jdbc);
        UUID viewer = UUID.randomUUID(), academy = UUID.randomUUID(), owner = UUID.randomUUID(), card = UUID.randomUUID();
        Instant time = Instant.parse("2026-09-02T00:00:00.123456Z");
        String payload = "1|listAcademySharedCards|" + viewer + "|" + academy + "|" + owner + "|" + time + "|" + card;
        String cursor = sign(payload);
        assertThat(codec.decode(cursor, viewer, academy, owner)).isEqualTo(new SharedCardQueryRepository.CursorBoundary(time, card));
        assertThatThrownBy(() -> codec.decode(cursor, viewer, UUID.randomUUID(), owner)).isInstanceOf(WishLifecycleException.class);
        assertThatThrownBy(() -> codec.decode(cursor, UUID.randomUUID(), academy, owner)).isInstanceOf(WishLifecycleException.class);
        assertThatThrownBy(() -> codec.decode(cursor, viewer, academy, null)).isInstanceOf(WishLifecycleException.class);
        for (String bad : List.of(payload.replace("1|list", "2|list"), payload.replace("listAcademySharedCards", "students"),
                payload.replace(time.toString(), "not-a-time"))) {
            assertThatThrownBy(() -> codec.decode(sign(bad), viewer, academy, owner)).isInstanceOf(WishLifecycleException.class);
        }
        assertThatThrownBy(() -> codec.decode(cursor + "x", viewer, academy, owner)).isInstanceOf(WishLifecycleException.class);
    }
    private String sign(String payload) throws Exception {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
    }
}
