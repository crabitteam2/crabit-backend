package com.crabit.backend.simulation;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** Checks identity and immutable evidence links. Does not infer execution from a claimed result. */
public final class SimulationEvidenceIndex {
    public record Result(int identityMappings, int rawRecords, int reportedRules) {}

    public Result verify(JsonNode manifest, JsonNode students, List<JsonNode> events,
                         JsonNode idMap, JsonNode rawIndex, JsonNode validation,
                         Map<String, byte[]> artifacts) {
        String dataset = text(manifest,"datasetId");
        for (JsonNode document : List.of(idMap,rawIndex,validation))
            check(dataset.equals(text(document,"datasetId")),"EVIDENCE_DATASET_BINDING");
        for (String field : List.of("configDigest","schemaDigest"))
            check(manifest.get(field).equals(validation.get(field)),"VALIDATION_DIGEST_BINDING");

        Map<String,Map<String,JsonNode>> kinds = new HashMap<>();
        Map<String,Set<String>> uuids = new HashMap<>();
        String previous = "";
        for (JsonNode entry : idMap.get("entries")) {
            String kind=text(entry,"entityKind"), id=text(entry,"logicalId"), key=kind+":"+id;
            check(key.compareTo(previous)>0,"ID_MAP_CANONICAL_ORDER"); previous=key;
            check(kinds.computeIfAbsent(kind,k->new HashMap<>()).put(id,entry)==null,"ID_MAP_LOGICAL_DUPLICATE");
            check(uuids.computeIfAbsent(kind,k->new HashSet<>()).add(text(entry,"replayUuid")),"ID_MAP_UUID_DUPLICATE");
        }
        Map<String,JsonNode> accounts=kinds.getOrDefault("ACCOUNT",Map.of());
        Set<String> studentIds=new HashSet<>(), accountIds=new HashSet<>(), academyIds=new HashSet<>();
        for (JsonNode student : students) {
            studentIds.add(text(student,"logicalStudentId")); accountIds.add(text(student,"logicalAccountId"));
            academyIds.add(text(student,"logicalAcademyId"));
        }
        check(kinds.getOrDefault("STUDENT",Map.of()).keySet().equals(studentIds),"ID_MAP_STUDENT_COVERAGE");
        check(accounts.keySet().equals(accountIds),"ID_MAP_ACCOUNT_COVERAGE");
        check(kinds.getOrDefault("ACADEMY",Map.of()).keySet().equals(academyIds),"ID_MAP_ACADEMY_COVERAGE");
        for (var bucket : kinds.entrySet()) for (JsonNode entry : bucket.getValue().values()) {
            String kind=bucket.getKey(); JsonNode owner=entry.get("ownerAccountId");
            if (kind.equals("ACADEMY") || kind.equals("STUDENT")) check(owner.isNull(),"ID_MAP_IDENTITY_OWNER");
            else {
                check(!owner.isNull() && accounts.containsKey(owner.asString()),"ID_MAP_ACCOUNT_REFERENCE");
                if (kind.equals("ACCOUNT"))check(owner.asString().equals(text(entry,"logicalId")),"ID_MAP_ACCOUNT_SELF");
            }
        }
        // The original replay mapping is immutable. Target UUIDs never belong to this document.
        Map<String,JsonNode> eventById=new HashMap<>();
        for(JsonNode event:events)eventById.put(text(event,"eventId"),event);
        Set<String> rawPaths=new HashSet<>();
        for(JsonNode file:manifest.get("files"))if(text(file,"role").equals("RAW"))rawPaths.add(text(file,"path"));
        Set<String> indexed=new HashSet<>(); previous="";
        for(JsonNode record:rawIndex.get("records")) {
            String path=text(record,"path");
            check(path.compareTo(previous)>0 && indexed.add(path),"RAW_INDEX_CANONICAL_ORDER"); previous=path;
            check(rawPaths.contains(path),"RAW_INDEX_ROLE");
            byte[] bytes=artifacts.get(path);
            check(bytes!=null && bytes.length==record.get("byteLength").longValue()
                && SimulationBundleReader.digest(bytes).equals(text(record,"sha256")),"RAW_INDEX_BYTES");
            String service=text(record,"service");
            JsonNode model=record.get("modelVersion");
            JsonNode event=eventById.get(text(record,"eventId"));
            check(event!=null && contains(event.get("artifactRefs"),path),"RAW_INDEX_EVENT_REFERENCE");
            if(service.equals("FEED") && model.isNull())
                verifyFeedCapture(manifest,event,record,artifacts);
            else if(service.equals("FEED") || service.equals("RECAP"))
                check(model.equals(manifest.get("runtimeVersions").get(service.equals("FEED")?"feedModel":"recapModel")),"RAW_INDEX_MODEL_BINDING");
            else check(model.isNull(),"RAW_INDEX_MODEL_BINDING");
        }
        check(indexed.equals(rawPaths),"RAW_INDEX_COMPLETE");
        Set<String> rules=new HashSet<>();
        for(JsonNode rule:validation.get("rules")) {
            check(rules.add(text(rule,"rule")),"VALIDATION_RULE_DUPLICATE");
            String status=text(rule,"status");
            check(status.equals("FAIL") == !rule.get("errors").isEmpty(),"VALIDATION_ERROR_STATUS");
            if(status.equals("NOT_RUN"))check(rule.get("checkedCount").longValue()==0,"VALIDATION_NOT_RUN_COUNT");
            references(rule.get("artifactRefs"),artifacts);
            for(JsonNode error:rule.get("errors")) {
                check(error.get("rule").equals(rule.get("rule")),"VALIDATION_ERROR_RULE");
                references(error.get("artifactRefs"),artifacts);
            }
        }
        for(String field:List.of("counts","independentAggregates")) {
            Set<String> names=new HashSet<>();
            for(JsonNode item:validation.get(field))check(names.add(text(item,"name")),"VALIDATION_AGGREGATE_DUPLICATE");
        }
        return new Result(idMap.get("entries").size(),indexed.size(),rules.size());
    }
    /** Feed capture includes backend observations and unsuccessful attempts, which have no model label. */
    private static void verifyFeedCapture(JsonNode manifest,JsonNode event,JsonNode record,Map<String,byte[]> artifacts) {
        String path=text(record,"path");
        check(text(event,"kind").equals("FEED_QUERY") && SimulationFeedReplayArtifacts.paths(event).contains(path),"RAW_INDEX_MODEL_BINDING");
        if(text(record,"kind").equals("RUNTIME_OBSERVATION"))return;
        String prefix="raw/feed/event-"+event.get("sequence").asLong();
        byte[] response=artifacts.get(prefix+"-response.json");
        if(response!=null) {
            JsonNode body=SimulationBundleReader.parse(response);
            check(body.path("model_version").equals(manifest.get("runtimeVersions").get("feedModel")),"RAW_INDEX_MODEL_BINDING");
        } else {
            check(text(record,"kind").equals("REQUEST") && path.equals(prefix+"-request.json"),"RAW_INDEX_MODEL_BINDING");
            byte[] pageBytes=artifacts.get(prefix+"-page.json");
            check(pageBytes!=null,"RAW_INDEX_MODEL_BINDING");
            JsonNode page=SimulationBundleReader.parse(pageBytes);
            if(page.has("page")) {
                check(page.path("responseCaptured").isBoolean() && !page.get("responseCaptured").asBoolean(),"RAW_INDEX_MODEL_BINDING");
                page=page.get("page");
            }
            check(page.path("sortSource").asString().equals("LATEST") && !page.hasNonNull("modelVersion")
                && !page.hasNonNull("recommendationResultId"),"RAW_INDEX_MODEL_BINDING");
        }
    }
    private static void references(JsonNode refs,Map<String,byte[]> artifacts) {
        Set<String> unique=new HashSet<>();
        for(JsonNode ref:refs)check(unique.add(ref.asString()) && artifacts.containsKey(ref.asString()),"VALIDATION_ARTIFACT_REFERENCE");
    }
    private static boolean contains(JsonNode array,String value) {
        for(JsonNode item:array)if(item.asString().equals(value))return true; return false;
    }
    private static String text(JsonNode node,String field) {return node.get(field).asString();}
    private static void check(boolean condition,String rule) {
        if(!condition)throw new SimulationBundleReader.Rejection("INVARIANT_VIOLATION",rule);
    }
}
