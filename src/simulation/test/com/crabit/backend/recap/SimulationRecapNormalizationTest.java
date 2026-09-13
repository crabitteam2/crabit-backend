package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.*;
import com.crabit.backend.simulation.SimulationBundleReader;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SimulationRecapNormalizationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    final UUID generation=UUID.randomUUID(),student=UUID.randomUUID(),account=UUID.randomUUID(),academy=UUID.randomUUID(),wish=UUID.randomUUID(),root=UUID.randomUUID();
    final Map<String,UUID> ids=Map.of("RECAP_GENERATION:week",generation,"STUDENT:person",student,"ACCOUNT:wallet",account,"ACADEMY:school",academy,"WISH:goal",wish,"LEDGER_ROOT:deposit",root);
    ObjectNode request() {
        var r=JSON.createObjectNode().put("schema_version",1).put("algorithm_version","recap-1")
            .put("generation_id",generation.toString()).put("student_id",student.toString())
            .put("card_balance_account_id",account.toString()).put("academy_id",academy.toString()).put("kind","WEEKLY")
            .put("reference_date","2026-06-07").put("snapshot_at","2026-06-07T15:00:00Z");
        r.putObject("period").put("start_date","2026-06-01").put("end_date_exclusive","2026-06-08").put("timezone","Asia/Seoul");
        var input=r.putObject("input");input.put("representative_wish_id",wish.toString());
        input.putArray("wishes").addObject().put("wish_id",wish.toString()).put("title",wish.toString()).put("target_amount",10000).putNull("deleted_at");
        input.putArray("effective_transactions").addObject().put("root_event_id",root.toString()).put("wish_id",wish.toString()).put("amount",0).put("occurred_at","2026-06-07T14:59:59.999999Z");
        input.putArray("success_story_candidates").addObject().put("wish_id",wish.toString()).put("type_title","A");
        sign(r);return r;
    }
    void sign(ObjectNode r) {var basis=r.deepCopy();basis.remove("generation_id");basis.remove("input_digest");r.put("input_digest",SimulationBundleReader.digest(JsonCanonicalizer.canonicalize(JSON,basis)));}
    ObjectNode stored(ObjectNode request) {
        var row=JSON.createObjectNode().put("id",generation.toString()).put("account_id",account.toString())
            .put("student_id",student.toString()).put("academy_id",academy.toString()).put("kind","WEEKLY")
            .put("schema_version",1).put("algorithm_version","recap-1").put("period_start","2026-06-01").put("period_end_exclusive","2026-06-08")
            .put("input_digest",request.get("input_digest").asString()).put("state","SUCCEEDED").put("request_json",JSON.writeValueAsString(request));
        var view=JSON.createObjectNode();view.putArray("stories").addObject().put("wish_id",wish.toString()).put("title",wish.toString());
        view.putObject("page3_academy_success_stories").putArray("stories").addObject().put("wish_id",wish.toString()).put("type_title","A");
        view.putArray("ranking").add(3).add(1).add(2);view.put("amount",0);view.putNull("ctr");
        row.put("view_json",JSON.writeValueAsString(view));row.put("internal_metrics_json",JSON.writeValueAsString(Map.of("account_id",account.toString())));return row;
    }
    @Test void preservesTextZeroNullTimestampAndRankingWhileMappingNestedIds() {
        var request=request();var row=stored(request);byte[] before=JSON.writeValueAsBytes(row);
        var output=new SimulationRecapNormalization(ids).stored(row);
        assertThat(output.get("request_json").get("input").get("wishes").get(0).get("title").asString()).isEqualTo(wish.toString());
        assertThat(output.get("request_json").get("input").get("effective_transactions").get(0).get("root_event_id").asString()).isEqualTo("LEDGER_ROOT:deposit");
        assertThat(output.get("view_json").get("stories").get(0).get("wish_id").asString()).isEqualTo("WISH:goal");
        assertThat(output.get("view_json").get("ranking")).isEqualTo(JSON.readTree("[3,1,2]"));
        assertThat(output.get("view_json").get("ctr").isNull()).isTrue();assertThat(output.get("view_json").get("amount").asLong()).isZero();
        assertThat(output.get("request_json").get("input").get("effective_transactions").get(0).get("occurred_at").asString()).isEqualTo("2026-06-07T14:59:59.999999Z");
        assertThat(JSON.writeValueAsBytes(row)).isEqualTo(before);
    }
    @Test void changedInputCannotHideBehindOriginalDigest() {
        var r=request();((ObjectNode)r.get("input").get("wishes").get(0)).put("target_amount",20000);
        assertThatThrownBy(()->new SimulationRecapNormalization(ids).stored(stored(r))).hasMessage("RECAP_NORMALIZATION_INPUT_DIGEST");
    }
    @Test void unknownNestedIdentityFailsClosedEvenWithValidDigest() {
        var r=request();((ObjectNode)r.get("input").get("effective_transactions").get(0)).put("root_event_id",UUID.randomUUID().toString());sign(r);
        assertThatThrownBy(()->new SimulationRecapNormalization(ids).stored(stored(r))).hasMessage("RECAP_NORMALIZATION_UNKNOWN_ID:LEDGER_ROOT");
    }
    @Test void responseMismatchIsRejectedBeforeComparison() {
        var r=request();var row=stored(r);var response=r.deepCopy();response.remove("input");response.set("view",JSON.readTree(row.get("view_json").asString()));response.set("internal_metrics",JSON.readTree(row.get("internal_metrics_json").asString()));
        var n=new SimulationRecapNormalization(ids);byte[] request=JSON.writeValueAsBytes(r);
        n.exchange(request,JSON.writeValueAsBytes(response),row);
        ((ObjectNode)response.get("view")).put("amount",1);
        assertThatThrownBy(()->n.exchange(request,JSON.writeValueAsBytes(response),row)).hasMessage("RECAP_NORMALIZATION_RESPONSE_STORAGE");
        var other=r.deepCopy();other.put("snapshot_at","2026-06-08T15:00:00Z");
        assertThatThrownBy(()->n.exchange(JSON.writeValueAsBytes(other),JSON.writeValueAsBytes(response),row)).hasMessage("RECAP_NORMALIZATION_RAW_REQUEST_STORAGE");
    }
    @Test void orderingAndMoneyChangesRemainVisibleAfterValidResigning() {
        var r=request();var n=new SimulationRecapNormalization(ids);String original=n.logicalDigest(r);
        ((ObjectNode)r.get("input").get("effective_transactions").get(0)).put("amount",1);sign(r);
        assertThat(n.logicalDigest(r)).isNotEqualTo(original);
        var row=stored(r);var first=n.stored(row);var view=(ObjectNode)JSON.readTree(row.get("view_json").asString());view.set("ranking",JSON.readTree("[1,2,3]"));row.put("view_json",JSON.writeValueAsString(view));
        assertThat(n.stored(row)).isNotEqualTo(first);
    }
    @Test void rawEvidenceRejectsTrailingJsonAndDuplicateKeys() {
        var r=request();var row=stored(r);var n=new SimulationRecapNormalization(ids);
        byte[] trailing=(JSON.writeValueAsString(r)+" {}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(()->n.exchange(trailing,null,row)).isInstanceOf(tools.jackson.core.JacksonException.class);
        String duplicate=JSON.writeValueAsString(r).replaceFirst("\\{","{\"schema_version\":1,");
        assertThatThrownBy(()->n.exchange(duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8),null,row)).isInstanceOf(tools.jackson.core.JacksonException.class);
    }

    @Test void anonymousPeerMultisetsAndSameInstantEffectsIgnoreOnlyRuntimeOrdering() {
        var first=request();
        var peer=first.withObject("/input").putObject("peer_metrics");
        peer.set("habit_active_weeks",JSON.readTree("[2,0,2]"));
        peer.set("achievement_rates",JSON.readTree("[25.0,75.0]"));
        var tx=(tools.jackson.databind.node.ArrayNode)first.get("input").get("effective_transactions");
        var in=((ObjectNode)tx.get(0)).deepCopy().put("type","TRANSFER_IN").put("amount",1000);
        var out=in.deepCopy().put("type","TRANSFER_OUT");
        tx.removeAll();tx.add(out);tx.add(in);sign(first);
        var second=first.deepCopy();
        second.withObject("/input/peer_metrics").set("habit_active_weeks",JSON.readTree("[2,2,0]"));
        second.withObject("/input/peer_metrics").set("achievement_rates",JSON.readTree("[75.0,25.0]"));
        ((tools.jackson.databind.node.ArrayNode)second.get("input").get("effective_transactions")).removeAll().add(in).add(out);sign(second);
        var n=new SimulationRecapNormalization(ids);
        byte[] raw=JSON.writeValueAsBytes(first);
        assertThat(n.logicalDigest(second)).isEqualTo(n.logicalDigest(first));
        assertThat(n.stored(stored(second))).isEqualTo(n.stored(stored(first)));
        assertThat(JSON.writeValueAsBytes(first)).isEqualTo(raw);
        second.withObject("/input/peer_metrics").set("habit_active_weeks",JSON.readTree("[2,0]"));sign(second);
        assertThat(n.logicalDigest(second)).isNotEqualTo(n.logicalDigest(first));
        second=first.deepCopy();
        ((ObjectNode)second.get("input").get("effective_transactions").get(0)).put("amount",999);sign(second);
        assertThat(n.logicalDigest(second)).isNotEqualTo(n.logicalDigest(first));
    }

}
