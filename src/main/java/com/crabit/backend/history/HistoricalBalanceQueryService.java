package com.crabit.backend.history;

import static com.crabit.backend.history.HistoricalBalanceException.integrity;
import static com.crabit.backend.history.HistoricalBalanceException.malformed;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Replays immutable account checkpoints under one repeatable-read snapshot; never writes on GET. */
@Service
public class HistoricalBalanceQueryService {
    static final long MAX_MONEY = 9_007_199_254_740_991L;
    private final JdbcTemplate jdbc;
    public HistoricalBalanceQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> query(UUID academyId, UUID studentId, UUID accountId, LocalDate from,
            LocalDate to, HistoricalPeriods.Granularity granularity, String revision) {
        try { return read(academyId, studentId, accountId, from, to, granularity, revision); }
        catch (DataAccessException e) {
            throw new HistoricalBalanceException(HistoricalBalanceException.Code.HISTORICAL_BALANCE_QUERY_UNAVAILABLE);
        } catch (ArithmeticException e) { throw integrity(); }
    }

    private Map<String, Object> read(UUID academyId, UUID studentId, UUID accountId, LocalDate from,
            LocalDate to, HistoricalPeriods.Granularity granularity, String revision) {
        HistoricalPeriods.validate(from, to, granularity);
        Instant snapshotAt = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        Instant openedAt = jdbc.query("""
                select a.opened_at from card_balance_account a
                join student s on s.id=a.student_id join academy ac on ac.id=a.academy_id
                where a.id=? and a.student_id=? and a.academy_id=? and a.closed_at is null
                and exists (select 1 from academy_membership m where m.student_id=a.student_id
                  and m.academy_id=a.academy_id and m.left_at is null)
                """, (rs,n) -> rs.getTimestamp(1).toInstant(), accountId, studentId, academyId)
                .stream().findFirst().orElseThrow(() -> new HistoricalBalanceException(
                        HistoricalBalanceException.Code.CARD_BALANCE_ACCOUNT_NOT_FOUND));

        Map<String, Object> token = revision == null ? null : decode(revision);
        Instant horizon = snapshotAt;
        long maxRevision = Long.MAX_VALUE;
        if (token != null) {
            try {
                if (!accountId.toString().equals(token.get("cardBalanceAccountId"))
                        || !studentId.toString().equals(token.get("studentId"))
                        || !academyId.toString().equals(token.get("academyId"))) throw malformed();
                horizon = Instant.parse(string(token.get("evaluationHorizon")));
                if (horizon.isAfter(snapshotAt)) throw malformed();
                maxRevision = counter(map(token.get("revisionBounds")).get("checkpointRevision"), true);
            } catch (RuntimeException e) { throw malformed(); }
        }
        final Instant evaluationHorizon = horizon;
        List<Checkpoint> checkpoints = jdbc.query("""
                select * from historical_balance_checkpoint where account_id=? and revision<=?
                order by revision
                """, (rs,n) -> checkpoint(rs), accountId, maxRevision);
        if (checkpoints.isEmpty()) {
            if (token != null) throw malformed();
            throw integrity();
        }
        Checkpoint baseline = checkpoints.getFirst();
        Checkpoint selected = checkpoints.getLast();
        if (token != null && selected.revision != maxRevision) throw malformed();
        if (selected.appliedAt.isAfter(horizon)) {
            if (token != null) throw malformed();
            throw integrity();
        }
        Map<String, Object> bounds = bounds(baseline, selected);
        Map<String, Object> expectedToken = fields("schemaVersion",1,"academyId",academyId.toString(),
                "studentId",studentId.toString(),"cardBalanceAccountId",accountId.toString(),
                "revisionBounds",bounds,"evaluationHorizon",horizon.toString());
        String dataRevision = encodeToken(expectedToken);
        if (revision != null && !dataRevision.equals(revision)) throw malformed();

        validateCheckpoints(checkpoints, openedAt);
        Map<UUID, Observation> observations = observations(accountId, checkpoints);
        for (Checkpoint checkpoint : checkpoints) validateObservationLinks(checkpoint, observations);
        List<Map<String, Object>> ledger = ledger(accountId, baseline, selected);
        validateReplay(checkpoints, ledger);
        Instant balanceKnownFrom = checkpoints.stream().filter(c -> c.successId != null)
                .map(c -> c.appliedAt).findFirst().orElse(null);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var period : HistoricalPeriods.buckets(from, to, granularity, horizon)) {
            Checkpoint atPeriod = null;
            for (Checkpoint checkpoint : checkpoints) if (period.includes(checkpoint.appliedAt)) atPeriod = checkpoint;
            items.add(bucket(period, openedAt, baseline.appliedAt, balanceKnownFrom, atPeriod, observations));
        }
        Map<String,Object> input = financialInput(academyId, studentId, accountId, from, to,
                granularity, evaluationHorizon, openedAt, bounds, baseline, checkpoints,
                ledger, new ArrayList<>(observations.values()));
        return fields("schemaVersion",1,"academyId",academyId.toString(),"studentId",studentId.toString(),
                "cardBalanceAccountId",accountId.toString(),"fromDate",from.toString(),"toDateExclusive",to.toString(),
                "granularity",granularity.name(),"timezone","Asia/Seoul","readSnapshotAt",snapshotAt.toString(),
                "evaluationHorizon",horizon.toString(),"dataRevision",dataRevision,
                "inputDigest",HistoricalCanonicalJson.digest(input),"accountOpenedAt",openedAt.toString(),
                "collectionStartedAt",baseline.appliedAt.toString(),"revisionBounds",bounds,"items",items);
    }

    private Map<UUID,Observation> observations(UUID accountId, List<Checkpoint> checkpoints) {
        Set<UUID> needed = new HashSet<>();
        for (Checkpoint c : checkpoints) { if (c.latestId != null) needed.add(c.latestId); if (c.successId != null) needed.add(c.successId); }
        Map<UUID,Observation> result = new LinkedHashMap<>();
        if (needed.isEmpty()) return result;
        Object[] parameters = new Object[needed.size()+1]; parameters[0]=accountId;
        int i=1; for (UUID id:needed) parameters[i++]=id;
        String placeholders = String.join(",", java.util.Collections.nCopies(needed.size(), "?"));
        jdbc.query("select * from balance_observation where account_id=? and id in ("+placeholders+")", rs -> {
            Observation o=new Observation(rs.getObject("id",UUID.class), rs.getString("status"),
                    rs.getString("lookup_method"),rs.getTimestamp("observed_at").toInstant(),
                    rs.getObject("actual_card_balance",Long.class),rs.getString("failure_code"),
                    rs.getObject("account_lookup_version",Long.class));
            result.put(o.id,o);
        },parameters);
        if (result.size()!=needed.size()) throw integrity();
        for (Observation o:result.values()) {
            if (!Set.of("USER_REQUESTED","PRE_DEPOSIT","AUTO_DAILY").contains(o.method)
                    || (o.version!=null && o.version<=0)) throw integrity();
            if (o.status.equals("SUCCEEDED")) { if(o.amount==null || o.failure!=null) throw integrity(); money(o.amount); }
            else if (o.status.equals("FAILED")) { if(o.amount!=null || o.failure==null || o.failure.isBlank() || o.failure.length()>80) throw integrity(); }
            else throw integrity();
        }
        return result;
    }

    private List<Map<String,Object>> ledger(UUID accountId, Checkpoint baseline, Checkpoint selected) {
        List<Map<String,Object>> facts = new ArrayList<>();
        Map<UUID,Map<String,Object>> events = new LinkedHashMap<>();
        jdbc.query("""
                select e.id,e.application_order,e.occurred_at,e.event_type,e.account_delta,e.correction_of_event_id,
                  a.account_id as application_account,a.application_order as recorded_order,a.applied_at,
                  x.wish_id,x.wish_delta
                from ledger_event e left join historical_ledger_application a on a.event_id=e.id
                left join ledger_wish_effect x on x.event_id=e.id and x.account_id=e.account_id
                where e.account_id=? and e.application_order>? and e.application_order<=?
                order by e.application_order,x.wish_id
                """, rs -> {
            UUID id=rs.getObject("id",UUID.class);
            long order=rs.getLong("application_order");
            if (!accountId.equals(rs.getObject("application_account",UUID.class))
                    || rs.getLong("recorded_order")!=order || rs.getTimestamp("applied_at")==null) throw integrity();
            Instant applied=rs.getTimestamp("applied_at").toInstant();
            if(applied.isBefore(baseline.appliedAt)||applied.isAfter(selected.appliedAt)) throw integrity();
            var event=events.get(id);
            if(event==null){
                event=fields("ledgerEventId",id.toString(),"applicationOrder",Long.toString(order),
                        "appliedAt",applied.toString(),"occurredAt",rs.getTimestamp("occurred_at").toInstant().toString(),
                        "type",rs.getString("event_type"),
                        "wishEffects",new ArrayList<Map<String,Object>>());
                events.put(id,event); facts.add(event);
            }
            UUID wish=rs.getObject("wish_id",UUID.class);
            if(wish!=null) list(event.get("wishEffects")).add(fields("wishId",wish.toString(),"deltaAmount",rs.getLong("wish_delta")));
        },accountId,baseline.ledgerOrder,selected.ledgerOrder);
        if(selected.ledgerOrder>baseline.ledgerOrder && (facts.isEmpty()
                || !Long.toString(selected.ledgerOrder).equals(facts.getLast().get("applicationOrder")))) throw integrity();
        return facts;
    }

    static Map<String,Object> financialInput(UUID academyId, UUID studentId, UUID accountId,
            LocalDate from, LocalDate to, HistoricalPeriods.Granularity granularity, Instant horizon,
            Instant openedAt, Map<String,Object> bounds, Checkpoint baseline, List<Checkpoint> checkpoints,
            List<Map<String,Object>> ledger, List<Observation> observations) {
        List<Map<String,Object>> observationFacts = observations.stream()
                .sorted(Comparator.comparing((Observation o) -> o.version, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(o -> o.id.toString())).map(Observation::fact).toList();
        return fields("schemaVersion",1,"academyId",academyId.toString(),"studentId",studentId.toString(),
                "cardBalanceAccountId",accountId.toString(),"fromDate",from.toString(),"toDateExclusive",to.toString(),
                "granularity",granularity.name(),"timezone","Asia/Seoul","evaluationHorizon",horizon.toString(),
                "accountOpenedAt",openedAt.toString(),"collectionStartedAt",baseline.appliedAt.toString(),
                "revisionBounds",bounds,"baseline",baseline.fact(),
                "checkpoints",checkpoints.stream().filter(c -> !c.baseline).map(Checkpoint::fact).toList(),
                "ledgerEffects",ledger,"observations",observationFacts);
    }

    /** Rebuild every collected monetary checkpoint; snapshot values are never trusted alone. */
    static void validateReplay(List<Checkpoint> checkpoints, List<Map<String,Object>> ledger) {
        Map<String,Long> amounts = new LinkedHashMap<>();
        Checkpoint previous = null;
        int index=0;
        for (Checkpoint c:checkpoints) {
            Map<String,Map<String,Object>> active = activeWishes(c);
            if(previous==null) active.forEach((id,w)->amounts.put(id,storedLong(w.get("amount"))));
            else {
                long processedOrder=previous.ledgerOrder;
                while(index<ledger.size() && counter(ledger.get(index).get("applicationOrder"),true)<=c.ledgerOrder){
                    Map<String,Object> event=ledger.get(index++);
                    long order=counter(event.get("applicationOrder"),true);
                    processedOrder=order;
                    Instant at=Instant.parse((String)event.get("appliedAt"));
                    if(order<=previous.ledgerOrder||at.isBefore(previous.appliedAt)||at.isAfter(c.appliedAt))throw integrity();
                    var effects=list(event.get("wishEffects"));
                    String type=(String)event.get("type");
                    int expected=type.equals("CARD_BALANCE_CHANGE")?0:type.equals("WISH_TRANSFER")?2:1;
                    if(effects.size()!=expected)throw integrity();
                    Set<String> seen=new HashSet<>();
                    long transferSum=0;
                    for(var effect:effects){
                        String id=(String)effect.get("wishId");
                        if(!seen.add(id))throw integrity();
                        long delta=storedLong(effect.get("deltaAmount"));
                        long amount=Math.addExact(amounts.getOrDefault(id,0L),delta);money(amount);
                        amounts.put(id,amount);transferSum=Math.addExact(transferSum,delta);
                    }
                    if(type.equals("WISH_TRANSFER")&&transferSum!=0)throw integrity();
                }
                if(processedOrder!=c.ledgerOrder)throw integrity();
                for(var entry:amounts.entrySet())if(!active.containsKey(entry.getKey())&&entry.getValue()!=0)throw integrity();
                for(var entry:active.entrySet()){
                    long expected=amounts.getOrDefault(entry.getKey(),0L);
                    if(expected!=storedLong(entry.getValue().get("amount")))throw integrity();
                }
                amounts.keySet().retainAll(active.keySet());
                active.forEach((id,w)->amounts.putIfAbsent(id,0L));
            }
            previous=c;
        }
        if(index!=ledger.size())throw integrity();
    }
    private static Map<String,Map<String,Object>> activeWishes(Checkpoint c){
        if(!(c.activeWishes instanceof List<?> rows))throw integrity();
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();long total=0;String previousId=null;
        for(Object row:rows){
            if(!(row instanceof Map<?,?> raw)||!raw.keySet().equals(Set.of("wishId","state","targetAmount","amount")))throw integrity();
            @SuppressWarnings("unchecked") Map<String,Object> wish=(Map<String,Object>)raw;
            String id = storedUuid(wish.get("wishId"));
            if((previousId!=null&&previousId.compareTo(id)>=0)||result.put(id,wish)!=null)throw integrity();
            previousId=id;
            if(!(wish.get("state") instanceof String state)||!Set.of("IN_PROGRESS","AMOUNT_REACHED").contains(state))throw integrity();
            long amount=storedLong(wish.get("amount")),target=storedLong(wish.get("targetAmount"));
            money(amount);money(target);if(target==0||amount>target||(state.equals("AMOUNT_REACHED")&&amount!=target))throw integrity();
            total=Math.addExact(total,amount);
        }
        if(total!=c.allocation)throw integrity();
        if(c.representativeId!=null){
            var selected=result.get(c.representativeId.toString());
            if(selected==null||!Objects.equals(c.state,selected.get("state"))||c.amount==null||c.target==null
                    ||c.amount!=storedLong(selected.get("amount"))||c.target!=storedLong(selected.get("targetAmount")))throw integrity();
        }
        return result;
    }
    private static String storedUuid(Object value){
        if(!(value instanceof String id))throw integrity();
        try{if(!UUID.fromString(id).toString().equals(id))throw integrity();return id;}
        catch(IllegalArgumentException e){throw integrity();}
    }
    private static long storedLong(Object value){
        if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long))throw integrity();
        return ((Number)value).longValue();
    }

    static Map<String,Object> bucket(HistoricalPeriods.Period period, Instant openedAt, Instant collectedAt,
            Instant firstSuccessAt, Checkpoint checkpoint, Map<UUID,Observation> observations) {
        String unknown=!period.includes(openedAt)?"ACCOUNT_NOT_OPEN":!period.includes(collectedAt)?"PRE_COLLECTION_UNKNOWN":null;
        if(unknown==null && checkpoint==null) throw integrity();
        if(unknown!=null && checkpoint!=null) throw integrity();
        Observation success=checkpoint==null||checkpoint.successId==null?null:observations.get(checkpoint.successId);
        Observation latest=checkpoint==null||checkpoint.latestId==null?null:observations.get(checkpoint.latestId);
        Map<String,Object> balance;
        if(success==null) balance=fields("knowledge","UNKNOWN","unknownReason",unknown==null?"NO_SUCCESSFUL_OBSERVATION":unknown,
                "lastSuccessfulObservedCardBalance",null,"lastSuccessfulObservationId",null,"lastSuccessfulObservedAt",null,
                "ledgerAvailableBalance",null,"displayAvailableBalance",null,"unresolvedShortage",null);
        else {
            long available=Math.subtractExact(success.amount,checkpoint.allocation);
            if(available < -MAX_MONEY || available>MAX_MONEY) throw integrity();
            balance=fields("knowledge","KNOWN","unknownReason",null,"lastSuccessfulObservedCardBalance",success.amount,
                    "lastSuccessfulObservationId",success.id.toString(),"lastSuccessfulObservedAt",success.observedAt.toString(),
                    "ledgerAvailableBalance",available,"displayAvailableBalance",Math.max(available,0),"unresolvedShortage",Math.max(-available,0));
        }
        Map<String,Object> lookup=latest==null?fields("status",unknown==null?"NOT_LOOKED_UP":"UNKNOWN","observationId",null,
                "observedAt",null,"lookupMethod",null,"failureCode",null):fields("status",latest.status,
                "observationId",latest.id.toString(),"observedAt",latest.observedAt.toString(),"lookupMethod",latest.method,"failureCode",latest.failure);
        boolean full=!collectedAt.isAfter(period.startInstant())&&!openedAt.isAfter(period.startInstant());
        return fields("periodStart",period.start().toString(),"periodEndExclusive",period.endExclusive().toString(),
                "periodStatus",period.completed()?"COMPLETED":"PROVISIONAL","evaluatedAt",period.evaluatedAt().toString(),
                "evaluationBoundary",period.completed()?"BEFORE":"THROUGH",
                "coverage",fields("status",unknown!=null?"NONE":full?"FULL":"PARTIAL",
                        "coveredFrom",unknown!=null?null:(collectedAt.isAfter(period.startInstant())?collectedAt:period.startInstant()).toString(),
                        "balanceKnownFrom",success==null?null:firstSuccessAt.toString(),"allocationKnownFrom",unknown!=null?null:collectedAt.toString(),
                        "representativeKnownFrom",unknown!=null?null:collectedAt.toString()),
                "balance",balance,"allocation",fields("knowledge",unknown==null?"KNOWN":"UNKNOWN","unknownReason",unknown,
                        "activeWishAllocation",unknown==null?checkpoint.allocation:null),"latestLookup",lookup,
                "representative",unknown==null?checkpoint.representative():emptyRepresentative(unknown),
                "provenance",fields("checkpointId",checkpoint==null?null:checkpoint.id.toString(),
                        "checkpointRevision",checkpoint==null?null:Long.toString(checkpoint.revision),
                        "ledgerApplicationOrder",checkpoint==null?null:Long.toString(checkpoint.ledgerOrder),
                        "latestObservationId",latest==null?null:latest.id.toString(),"lastSuccessfulObservationId",success==null?null:success.id.toString()));
    }

    private static void validateCheckpoints(List<Checkpoint> checkpoints, Instant openedAt) {
        Checkpoint previous=null;
        for(Checkpoint c:checkpoints){
            if(c.revision!=(previous==null?1:previous.revision+1) || c.baseline!=(previous==null)
                    || c.appliedAt.isBefore(openedAt) || c.ledgerOrder<0
                    || (previous!=null && (c.appliedAt.isBefore(previous.appliedAt)||c.ledgerOrder<previous.ledgerOrder))) throw integrity();
            money(c.allocation); c.representative(); previous=c;
        }
    }
    private static void validateObservationLinks(Checkpoint c, Map<UUID,Observation> observations) {
        Observation latest=c.latestId==null?null:observations.get(c.latestId),success=c.successId==null?null:observations.get(c.successId);
        if(c.successId!=null && (success==null || !success.status.equals("SUCCEEDED"))) throw integrity();
        if(c.latestId!=null && latest==null) throw integrity();
        if(latest==null && (c.lookupVersion!=null || success!=null)) throw integrity();
        if(latest!=null && (!Objects.equals(c.lookupVersion,latest.version)
                || (latest.status.equals("SUCCEEDED")&&!latest.id.equals(c.successId)))) throw integrity();
    }
    private static Map<String,Object> bounds(Checkpoint baseline, Checkpoint selected) {
        return fields("baselineCheckpointId",baseline.id.toString(),"baselineRevision",Long.toString(baseline.revision),
                "baselineLedgerApplicationOrder",Long.toString(baseline.ledgerOrder),"checkpointId",selected.id.toString(),
                "checkpointRevision",Long.toString(selected.revision),"ledgerApplicationOrder",Long.toString(selected.ledgerOrder),
                "observationLookupVersion",selected.lookupVersion==null?null:Long.toString(selected.lookupVersion));
    }
    private static Checkpoint checkpoint(ResultSet rs) throws SQLException {
        Object active;
        try { active=HistoricalCanonicalJson.parse(rs.getString("active_wishes")); }
        catch(RuntimeException e){throw integrity();}
        return new Checkpoint(rs.getObject("id",UUID.class),rs.getLong("revision"),rs.getTimestamp("applied_at").toInstant(),
                rs.getBoolean("is_baseline"),rs.getLong("ledger_application_order"),rs.getObject("latest_observation_id",UUID.class),
                rs.getObject("last_successful_observation_id",UUID.class),rs.getObject("observation_lookup_version",Long.class),
                rs.getLong("active_wish_allocation"),rs.getObject("representative_wish_id",UUID.class),
                rs.getString("representative_state"),rs.getObject("representative_target_amount",Long.class),
                rs.getObject("representative_amount",Long.class),active);
    }
    record Checkpoint(UUID id,long revision,Instant appliedAt,boolean baseline,long ledgerOrder,UUID latestId,
            UUID successId,Long lookupVersion,long allocation,UUID representativeId,String state,Long target,Long amount,Object activeWishes) {
        Map<String,Object> representative(){
            if(representativeId==null){if(state!=null||target!=null||amount!=null)throw integrity();return emptyRepresentative("KNOWN_NONE");}
            if(state==null||target==null||amount==null||target<=0||!Set.of("IN_PROGRESS","AMOUNT_REACHED").contains(state))throw integrity();
            money(target);money(amount);if(amount>target||(state.equals("AMOUNT_REACHED")&&!amount.equals(target)))throw integrity();
            long progress=state.equals("AMOUNT_REACHED")?100:Math.min(99,BigInteger.valueOf(amount).multiply(BigInteger.valueOf(100)).divide(BigInteger.valueOf(target)).longValueExact());
            return fields("status","KNOWN_SELECTED","representativeWishId",representativeId.toString(),"historicalState",state,
                    "numeratorAmount",amount,"targetAmount",target,"progressPercent",progress);
        }
        Map<String,Object> fact(){return fields("checkpointId",id.toString(),"checkpointRevision",Long.toString(revision),
                "ledgerApplicationOrder",Long.toString(ledgerOrder),"appliedAt",appliedAt.toString(),"activeWishAllocation",allocation,
                "representative",representative(),"latestObservationId",stringOrNull(latestId),"lastSuccessfulObservationId",stringOrNull(successId));}
    }
    record Observation(UUID id,String status,String method,Instant observedAt,Long amount,String failure,Long version) {
        Map<String,Object> fact(){return fields("observationId",id.toString(),"lookupVersion",version==null?null:Long.toString(version),
                "observedAt",observedAt.toString(),"lookupMethod",method,"status",status,"amount",amount,"failureCode",failure);}
    }
    static Map<String,Object> emptyRepresentative(String status){return fields("status",status,"representativeWishId",null,
            "historicalState",null,"numeratorAmount",null,"targetAmount",null,"progressPercent",null);}
    static void money(long amount){if(amount<0||amount>MAX_MONEY)throw integrity();}
    static Map<String,Object> fields(Object... entries){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)result.put((String)entries[i],entries[i+1]);return result;}
    private static String stringOrNull(Object value){return value==null?null:value.toString();}
    private static String string(Object value){if(!(value instanceof String text))throw malformed();return text;}
    static long counter(Object value,boolean positive){String text=string(value);if(!text.matches("0|[1-9][0-9]{0,18}"))throw malformed();try{long n=Long.parseLong(text);if(positive&&n==0)throw malformed();return n;}catch(NumberFormatException e){throw malformed();}}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?>))throw malformed();return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> list(Object value){return (List<Map<String,Object>>)value;}
    static String encodeToken(Map<String,Object> value){return "h1."+Base64.getUrlEncoder().withoutPadding().encodeToString(HistoricalCanonicalJson.encode(value).getBytes(StandardCharsets.UTF_8));}
    static Map<String,Object> decode(String token){
        if(token.length()>2048||!token.matches("h1\\.[A-Za-z0-9_-]+"))throw malformed();
        try{byte[] bytes=Base64.getUrlDecoder().decode(token.substring(3));Map<String,Object> parsed=map(HistoricalCanonicalJson.parse(new String(bytes,StandardCharsets.UTF_8)));if(!encodeToken(parsed).equals(token))throw malformed();return parsed;}
        catch(RuntimeException e){throw malformed();}
    }
}
