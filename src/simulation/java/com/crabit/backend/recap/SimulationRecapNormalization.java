package com.crabit.backend.recap;

import com.crabit.backend.simulation.SimulationBundleReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Comparison only: verifies original digest and frozen storage before replacing typed runtime identities. */
public final class SimulationRecapNormalization {
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Map<String,Map<String,String>> ids=new HashMap<>();
    public SimulationRecapNormalization(Map<String,UUID> identities) {
        identities.forEach((key,id)->{
            int split=key.indexOf(':');check(split>0,"IDENTITY_KEY");
            String kind=key.substring(0,split),logical=kind+":"+key.substring(split+1);
            check(ids.computeIfAbsent(kind,k->new HashMap<>()).putIfAbsent(id.toString(),logical)==null,"IDENTITY_BIJECTION");
        });
    }
    public JsonNode stored(JsonNode row) {
        JsonNode request=parse(row.get("request_json"));
        verifyRequest(request);
        for(var pair:Map.of("id","generation_id","account_id","card_balance_account_id",
                "student_id","student_id","academy_id","academy_id","input_digest","input_digest",
                "kind","kind","algorithm_version","algorithm_version","schema_version","schema_version").entrySet())
            check(Objects.equals(row.get(pair.getKey()),request.get(pair.getValue())),"STORED_BINDING:"+pair.getKey());
        check(row.get("period_start").equals(request.get("period").get("start_date"))
            && row.get("period_end_exclusive").equals(request.get("period").get("end_date_exclusive")),"STORED_PERIOD");
        String state=row.get("state").asString();
        check(Set.of("SUCCEEDED","NOT_ELIGIBLE").contains(state),"TERMINAL_STATE_REQUIRED");
        check(row.hasNonNull("view_json")==state.equals("SUCCEEDED")
            && row.hasNonNull("internal_metrics_json")==state.equals("SUCCEEDED"),"STORED_RESULT_STATE");
        if(state.equals("SUCCEEDED")) SimulationRecapResultVerifier.verify(request,parse(row.get("view_json")));
        String digest=logicalDigest(request);
        var result=JSON.createObjectNode();
        for(String field:new TreeSet<>(row.propertyNames())) {
            JsonNode value=row.get(field);
            if(Set.of("request_json","view_json","internal_metrics_json").contains(field) && !value.isNull())
                result.set(field,typed(parse(value),digest));
            else if(field.equals("id"))result.set(field,ref("RECAP_GENERATION",value));
            else result.set(field,typedField(field,value,digest));
        }
        return result;
    }
    /** Null response is required for NOT_ELIGIBLE. It must never become a fabricated HTTP response. */
    public JsonNode exchange(byte[] requestBytes,byte[] responseBytes,JsonNode row) {
        JsonNode request=JSON.readTree(requestBytes);
        check(request.equals(parse(row.get("request_json"))),"RAW_REQUEST_STORAGE");
        JsonNode normalizedStored=stored(row);
        var result=JSON.createObjectNode().put("schemaVersion",1).put("schemaKind","simulation-normalized-recap");
        String digest=logicalDigest(request);
        result.set("request",typed(request,digest));result.set("stored",normalizedStored);
        if(row.get("state").asString().equals("NOT_ELIGIBLE")) {
            check(responseBytes==null,"INELIGIBLE_HTTP_RESPONSE");result.putNull("response");result.put("pythonInvoked",false);
        } else {
            check(responseBytes!=null,"SUCCESS_HTTP_RESPONSE_REQUIRED");
            JsonNode response=JSON.readTree(responseBytes);
            for(String field:List.of("schema_version","algorithm_version","generation_id","input_digest","student_id","card_balance_account_id","academy_id","kind","period"))
                check(Objects.equals(request.get(field),response.get(field)),"RESPONSE_BINDING:"+field);
            check(Objects.equals(response.get("view"),parse(row.get("view_json")))
                && Objects.equals(response.get("internal_metrics"),parse(row.get("internal_metrics_json"))),"RESPONSE_STORAGE");
            result.set("response",typed(response,digest));result.put("pythonInvoked",true);
        }
        return result;
    }
    public String logicalDigest(JsonNode request) {
        verifyRequest(request);
        ObjectNode basis=(ObjectNode)request.deepCopy();basis.remove("generation_id");basis.remove("input_digest");
        return "logical:"+SimulationBundleReader.digest(JsonCanonicalizer.canonicalize(JSON,typed(basis,null)));
    }
    private void verifyRequest(JsonNode request) {
        check(request!=null && request.isObject() && request.hasNonNull("input_digest") && request.hasNonNull("generation_id"),"REQUEST_SHAPE");
        ObjectNode basis=(ObjectNode)request.deepCopy();basis.remove("generation_id");basis.remove("input_digest");
        check(SimulationBundleReader.digest(JsonCanonicalizer.canonicalize(JSON,basis)).equals(request.get("input_digest").asString()),"INPUT_DIGEST");
    }
    private JsonNode typed(JsonNode value,String digest) {
        if(value.isArray()) {var out=JSON.createArrayNode();for(JsonNode v:value)out.add(typed(v,digest));return out;}
        if(!value.isObject())return value.deepCopy();
        var out=JSON.createObjectNode();for(String key:new TreeSet<>(value.propertyNames()))out.set(key,typedField(key,value.get(key),digest));return out;
    }
    private JsonNode typedField(String field,JsonNode value,String digest) {
        if(value.isNull())return value.deepCopy();
        String kind=switch(field) {
            case "generation_id" -> "RECAP_GENERATION";
            case "student_id" -> "STUDENT";
            case "account_id","card_balance_account_id" -> "ACCOUNT";
            case "academy_id" -> "ACADEMY";
            case "wish_id","representative_wish_id" -> "WISH";
            case "root_event_id" -> "LEDGER_ROOT";
            default -> null;
        };
        if(kind!=null)return ref(kind,value);
        if(field.equals("input_digest")) {check(digest!=null,"LOGICAL_DIGEST_REQUIRED");return JSON.getNodeFactory().stringNode(digest);}
        if(field.equals("input"))return normalizedInput(value,digest);
        return typed(value,digest);
    }
    private JsonNode normalizedInput(JsonNode value,String digest) {
        ObjectNode out=(ObjectNode)typed(value,digest);
        // These anonymous distributions are independent multisets, not paired peer records.
        // Preserve multiplicity and values; never sort stories, ranked outputs or raw evidence.
        if(out.hasNonNull("peer_metrics")) {
            var peers=(ObjectNode)out.get("peer_metrics");
            for(String field:List.of("habit_active_weeks","achievement_rates")) {
                JsonNode values=peers.get(field);check(values!=null && values.isArray(),"PEER_ARRAY");
                var ordered=new ArrayList<JsonNode>();
                for(JsonNode v:values) {check(v.isNumber(),"PEER_NUMBER");ordered.add(v);}
                ordered.sort(Comparator.comparing(v->new java.math.BigDecimal(v.asString())));
                peers.set(field,JSON.valueToTree(ordered));
            }
        }
        if(out.hasNonNull("effective_transactions")) {
            var ordered=new ArrayList<JsonNode>();
            for(JsonNode tx:out.get("effective_transactions"))ordered.add(tx);
            // Frozen rows have already been verified against raw ledger effects. UUID tie order
            // is not event chronology: compare time first, then the complete typed logical row.
            ordered.sort(Comparator.comparing((JsonNode tx)->java.time.Instant.parse(tx.get("occurred_at").asString()))
                .thenComparing(tx->new String(JsonCanonicalizer.canonicalize(JSON,tx),StandardCharsets.UTF_8)));
            out.set("effective_transactions",JSON.valueToTree(ordered));
        }
        return out;
    }
    private JsonNode ref(String kind,JsonNode value) {
        String mapped=ids.getOrDefault(kind,Map.of()).get(value.asString());
        check(mapped!=null,"UNKNOWN_ID:"+kind);return JSON.getNodeFactory().stringNode(mapped);
    }
    private static JsonNode parse(JsonNode value) {
        check(value!=null && value.isString(),"STORED_JSON_REQUIRED");
        return JSON.readTree(value.asString().getBytes(StandardCharsets.UTF_8));
    }
    private static void check(boolean ok,String code) {if(!ok)throw new IllegalArgumentException("RECAP_NORMALIZATION_"+code);}
}
