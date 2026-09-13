package com.crabit.backend.simulation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reconciles saved idempotency evidence with this fresh runtime's actual successful requests/results. */
public final class SimulationIdempotencyVerifier {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> KINDS=Set.of("CREATE","DEPOSIT","WITHDRAW","TRANSFER","COMPLETE","ABANDON","DELETE");
    private record Key(UUID student,String key) {}
    private record Expected(JsonNode event,JsonNode response,String runtimeFingerprint,String logicalFingerprint,String target) {}
    public static final class Verified {
        private final Map<String,String> records;
        private Verified(Map<String,String> records) { this.records=Map.copyOf(records); }
        public int records() { return records.size(); }
        String normalizedFingerprint(JsonNode record) {
            String value=records.get(SimulationBundleReader.canonical(record));
            check(value!=null,"UNVERIFIED_RECORD");return value;
        }
    }
    private SimulationIdempotencyVerifier() {}
    public static Verified verify(SimulationRelationalState.Export export,List<JsonNode> commands,
        List<SimulationCommandDispatcher.Result> results,Map<String,UUID> ids) {
        Map<String,SimulationCommandDispatcher.Result> actual=new HashMap<>();
        for(var result:results)check(actual.put(result.eventId(),result)==null,"DUPLICATE_RESULT");
        Map<Key,Expected> expected=new HashMap<>();
        for(JsonNode event:commands) {
            var result=actual.remove(str(event,"eventId"));check(result!=null,"RESULT_MISSING");
            String kind=str(event,"kind");
            if(!KINDS.contains(kind)||!result.status().equals("APPLIED"))continue;
            JsonNode command=event.get("command"),response=JSON.readTree(result.rawResult());
            Key key=new Key(uuid(ids,"STUDENT",str(event,"actorStudentId")),str(command,"idempotencyKey"));
            String runtime=fingerprint(kind,command,ids,false),logical=fingerprint(kind,command,ids,true);
            String target=Set.of("CREATE","TRANSFER").contains(kind)?uuid(ids,"ACCOUNT",str(command,"accountId")).toString():uuid(ids,"WISH",str(command,"wishId")).toString();
            var prior=expected.putIfAbsent(key,new Expected(event,response,runtime,logical,target));
            check(response.get("replayed")!=null && response.get("replayed").booleanValue()==(prior!=null),"REPLAY_FLAG");
            if(prior!=null) {
                check(prior.runtimeFingerprint().equals(runtime)&&str(prior.event(),"kind").equals(kind)&&prior.target().equals(target),"REPLAY_REQUEST");
                var first=prior.response().deepCopy();var again=response.deepCopy();
                ((tools.jackson.databind.node.ObjectNode)first).remove("replayed");
                ((tools.jackson.databind.node.ObjectNode)again).remove("replayed");
                check(first.equals(again),"REPLAY_RESPONSE");
            }
        }
        check(actual.isEmpty(),"EXTRA_RESULT");Map<String,String> verified=new HashMap<>();
        for(JsonNode student:export.state().tables().get("student")) {
            JsonNode records=student.get("wish_idempotency_records");check(records!=null&&records.isObject(),"RECORDS_SHAPE");
            for(String key:records.propertyNames()) {
                var exp=expected.remove(new Key(UUID.fromString(str(student,"id")),key));check(exp!=null,"UNEXPECTED_RECORD");
                JsonNode record=records.get(key),response=exp.response();String kind=str(exp.event(),"kind");
                check(record.propertyNames().equals(Set.of("operation","targetId","requestFingerprint","httpStatus","snapshot","destinationSnapshot",
                    "photoReplayState","destinationPhotoReplayState","eventId","occurredAt","recordedAt")),"RECORD_FIELDS");
                check(str(record,"operation").equals(kind)&&str(record,"targetId").equals(exp.target()),"RECORD_TARGET");
                check(str(record,"requestFingerprint").equals(exp.runtimeFingerprint()),"FINGERPRINT");
                check(record.get("httpStatus").equals(response.get("httpStatus")),"HTTP_STATUS");
                check(record.get("eventId").equals(response.get("eventId")),"EVENT_ID");
                check(record.get("snapshot").equals(response.get(kind.equals("TRANSFER")?"sourceWish":"wish")),"SNAPSHOT");
                check(kind.equals("TRANSFER")?record.get("destinationSnapshot").equals(response.get("destinationWish")):record.get("destinationSnapshot").isNull(),"DESTINATION_SNAPSHOT");
                check(Instant.parse(str(record,"recordedAt")).equals(Instant.parse(str(exp.event(),"occurredAt"))),"RECORDED_AT");
                check(record.get("eventId").isNull()?record.get("occurredAt").isNull():Instant.parse(str(record,"occurredAt")).equals(Instant.parse(str(exp.event(),"occurredAt"))),"OCCURRED_AT");
                check(noPhoto(record.get("photoReplayState")),"PHOTO_STATE");
                check(kind.equals("TRANSFER")?noPhoto(record.get("destinationPhotoReplayState")):record.get("destinationPhotoReplayState").isNull(),"DESTINATION_PHOTO_STATE");
                String original=SimulationBundleReader.canonical(record);
                String previous=verified.putIfAbsent(original,exp.logicalFingerprint());
                check(previous==null||previous.equals(exp.logicalFingerprint()),"NORMALIZATION_COLLISION");
            }
        }
        check(expected.isEmpty(),"MISSING_RECORD");return new Verified(verified);
    }
    private static boolean noPhoto(JsonNode n) {
        return n!=null&&n.isObject()&&n.propertyNames().equals(Set.of("kind","photoId"))&&str(n,"kind").equals("NO_PHOTO")&&n.get("photoId").isNull();
    }
    private static UUID uuid(Map<String,UUID> ids,String kind,String logical) {
        UUID value=ids.get(kind+":"+logical);check(value!=null,"UNKNOWN_IDENTITY");return value;
    }
    private static String identity(Map<String,UUID> ids,String kind,String logical,boolean normalize) {
        UUID value=uuid(ids,kind,logical);return normalize?kind+":"+logical:value.toString();
    }
    private static String fingerprint(String kind,JsonNode c,Map<String,UUID> ids,boolean normalize) {
        List<String> values=new ArrayList<>();values.add(kind);
        if(kind.equals("CREATE"))values.add("v3");
        values.add(identity(ids,"ACCOUNT",str(c,"accountId"),normalize));
        if(kind.equals("CREATE")) {
            // Independently reproduce the domain's boundary-Zs trimming and NFC, without invoking its hash code.
            String purpose=str(c,"purpose");int first=0,last=purpose.length();
            while(first<last&&Character.getType(purpose.codePointAt(first))==Character.SPACE_SEPARATOR)first+=Character.charCount(purpose.codePointAt(first));
            while(last>first&&Character.getType(purpose.codePointBefore(last))==Character.SPACE_SEPARATOR)last-=Character.charCount(purpose.codePointBefore(last));
            values.add(Normalizer.normalize(purpose.substring(first,last),Normalizer.Form.NFC));values.add(str(c,"targetAmount"));
            values.add(nullable(c,"startDate"));values.add(nullable(c,"targetDate"));
            check(c.get("photoId").isNull(),"PHOTO_UNSUPPORTED");values.add("null");
        } else if(kind.equals("TRANSFER")) {
            values.add(identity(ids,"WISH",str(c,"sourceWishId"),normalize));values.add(identity(ids,"WISH",str(c,"destinationWishId"),normalize));
            values.add(str(c,"amount"));values.add(str(c,"sourceExpectedVersion"));values.add(str(c,"destinationExpectedVersion"));
        } else {
            values.add(identity(ids,"WISH",str(c,"wishId"),normalize));
            if(Set.of("DEPOSIT","WITHDRAW").contains(kind))values.add(str(c,"amount"));
            values.add(str(c,"expectedVersion"));
        }
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String value:values) {byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}
            return "sha256:"+HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private static String nullable(JsonNode n,String key) {return n.get(key).isNull()?"null":str(n,key);}
    private static String str(JsonNode n,String key) {JsonNode value=n.get(key);check(value!=null&&!value.isNull(),"FIELD:"+key);return value.asString();}
    private static void check(boolean ok,String code) {if(!ok)throw new IllegalStateException("IDEMPOTENCY_RECONCILIATION_"+code);}
}
