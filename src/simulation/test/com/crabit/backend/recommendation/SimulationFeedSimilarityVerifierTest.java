package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.node.*;

class SimulationFeedSimilarityVerifierTest {
    SimulationFeedInputVerifierTest.Fixture fixture(){return new SimulationFeedInputVerifierTest().fixture();}
    ObjectNode candidate(SimulationFeedInputVerifierTest.Fixture f){return (ObjectNode)f.request().get("candidates").get(0);}
    ObjectNode own(SimulationFeedInputVerifierTest.Fixture f,String id,String purpose,long amount,String created,String target) {
        var w=((ArrayNode)f.source().get("wish")).addObject().put("id",id).put("account_id","viewer-account")
            .put("academy_id","academy").put("purpose",purpose).put("target_amount",amount)
            .put("created_at",created).put("state","IN_PROGRESS").putNull("deleted_at").putNull("completed_at");
        if(target==null)w.putNull("target_date");else w.put("target_date",target);return w;
    }
    @Test void independentImplementationsMatchPinnedPythonOracle() throws Exception {
        var oracle=SimulationFeedInputVerifierTest.JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/recommendation/feed-feature-oracle.json")));
        for(var row:oracle.get("categories"))assertThat(SimulationFeedSimilarityVerifier.classify(row.get("title").asString())).as(row.toString()).isEqualTo(row.get("category").asString());
        for(var row:oracle.get("similarities"))assertThat(SimulationFeedSimilarityVerifier.ratio(row.get("left").asString(),row.get("right").asString())).as(row.toString()).isEqualTo(row.get("ratio").asDouble());
        var extra=SimulationFeedInputVerifierTest.JSON.readTree(Files.readAllBytes(Path.of("src/test/resources/simulation/title-similarity-oracle.json")));
        for(var row:extra)assertThat(SimulationFeedSimilarityVerifier.ratio(row.get("left").asString(),row.get("right").asString())).as(row.toString()).isEqualTo(row.get("ratio").asDouble());
    }
    @Test void noRepresentativeMeansZeroAndForgedFeaturesCannotPass() {
        for(String field:List.of("category_id","basic_similarity","title_similarity","feature_version","classifier_version")) {
            var f=fixture();
            if(field.endsWith("version"))f.request().put(field,"forged");
            else if(field.equals("category_id"))candidate(f).put(field,"전자기기");else candidate(f).put(field,0.5);
            assertThatThrownBy(()->SimulationFeedSimilarityVerifier.verify(f.request(),f.source())).hasMessageStartingWith("FEED_FEATURE_");
        }
    }
    @Test void explicitReachedSelectionWinsAndInvalidSelectionFallsBackToOldestActive() {
        var f=fixture();own(f,"old","책 모으기",10000,"2026-06-01T00:00:00Z",null);
        var selected=own(f,"new","노트북",50000,"2026-06-02T00:00:00Z","2026-06-03").put("state","AMOUNT_REACHED");
        ((ArrayNode)f.source().get("representative_wish_selection")).addObject().put("account_id","viewer-account").put("wish_id","new");
        candidate(f).put("basic_similarity",0.0).put("title_similarity",0.0);
        SimulationFeedSimilarityVerifier.verify(f.request(),f.source());
        selected.put("state","COMPLETED");candidate(f).put("basic_similarity",1.0).put("title_similarity",1.0);
        SimulationFeedSimilarityVerifier.verify(f.request(),f.source());
        selected.put("state","IN_PROGRESS").put("deleted_at","2026-06-03T00:00:00Z");
        SimulationFeedSimilarityVerifier.verify(f.request(),f.source());
    }
    @Test void fallbackUsesCreationThenIdAndNeverImplicitReachedWish() {
        var f=fixture();own(f,"b","노트북",10000,"2026-06-01T00:00:00Z",null);
        var earlier=own(f,"a","책 모으기",10000,"2026-06-01T00:00:00Z",null);
        candidate(f).put("basic_similarity",1).put("title_similarity",1);SimulationFeedSimilarityVerifier.verify(f.request(),f.source());
        earlier.put("state","AMOUNT_REACHED");candidate(f).put("basic_similarity",2.0/3).put("title_similarity",0);
        SimulationFeedSimilarityVerifier.verify(f.request(),f.source());
        ((ArrayNode)f.source().get("representative_wish_selection")).addObject().put("account_id","viewer-account").put("wish_id","wish");
        assertThatThrownBy(()->SimulationFeedSimilarityVerifier.verify(f.request(),f.source())).hasMessage("FEED_FEATURE_SELECTION_OWNER");
    }
    @ParameterizedTest @CsvSource({"9999,0","10000,1","29999,1","30000,0","49999,0","50000,0","99999,0","100000,0","299999,0","300000,0"})
    void amountBoundaryMatchesAreIndependent(long amount,int match) {
        var f=fixture();own(f,"own","책 모으기",amount,"2026-06-01T00:00:00Z",null);
        candidate(f).put("basic_similarity",(2+match)/3.0).put("title_similarity",1);
        SimulationFeedSimilarityVerifier.verify(f.request(),f.source());
    }
    @ParameterizedTest @CsvSource({"29,0","30,1","89,1","90,2","179,2","180,3"})
    void deadlineBoundariesUseSeoulDate(int days,int bucket) {
        var f=fixture();String created="2026-06-01T15:01:00Z"; // June 2 in Seoul
        own(f,"own","책 모으기",10000,created,"2026-07-01"); // 29 days, bucket zero
        var w=(ObjectNode)f.source().get("wish").get(0);w.put("created_at",created).put("target_date",LocalDate.of(2026,6,2).plusDays(days).toString());
        candidate(f).put("basic_similarity",(2+(bucket==0?1:0))/3.0).put("title_similarity",1);
        SimulationFeedSimilarityVerifier.verify(f.request(),f.source());
    }
}
