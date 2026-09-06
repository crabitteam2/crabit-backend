package com.crabit.backend.history;

import static com.crabit.backend.history.HistoricalBalanceQueryService.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class HistoricalCanonicalFixtureTest {
    @Test void productionFinancialInputBuilderReproducesEveryApprovedGoldenDigest() throws Exception {
        Map<String,Object> document=map(new Yaml().load(Files.readString(Path.of("api/openapi.yaml"))));
        Map<String,Object> examples=map(map(document.get("components")).get("examples"));int checked=0;
        for(var entry:examples.entrySet()){
            Map<String,Object> example=map(entry.getValue());
            if(!example.containsKey("x-canonical-financial-input"))continue;
            Map<String,Object> expected=map(example.get("x-canonical-financial-input"));
            Checkpoint baseline=checkpoint(map(expected.get("baseline")),true);
            List<Checkpoint> checkpoints=new ArrayList<>();checkpoints.add(baseline);
            for(Object raw:list(expected.get("checkpoints")))checkpoints.add(checkpoint(map(raw),false));
            List<Observation> observations=new ArrayList<>();
            for(Object raw:list(expected.get("observations"))){var row=map(raw);observations.add(new Observation(uuid(row.get("observationId")),(String)row.get("status"),(String)row.get("lookupMethod"),Instant.parse((String)row.get("observedAt")),number(row.get("amount")),(String)row.get("failureCode"),row.get("lookupVersion")==null?null:Long.valueOf((String)row.get("lookupVersion"))));}
            List<Map<String,Object>> ledger=new ArrayList<>();for(Object raw:list(expected.get("ledgerEffects")))ledger.add(map(raw));
            Map<String,Object> actual=financialInput(uuid(expected.get("academyId")),uuid(expected.get("studentId")),uuid(expected.get("cardBalanceAccountId")),
                    LocalDate.parse((String)expected.get("fromDate")),LocalDate.parse((String)expected.get("toDateExclusive")),
                    HistoricalPeriods.Granularity.valueOf((String)expected.get("granularity")),Instant.parse((String)expected.get("evaluationHorizon")),
                    Instant.parse((String)expected.get("accountOpenedAt")),map(expected.get("revisionBounds")),baseline,checkpoints,ledger,observations);
            assertThat(HistoricalCanonicalJson.digest(actual)).as(entry.getKey()).isEqualTo(map(example.get("value")).get("inputDigest"));
            checked++;
        }
        assertThat(checked).isEqualTo(6);
    }
    private static Checkpoint checkpoint(Map<String,Object> row,boolean baseline){var rep=map(row.get("representative"));return new Checkpoint(uuid(row.get("checkpointId")),Long.parseLong((String)row.get("checkpointRevision")),Instant.parse((String)row.get("appliedAt")),baseline,Long.parseLong((String)row.get("ledgerApplicationOrder")),uuid(row.get("latestObservationId")),uuid(row.get("lastSuccessfulObservationId")),null,number(row.get("activeWishAllocation")),uuid(rep.get("representativeWishId")),(String)rep.get("historicalState"),number(rep.get("targetAmount")),number(rep.get("numeratorAmount")),List.of());}
    private static UUID uuid(Object value){return value==null?null:UUID.fromString((String)value);}
    private static Long number(Object value){return value==null?null:((Number)value).longValue();}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value){return (List<Object>)value;}
}
