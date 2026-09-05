package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties={"spring.main.banner-mode=off","logging.level.root=warn",
        "crabit.recommendation.handoff.enabled=true","crabit.recommendation.handoff.trigger-credential=history-trigger",
        "crabit.recommendation.handoff.receiver-credential=history-receiver",
        "crabit.recommendation.handoff.receiver-url=http://127.0.0.1:1/unused",
        "crabit.wish-photo.cleanup-delay-ms=3600000"})
class HistoricalBalanceApiIT extends WishApiIntegrationSupport {
    @org.springframework.beans.factory.annotation.Autowired
    private com.crabit.backend.history.HistoricalBalanceQueryService history;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    private static final String BASE="/internal/v1/academies/"+PRIMARY_ACADEMY_ID+"/students/"+OWNER_ID+"/card-balance-accounts/"+OWNER_ACCOUNT_ID+"/historical-balances";
    private LocalDate today(){return jdbc.queryForObject("select clock_timestamp()",Timestamp.class).toInstant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();}
    private MockHttpServletRequestBuilder request(){var today=today();return get(BASE).header("Authorization","Bearer history-trigger").param("fromDate",today.toString()).param("toDateExclusive",today.plusDays(1).toString()).param("granularity","DAY");}
    @SuppressWarnings("unchecked") private Map<String,Object> body(String json){return JsonPath.read(json,"$");}
    private Map<String,Object> success(MockHttpServletRequestBuilder request) throws Exception {
        String json=mockMvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        Map<String,Object> value=body(json);assertThat(OpenApiExamplesTest.validateWireResponse("HistoricalBalancesResponse",value)).isEmpty();return value;
    }
    @Test void exposesTheEnabledExactRouteAndTruthfulUnknownWithoutWritingOnRead() throws Exception {
        String docs=mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String,Object> paths=JsonPath.read(docs,"$.paths");
        assertThat(paths).containsKey("/internal/v1/academies/{academyId}/students/{studentId}/card-balance-accounts/{accountId}/historical-balances");
        jdbc.update("delete from representative_wish_selection where account_id=?",OWNER_ACCOUNT_ID);
        long before=jdbc.queryForObject("select count(*) from historical_balance_checkpoint",Long.class);
        var value=success(request());
        assertThat(JsonPath.<String>read(value,"$.items[0].balance.knowledge")).isEqualTo("UNKNOWN");
        assertThat(JsonPath.<Object>read(value,"$.items[0].balance.lastSuccessfulObservedCardBalance")).isNull();
        assertThat(JsonPath.<Number>read(value,"$.items[0].allocation.activeWishAllocation").longValue()).isEqualTo(750000);
        assertThat(JsonPath.<String>read(value,"$.items[0].representative.status")).isEqualTo("KNOWN_NONE");
        assertThat(jdbc.queryForObject("select count(*) from historical_balance_checkpoint",Long.class)).isEqualTo(before);
    }
    @Test void replayFreezesValuesAndDigestAfterTargetAndRepresentativeMutation() throws Exception {
        var original=success(request());
        asOwner(put("/v1/card-balance-accounts/"+OWNER_ACCOUNT_ID+"/representative-wish")
                .contentType(MediaType.APPLICATION_JSON).content("{\"wishId\":\""+LAPTOP_WISH_ID+"\"}")).andExpect(status().isOk());
        asOwner(patch(WISHES_PATH+"/"+LAPTOP_WISH_ID).contentType("application/merge-patch+json")
                .content("{\"expectedVersion\":0,\"targetAmount\":2000000}")).andExpect(status().isOk());
        var fresh=success(request());
        assertThat(fresh.get("dataRevision")).isNotEqualTo(original.get("dataRevision"));
        assertThat(JsonPath.<String>read(fresh,"$.items[0].representative.status")).isEqualTo("KNOWN_SELECTED");
        var replay=success(request().param("asOfRevision",(String)original.get("dataRevision")));
        var frozen=new LinkedHashMap<>(original);var replayed=new LinkedHashMap<>(replay);
        frozen.remove("readSnapshotAt");replayed.remove("readSnapshotAt");assertThat(replayed).isEqualTo(frozen);
    }
    @Test void requiresExactlyOneMachineCredentialAndRejectsMalformedRequestsWithNoStore() throws Exception {
        for(String credential:new String[]{"Bearer ","Bearer history-receiver","Bearer "+OWNER_TOKEN,"Basic history-trigger","Bearer history-trigger "}){
            mockMvc.perform(get(BASE).header("Authorization",credential)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("WWW-Authenticate","Bearer"));
        }
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
        mockMvc.perform(request().header("Authorization","Bearer history-trigger")).andExpect(status().isUnauthorized());
        for(var malformed:new MockHttpServletRequestBuilder[]{
                request().param("fromDate",today().toString()),request().param("unknown","x"),request().content("{}"),
                get(BASE).header("Authorization","Bearer history-trigger"),request().param("asOfRevision","null"),
                request().param("asOfRevision","h1.e30")}) {
            String json=mockMvc.perform(malformed).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST")).andReturn().getResponse().getContentAsString();
            assertThat(OpenApiExamplesTest.validateWireResponse("ErrorEnvelope",body(json))).isEmpty();
        }
    }
    @Test void hidesWrongOwnerAcademyMissingClosedAndMembershipIneligibleAccountsIncludingReplay() throws Exception {
        String token=(String)success(request()).get("dataRevision");
        for(String route:new String[]{BASE.replace(OWNER_ID.toString(),FRIEND_ID.toString()),BASE.replace(PRIMARY_ACADEMY_ID.toString(),OTHER_ACADEMY_ID.toString()),BASE.replace(OWNER_ACCOUNT_ID.toString(),UUID.randomUUID().toString())}){
            mockMvc.perform(get(route).header("Authorization","Bearer history-trigger").param("fromDate",today().toString()).param("toDateExclusive",today().plusDays(1).toString()).param("granularity","DAY"))
                    .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.error.code").value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));
        }
        jdbc.update("update academy_membership set left_at=clock_timestamp() where student_id=? and academy_id=?",OWNER_ID,PRIMARY_ACADEMY_ID);
        mockMvc.perform(request().param("asOfRevision",token)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("CARD_BALANCE_ACCOUNT_NOT_FOUND"));
        jdbc.update("update academy_membership set left_at=null where student_id=? and academy_id=?",OWNER_ID,PRIMARY_ACADEMY_ID);
        jdbc.update("update card_balance_account set closed_at=clock_timestamp() where id=?",OWNER_ACCOUNT_ID);
        mockMvc.perform(request()).andExpect(status().isNotFound());
    }
    @Test void successfulObservationAndMoneyTransferAreVisibleOnlyAsCoherentFinalFacts() throws Exception {
        setBalanceScenario("[{\"type\":\"SUCCESS\",\"balance\":1000000}]");
        asOwner(post("/v1/card-balance-accounts/"+OWNER_ACCOUNT_ID+"/balance-refreshes")).andExpect(status().isOk());
        var known=success(request());
        assertThat(JsonPath.<Number>read(known,"$.items[0].balance.ledgerAvailableBalance").longValue()).isEqualTo(250000);
        String destination=createWish("historical-destination","계약 검증",1000000);
        asOwner(post("/v1/card-balance-accounts/"+OWNER_ACCOUNT_ID+"/transfers")
                .header("Idempotency-Key","historical-transfer").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceWishId\":\""+LAPTOP_WISH_ID+"\",\"destinationWishId\":\""+destination+"\",\"amount\":100000,\"sourceExpectedVersion\":0,\"destinationExpectedVersion\":0}"))
                .andExpect(status().isOk());
        var after=success(request());
        assertThat(JsonPath.<Number>read(after,"$.items[0].allocation.activeWishAllocation").longValue()).isEqualTo(750000);
        assertThat(JsonPath.<Number>read(after,"$.items[0].balance.ledgerAvailableBalance").longValue()).isEqualTo(250000);
        success(request().param("asOfRevision",(String)known.get("dataRevision")));
    }
    @Test void rejectsFutureCrossAccountAndNonexistentRevisionDescriptors() throws Exception {
        var original=success(request());String token=(String)original.get("dataRevision");
        String decoded=new String(java.util.Base64.getUrlDecoder().decode(token.substring(3)),java.nio.charset.StandardCharsets.UTF_8);
        String checkpoint=JsonPath.read(original,"$.revisionBounds.checkpointId");
        for(String invalid:new String[]{decoded.replace(OWNER_ACCOUNT_ID.toString(),UUID.randomUUID().toString()),
                decoded.replace((String)original.get("evaluationHorizon"),"2099-01-01T00:00:00Z"),decoded.replace(checkpoint,UUID.randomUUID().toString())}) {
            String altered="h1."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            mockMvc.perform(request().param("asOfRevision",altered)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
        }
    }
    @Test void replayKeepsProvisionalHorizonAfterCalendarRolloverAndNewTransactions() throws Exception {
        LocalDate yesterday=today().minusDays(1);
        java.time.Instant started=yesterday.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        jdbc.execute("alter table historical_balance_checkpoint disable trigger immutable_historical_checkpoint");
        try { jdbc.update("update historical_balance_checkpoint set applied_at=? where account_id=?",Timestamp.from(started),OWNER_ACCOUNT_ID); }
        finally { jdbc.execute("alter table historical_balance_checkpoint enable trigger immutable_historical_checkpoint"); }
        var current=success(request());
        String token=(String)current.get("dataRevision");
        String priorHorizon=started.plusSeconds(86340).toString();
        String descriptor=new String(java.util.Base64.getUrlDecoder().decode(token.substring(3)),java.nio.charset.StandardCharsets.UTF_8)
                .replace((String)current.get("evaluationHorizon"),priorHorizon);
        String frozen="h1."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var oldRequest=get(BASE).header("Authorization","Bearer history-trigger").param("fromDate",yesterday.toString()).param("toDateExclusive",yesterday.plusDays(1).toString()).param("granularity","DAY").param("asOfRevision",frozen);
        var original=success(oldRequest);
        assertThat(JsonPath.<String>read(original,"$.items[0].periodStatus")).isEqualTo("PROVISIONAL");
        assertThat(original.get("evaluationHorizon")).isEqualTo(priorHorizon);
        createWish("rollover-new-wish","다음날 위시",100000);
        var replay=success(get(BASE).header("Authorization","Bearer history-trigger").param("fromDate",yesterday.toString()).param("toDateExclusive",yesterday.plusDays(1).toString()).param("granularity","DAY").param("asOfRevision",frozen));
        assertThat(replay.get("inputDigest")).isEqualTo(original.get("inputDigest"));
        assertThat(replay.get("items")).isEqualTo(original.get("items"));
        assertThat(replay.get("dataRevision")).isEqualTo(frozen);
    }
    @Test void repeatableReadKeepsCheckpointAndFactsCoherentAcrossAConcurrentCommit() throws Exception {
        var before=success(request());LocalDate from=today();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
        Map<String,Object> during=tx.execute(status->{
            jdbc.queryForObject("select count(*) from historical_balance_checkpoint where account_id=?",Long.class,OWNER_ACCOUNT_ID);
            java.util.concurrent.CompletableFuture.runAsync(()->jdbc.update("update wish set target_amount=2000000 where id=?",LAPTOP_WISH_ID)).orTimeout(10,java.util.concurrent.TimeUnit.SECONDS).join();
            return history.query(PRIMARY_ACADEMY_ID,OWNER_ID,OWNER_ACCOUNT_ID,from,from.plusDays(1),com.crabit.backend.history.HistoricalPeriods.Granularity.DAY,null);
        });
        assertThat(JsonPath.<String>read(during,"$.revisionBounds.checkpointId")).isEqualTo(JsonPath.<String>read(before,"$.revisionBounds.checkpointId"));
        var after=success(request());
        assertThat(JsonPath.<String>read(after,"$.revisionBounds.checkpointId")).isNotEqualTo(JsonPath.<String>read(during,"$.revisionBounds.checkpointId"));
        var replay=success(request().param("asOfRevision",(String)during.get("dataRevision")));
        assertThat(replay.get("inputDigest")).isEqualTo(during.get("inputDigest"));
        assertThat(replay.get("items")).isEqualTo(body(new tools.jackson.databind.json.JsonMapper().writeValueAsString(during)).get("items"));
    }
    @Test void collectedCheckpointContradictionsReturnSanitizedIntegrityFailure() throws Exception {
        jdbc.execute("alter table historical_balance_checkpoint disable trigger immutable_historical_checkpoint");
        try { jdbc.update("update historical_balance_checkpoint set active_wish_allocation=0 where account_id=?",OWNER_ACCOUNT_ID); }
        finally { jdbc.execute("alter table historical_balance_checkpoint enable trigger immutable_historical_checkpoint"); }
        String json=mockMvc.perform(request()).andExpect(status().isInternalServerError()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.error.code").value("HISTORICAL_BALANCE_INTEGRITY_ERROR")).andExpect(jsonPath("$.error.retryable").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(OpenApiExamplesTest.validateWireResponse("ErrorEnvelope",body(json))).isEmpty();
        assertThat(JsonPath.<Map<String,Object>>read(json,"$.error.details")).isEmpty();
    }
}
