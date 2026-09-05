package com.crabit.backend.history;

import static com.crabit.backend.history.HistoricalBalanceQueryService.*;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoricalBalanceProjectionTest {
    private static final Instant OPEN=Instant.parse("2026-08-30T15:00:00Z"), COLLECT=Instant.parse("2026-08-31T15:00:00Z");
    private static final UUID A=UUID.randomUUID(),B=UUID.randomUUID(),SUCCESS=UUID.randomUUID(),FAILURE=UUID.randomUUID();
    private static HistoricalPeriods.Period day(String date){return HistoricalPeriods.buckets(LocalDate.parse(date),LocalDate.parse(date).plusDays(1),HistoricalPeriods.Granularity.DAY,Instant.parse("2026-09-10T00:00:00Z")).getFirst();}
    private static Checkpoint cp(long allocation,UUID rep,String state,Long target,Long amount,UUID latest,UUID success){return new Checkpoint(UUID.randomUUID(),1,COLLECT,true,0,latest,success,latest==null?null:1L,allocation,rep,state,target,amount,List.of());}
    private static Observation success(long amount){return new Observation(SUCCESS,"SUCCEEDED","USER_REQUESTED",COLLECT,amount,null,1L);}
    @SuppressWarnings("unchecked") private static Map<String,Object> part(Map<String,Object> item,String key){return (Map<String,Object>)item.get(key);}
    @Test void usesTheSelectedHistoricalTargetAndRepresentativeForEveryBucket() {
        var observation=Map.of(SUCCESS,success(1000));
        var cps=List.of(cp(700,A,"IN_PROGRESS",1000L,300L,SUCCESS,SUCCESS),cp(700,B,"IN_PROGRESS",800L,400L,SUCCESS,SUCCESS),cp(700,B,"IN_PROGRESS",1000L,400L,SUCCESS,SUCCESS));
        var percentages=cps.stream().map(c->part(bucket(day("2026-09-01"),OPEN,COLLECT,COLLECT,c,observation),"representative").get("progressPercent")).toList();
        assertThat(percentages).containsExactly(30L,50L,40L);
        assertThat(part(bucket(day("2026-09-01"),OPEN,COLLECT,COLLECT,cps.getLast(),observation),"balance")).containsEntry("ledgerAvailableBalance",300L);
    }
    @Test void keepsPreOpeningAndPreCollectionUnknownSeparateFromRecordedRepresentativeAbsence() {
        Instant opened=Instant.parse("2026-09-01T15:00:00Z"),collected=Instant.parse("2026-09-03T03:00:00Z");
        var before=bucket(day("2026-09-01"),opened,collected,null,null,Map.of());
        assertThat(part(before,"representative")).containsEntry("status","ACCOUNT_NOT_OPEN").containsEntry("progressPercent",null);
        var gap=bucket(day("2026-09-02"),opened,collected,null,null,Map.of());
        assertThat(part(gap,"balance")).containsEntry("unknownReason","PRE_COLLECTION_UNKNOWN");
        var known=bucket(day("2026-09-03"),opened,collected,null,cp(700,null,null,null,null,null,null),Map.of());
        assertThat(part(known,"coverage")).containsEntry("status","PARTIAL");
        assertThat(part(known,"representative")).containsEntry("status","KNOWN_NONE").containsEntry("progressPercent",null);
        assertThat(part(known,"allocation")).containsEntry("activeWishAllocation",700L);
        assertThatThrownBy(()->bucket(day("2026-09-04"),opened,collected,null,null,Map.of())).isInstanceOf(HistoricalBalanceException.class);
    }
    @Test void distinguishesNoSuccessZeroSuccessAndFailedLookupWithRealShortage() {
        var unknown=bucket(day("2026-09-01"),OPEN,COLLECT,null,cp(0,null,null,null,null,null,null),Map.of());
        assertThat(part(unknown,"balance")).containsEntry("knowledge","UNKNOWN").containsEntry("lastSuccessfulObservedCardBalance",null).containsEntry("unknownReason","NO_SUCCESSFUL_OBSERVATION");
        assertThat(part(unknown,"allocation")).containsEntry("knowledge","KNOWN").containsEntry("activeWishAllocation",0L);
        assertThat(part(unknown,"latestLookup")).containsEntry("status","NOT_LOOKED_UP");
        var zero=bucket(day("2026-09-01"),OPEN,COLLECT,COLLECT,cp(0,null,null,null,null,SUCCESS,SUCCESS),Map.of(SUCCESS,success(0)));
        assertThat(part(zero,"balance")).containsEntry("knowledge","KNOWN").containsEntry("ledgerAvailableBalance",0L).containsEntry("lastSuccessfulObservationId",SUCCESS.toString());
        var failed=new Observation(FAILURE,"FAILED","AUTO_DAILY",COLLECT.plusSeconds(60),null,"PROVIDER_UNAVAILABLE",2L);
        var shortage=bucket(day("2026-09-01"),OPEN,COLLECT,COLLECT,cp(1200,null,null,null,null,FAILURE,SUCCESS),Map.of(SUCCESS,success(1000),FAILURE,failed));
        assertThat(part(shortage,"balance")).containsEntry("ledgerAvailableBalance",-200L).containsEntry("displayAvailableBalance",0L).containsEntry("unresolvedShortage",200L);
        assertThat(part(shortage,"provenance")).containsEntry("latestObservationId",FAILURE.toString()).containsEntry("lastSuccessfulObservationId",SUCCESS.toString());
    }
    @Test void calculatesLargeMoneyWithoutOverflowAndRemovesTerminalRepresentatives() {
        assertThat(cp(MAX_MONEY,A,"IN_PROGRESS",MAX_MONEY,MAX_MONEY-1,null,null).representative()).containsEntry("progressPercent",99L);
        assertThat(cp(1000,A,"AMOUNT_REACHED",1000L,1000L,null,null).representative()).containsEntry("progressPercent",100L);
        assertThat(cp(0,null,null,null,null,null,null).representative()).containsEntry("status","KNOWN_NONE").containsEntry("progressPercent",null);
        assertThatThrownBy(()->cp(1001,A,"IN_PROGRESS",1000L,1001L,null,null).representative()).isInstanceOf(HistoricalBalanceException.class);
        assertThatThrownBy(()->cp(999,A,"AMOUNT_REACHED",1000L,999L,null,null).representative()).isInstanceOf(HistoricalBalanceException.class);
    }
    @Test void reconstructsEachCheckpointAndRejectsMissingEffectsOrCorruptActiveWishFacts() {
        UUID left=UUID.fromString("00000000-0000-4000-8000-000000000001"),right=UUID.fromString("00000000-0000-4000-8000-000000000002");
        var oldWishes=List.of(fields("wishId",left.toString(),"state","IN_PROGRESS","targetAmount",1000L,"amount",300L),fields("wishId",right.toString(),"state","IN_PROGRESS","targetAmount",1000L,"amount",400L));
        var newWishes=List.of(fields("wishId",left.toString(),"state","IN_PROGRESS","targetAmount",1000L,"amount",200L),fields("wishId",right.toString(),"state","IN_PROGRESS","targetAmount",1000L,"amount",500L));
        var base=new Checkpoint(UUID.randomUUID(),1,COLLECT,true,0,null,null,null,700,right,"IN_PROGRESS",1000L,400L,oldWishes);
        var next=new Checkpoint(UUID.randomUUID(),2,COLLECT.plusSeconds(2),false,5,null,null,null,700,right,"IN_PROGRESS",1000L,500L,newWishes);
        var event=fields("ledgerEventId",UUID.randomUUID().toString(),"applicationOrder","5","appliedAt",COLLECT.plusSeconds(1).toString(),"occurredAt",COLLECT.toString(),"type","WISH_TRANSFER","wishEffects",List.of(fields("wishId",left.toString(),"deltaAmount",-100L),fields("wishId",right.toString(),"deltaAmount",100L)));
        assertThatCode(()->validateReplay(List.of(base,next),List.of(event))).doesNotThrowAnyException();
        var missing=new java.util.LinkedHashMap<>(event);missing.put("wishEffects",List.of(fields("wishId",left.toString(),"deltaAmount",-100L)));
        assertThatThrownBy(()->validateReplay(List.of(base,next),List.of(missing))).isInstanceOf(HistoricalBalanceException.class);
        assertThatThrownBy(()->validateReplay(List.of(base,next),List.of())).isInstanceOf(HistoricalBalanceException.class);
        var malformed=new Checkpoint(UUID.randomUUID(),1,COLLECT,true,0,null,null,null,0,null,null,null,null,List.of(fields("wishId","broken","state","IN_PROGRESS","targetAmount",1000L,"amount",0L)));
        assertThatThrownBy(()->validateReplay(List.of(malformed),List.of())).isInstanceOf(HistoricalBalanceException.class).extracting("code").isEqualTo(HistoricalBalanceException.Code.HISTORICAL_BALANCE_INTEGRITY_ERROR);
        var contradiction=new Checkpoint(UUID.randomUUID(),1,COLLECT,true,0,null,null,null,701,right,"IN_PROGRESS",1000L,400L,oldWishes);
        assertThatThrownBy(()->validateReplay(List.of(contradiction),List.of())).isInstanceOf(HistoricalBalanceException.class);
    }
    @Test void canonicalDigestPreservesNullAndArrayOrderAndTokenRejectsNoncanonicalAndOverflow() {
        var a=fields("z",null,"a",List.of(1,2)); var b=fields("a",List.of(1,2),"z",null);
        assertThat(HistoricalCanonicalJson.digest(a)).isEqualTo(HistoricalCanonicalJson.digest(b));
        assertThat(HistoricalCanonicalJson.digest(a)).isNotEqualTo(HistoricalCanonicalJson.digest(fields("a",List.of(2,1),"z",null)));
        assertThat(HistoricalCanonicalJson.digest(a)).isNotEqualTo(HistoricalCanonicalJson.digest(fields("a",List.of(1,2))));
        assertThat(decode(encodeToken(a))).isEqualTo(a);
        for(String invalid:List.of("", "h2.e30", "h1.e30=", "h1.a", "h1."+"a".repeat(2048))) assertThatThrownBy(()->decode(invalid)).isInstanceOf(HistoricalBalanceException.class);
        assertThat(counter("9223372036854775807",true)).isEqualTo(Long.MAX_VALUE);
        for(Object invalid:List.of(1, "01", "0", "9223372036854775808")) assertThatThrownBy(()->counter(invalid,true)).isInstanceOf(HistoricalBalanceException.class);
    }
}
