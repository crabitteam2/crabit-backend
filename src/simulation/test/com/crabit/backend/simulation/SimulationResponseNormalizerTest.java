package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SimulationResponseNormalizerTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @Test void typedIdentityChangesDoNotRewriteFreeTextOrLoseNullZeroOrderOrTime() {
        UUID wish=UUID.randomUUID(),account=UUID.randomUUID(),event=UUID.randomUUID();
        var n=new SimulationResponseNormalizer(Map.of("WISH:goal",wish,"ACCOUNT:wallet",account,"LEDGER_ROOT:deposit",event));
        var raw=JSON.createObjectNode();var body=raw.putObject("wish");
        body.put("id",wish.toString());body.put("cardBalanceAccountId",account.toString());body.put("purpose",wish.toString());
        body.put("amount",0);body.putNull("closedAt");body.put("updatedAt","2026-06-01T01:02:03.123456Z");
        raw.put("eventId",event.toString());raw.putArray("ordered").add(3).add(1).add(2);
        byte[] bytes=JSON.writeValueAsBytes(raw),original=bytes.clone();
        var result=n.normalize("DEPOSIT",bytes,null);
        assertThat(result.get("wish").get("id").asString()).isEqualTo("WISH:goal");
        assertThat(result.get("wish").get("purpose").asString()).isEqualTo(wish.toString());
        assertThat(result.get("wish").get("amount").intValue()).isZero();
        assertThat(result.get("wish").get("closedAt").isNull()).isTrue();
        assertThat(result.get("wish").get("updatedAt")).isEqualTo(body.get("updatedAt"));
        assertThat(result.get("ordered")).isEqualTo(raw.get("ordered"));assertThat(bytes).isEqualTo(original);
    }
    @Test void unknownTypedIdsFailInsteadOfHashingRandomRuntimeValues() {
        var n=new SimulationResponseNormalizer(Map.of());
        assertThatThrownBy(()->n.normalize("JOIN",JSON.writeValueAsBytes(Map.of("studentId",UUID.randomUUID())),null))
            .hasMessageContaining("NORMALIZATION_IDENTITY_UNKNOWN:STUDENT");
    }
    @Test void refusesUnverifiedCursorsAndUnknownPythonResponseIdentity() {
        var n=new SimulationResponseNormalizer(Map.of());
        assertThatThrownBy(()->n.normalize("FEED_QUERY",JSON.writeValueAsBytes(Map.of("nextCursor","opaque")),null))
            .hasMessage("NORMALIZATION_CURSOR_UNVERIFIED");
        assertThatThrownBy(()->n.normalize("FEED_QUERY",JSON.writeValueAsBytes(Map.of("recommendationResultId","request")),null))
            .hasMessageContaining("NORMALIZATION_IDENTITY_UNKNOWN:FEED_RECOMMENDATION");
    }
    @Test void rejectsNonBijectiveSameKindAndKeepsDifferentKindsDistinct() {
        UUID id=UUID.randomUUID();
        assertThatThrownBy(()->new SimulationResponseNormalizer(Map.of("WISH:a",id,"WISH:b",id)))
            .hasMessage("NORMALIZATION_IDENTITY_NOT_BIJECTIVE");
        var n=new SimulationResponseNormalizer(Map.of("STUDENT:person",id,"ACCOUNT:wallet",id));
        var result=n.normalize("JOIN",JSON.writeValueAsBytes(Map.of("studentId",id,"accountId",id)),null);
        assertThat(result.get("studentId").asString()).isEqualTo("STUDENT:person");
        assertThat(result.get("accountId").asString()).isEqualTo("ACCOUNT:wallet");
    }
}
