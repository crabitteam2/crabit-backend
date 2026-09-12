package com.crabit.backend.simulation;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Non-web target adapter. The SQL capability owns exact-row writes; callers own backup files and console reads. */
public final class SimulationImportManager {
    private static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    private static final String OWNER="00000000-0000-0000-0000-000000000201";
    private static final String ACADEMY="00000000-0000-0000-0000-000000000101";
    private final DataSource source;
    public record Plan(JsonNode request,byte[] backup,String backupDigest) {
        public Plan {request=request.deepCopy();backup=backup.clone();}
        @Override public JsonNode request(){return request.deepCopy();}
        @Override public byte[] backup(){return backup.clone();}
    }
    public SimulationImportManager(DataSource source) {this.source=Objects.requireNonNull(source);}
    private <T> T transaction(Function<JdbcTemplate,T> work) {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(120);
        return tx.execute(status->{var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(90);
            jdbc.execute("SET LOCAL ROLE crabit_demo_manager");jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
            return work.apply(jdbc);});
    }
    public JsonNode inspect(String targetIdentity) {return transaction(jdbc->capture(jdbc,targetIdentity));}
    private static JsonNode capture(JdbcTemplate jdbc,String target) {
        return JSON.readTree(jdbc.queryForObject("SELECT public.demo_import_capture(?)::text",String.class,target));
    }
    public Plan prepare(byte[] relationalBytes,JsonNode people,JsonNode personas,JsonNode trustedSchema,
                        String manifestDigest,String targetIdentity,String consoleBaselineDigest,String codeSha) {
        var state=SimulationRelationalInput.read(relationalBytes);
        SimulationBundleReader.validate(trustedSchema.get("$defs").get("students"),people,"students");
        SimulationBundleReader.validate(trustedSchema.get("$defs").get("personas"),personas,"personas");
        check(manifestDigest.matches("sha256:[0-9a-f]{64}") && consoleBaselineDigest.matches("sha256:[0-9a-f]{64}") && codeSha.matches("[0-9a-f]{40}"),"DIGEST_BINDING");
        return transaction(jdbc->{
            var captured=capture(jdbc,targetIdentity);var tables=captured.get("tables");
            var catalog=SimulationRelationalState.catalog(jdbc);
            var export=new SimulationRelationalState.Export(catalog,state);
            var accounts=ids(state.tables().get("card_balance_account"),"id");
            var students=ids(state.tables().get("student"),"id");
            check(accounts.size()==100 && students.size()==100,"POPULATION");
            SimulationRelationalState.verify(state,catalog,state.datasetId(),students,accounts,Set.of(UUID.fromString(ACADEMY)));
            var byLogical=new HashMap<String,JsonNode>();for(JsonNode p:people)byLogical.put(p.get("logicalStudentId").asString(),p);
            var bindings=new ArrayList<SimulationRelationalDomainVerifier.Person>();
            for(JsonNode a:state.tables().get("demo_simulation_account")) {
                JsonNode person=byLogical.get(a.get("logical_student_id").asString());check(person!=null,"PEOPLE_BINDING");
                var account=one(state.tables().get("card_balance_account"),"id",a.get("account_id").asString());
                var member=one(state.tables().get("academy_membership"),"student_id",account.get("student_id").asString());
                bindings.add(new SimulationRelationalDomainVerifier.Person(UUID.fromString(account.get("student_id").asString()),
                    UUID.fromString(account.get("id").asString()),UUID.fromString(ACADEMY),UUID.fromString(member.get("id").asString()),
                    person.get("logicalStudentId").asString(),person.get("logicalAccountId").asString(),person.get("grade").asInt(),
                    person.get("isOwner").asBoolean(),Instant.parse(person.get("joinedAt").asString())));
            }
            SimulationRelationalDomainVerifier.verify(export,bindings,SimulationCashOracle.END.minusNanos(1000));
            SimulationCheckpointVerifier.verify(export);SimulationAdjustmentVerifier.verify(export);
            SimulationSemanticGraphBoundary.inspect(export,Map.of());
            var targetAccounts=accounts.stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet());
            var targetStudents=students.stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet());
            var before=select(tables,targetAccounts,targetStudents,state.datasetId());
            var after=JSON.createObjectNode();
            for(String table:tables.propertyNames())after.set(table,JSON.valueToTree(state.tables().get(table)));
            // One shared academy is an authentication identity, not an imported rename.
            after.set("academy",before.get("academy").deepCopy());
            check(after.get("academy").size()==1,"ACADEMY_REQUIRED");
            var dataset=(ObjectNode)after.get("demo_simulation_dataset").get(0);
            dataset.put("manifest_digest",manifestDigest);dataset.put("state","APPLIED");
            dataset.set("applied_at",captured.get("capturedAt"));
            var representatives=after.putArray("demo_simulation_persona");var aliases=new HashSet<String>();var selected=new HashSet<String>();
            for(JsonNode p:personas) {
                var account=one(state.tables().get("demo_simulation_account"),"logical_account_id",p.get("logicalAccountId").asString());
                String alias=p.get("persona").asString();int grade=p.get("grade").asInt();
                check(alias.equals("grade-"+grade) && aliases.add(alias) && selected.add(account.get("account_id").asString())
                    && account.get("grade").asInt()==grade && !account.get("is_owner").asBoolean()
                    && account.get("logical_student_id").equals(p.get("logicalStudentId")),"REPRESENTATIVE_BINDING");
                representatives.addObject().put("persona",alias).put("dataset_id",state.datasetId())
                    .set("account_id",account.get("account_id"));
                ((ObjectNode)representatives.get(representatives.size()-1)).set("display_name",p.get("displayName"));
            }
            check(aliases.size()==4,"REPRESENTATIVE_BINDING");
            preserveOwner(tables, after, state.datasetId());
            // Candidate uses target PostgreSQL's exact value spelling; raw source bytes stay untouched.
            for(String table:tables.propertyNames())after.set(table,JSON.readTree(jdbc.queryForObject(
                "SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text COLLATE \"C\"),'[]')::text FROM jsonb_populate_recordset(NULL::public."+table+",?::jsonb) r",
                String.class,JSON.writeValueAsString(after.get(table)))));
            assertNoRetainedReferences(tables,before,after);
            // Existing non-Owner identities must not be silently adopted from an unrelated graph.
            for(JsonNode student:before.get("student"))check(student.get("id").asString().equals(OWNER),"TARGET_ID_COLLISION");
            var backup=JSON.createObjectNode();backup.put("schemaVersion",1).put("schemaKind","demo-simulation-target-backup");
            backup.put("datasetId",state.datasetId()).put("manifestDigest",manifestDigest).put("targetIdentity",targetIdentity);
            backup.set("beforeSnapshot",captured.get("snapshot"));backup.set("before",before);backup.set("after",after);
            backup.set("targetAccounts",JSON.valueToTree(new TreeSet<>(targetAccounts)));backup.set("targetStudents",JSON.valueToTree(new TreeSet<>(targetStudents)));
            byte[] backupBytes=SimulationBundleReader.canonical(backup).getBytes(StandardCharsets.UTF_8);
            String digest=SimulationBundleReader.digest(backupBytes);
            ObjectNode request=request(backup,captured,consoleBaselineDigest,codeSha,digest,"APPLY");
            return new Plan(request,backupBytes,digest);
        });
    }
    public Plan prepareRestore(byte[] backupBytes,String backupDigest,String consoleBaselineDigest,String codeSha) {
        check(SimulationBundleReader.digest(backupBytes).equals(backupDigest),"BACKUP_CHECKSUM");
        JsonNode backup=SimulationBundleReader.parse(backupBytes);
        check(backup.path("schemaKind").asString().equals("demo-simulation-target-backup"),"BACKUP_FORMAT");
        return transaction(jdbc->{
            var captured=capture(jdbc,backup.get("targetIdentity").asString());
            JsonNode apply=null;
            for(JsonNode entry:captured.get("journal"))if(entry.get("operation").asString().equals("APPLY")
                && entry.get("dataset_id").equals(backup.get("datasetId")) && entry.get("backup_digest").asString().equals(backupDigest))apply=entry;
            check(apply!=null && apply.get("after_fingerprint").equals(captured.get("fingerprint")),"RESTORE_DRIFT");
            var reverse=(ObjectNode)backup.deepCopy();reverse.set("before",backup.get("after"));reverse.set("after",backup.get("before").deepCopy());
            // Journal FK retains only dataset metadata; original product graph and counters are restored.
            var dataset=(ObjectNode)backup.get("after").get("demo_simulation_dataset").get(0).deepCopy();dataset.put("state","RESTORED");
            ((ObjectNode)reverse.get("after")).set("demo_simulation_dataset",JSON.createArrayNode().add(dataset));
            ObjectNode request=request(reverse,captured,consoleBaselineDigest,codeSha,backupDigest,"RESTORE");
            request.set("restoreSequences",backup.get("beforeSnapshot").get("sequences"));
            assertNoRetainedReferences(captured.get("tables"),request.get("before"),request.get("after"));
            return new Plan(request,backupBytes,backupDigest);
        });
    }
    private static ObjectNode request(JsonNode backup,JsonNode captured,String console,String code,String digest,String operation) {
        var request=JSON.createObjectNode();request.put("schemaVersion",1).put("operation",operation);
        for(String key:List.of("datasetId","manifestDigest","targetIdentity","targetAccounts","targetStudents","before","after"))request.set(key,backup.get(key).deepCopy());
        request.put("backupDigest",digest).put("consoleBaselineDigest",console).put("codeSha",code);
        long revision=0;for(JsonNode entry:captured.get("journal"))revision=Math.max(revision,entry.get("expected_revision").asLong()+1);
        request.put("expectedRevision",revision);request.set("beforeSnapshot",captured.get("snapshot"));
        request.putNull("expectedAfterFingerprint");request.putNull("restoreSequences");return request;
    }
    /** Full SQL execution with rollback: returns the exact after fingerprint for the final request. */
    public JsonNode dryRun(JsonNode request) {return transaction(jdbc->invoke(jdbc,request,true));}
    /** One write attempt only. On transport ambiguity callers must inspect the durable journal, never retry. */
    public JsonNode execute(JsonNode request) {
        check(request.hasNonNull("expectedAfterFingerprint"),"DRY_RUN_BINDING_REQUIRED");
        JsonNode result;
        try {result=transaction(jdbc->invoke(jdbc,request,false));}
        catch(org.springframework.dao.DataAccessResourceFailureException e) {throw new IllegalStateException("APPLICATION_UNKNOWN",e);}
        var observed=inspect(request.get("targetIdentity").asString());
        check(observed.get("fingerprint").equals(result.get("afterFingerprint")),"APPLICATION_COMMITTED_READ_BACK_DRIFT");
        boolean found=false;for(JsonNode entry:observed.get("journal"))if(entry.get("journal_id").equals(result.get("journalId")))found=true;
        check(found,"APPLICATION_UNKNOWN");
        var report=(ObjectNode)result.deepCopy();report.put("authoritativeReadBack",true);return report;
    }
    private static JsonNode invoke(JdbcTemplate jdbc,JsonNode request,boolean dry) {
        return JSON.readTree(jdbc.queryForObject("SELECT public.demo_import_graph(?::jsonb,?)::text",String.class,JSON.writeValueAsString(request),dry));
    }
    private static Set<UUID> ids(List<JsonNode> rows,String field) {
        var ids=new HashSet<UUID>();for(JsonNode row:rows)check(ids.add(UUID.fromString(row.get(field).asString())),"DUPLICATE_ID");return ids;
    }
    private static JsonNode one(List<JsonNode> rows,String field,String value) {
        var matches=rows.stream().filter(r->r.path(field).asString().equals(value)).toList();check(matches.size()==1,"SOURCE_IDENTITY");return matches.getFirst();
    }
    /** Historical Owner remains in the local replay; target Owner graph comes exclusively from capture. */
    private static void preserveOwner(JsonNode target,ObjectNode candidate,String dataset) {
        var ownerAccounts=Set.of("00000000-0000-0000-0000-000000000301");
        var sourceOwner=select(candidate,ownerAccounts,Set.of(OWNER),dataset);
        var currentOwner=select(target,ownerAccounts,Set.of(OWNER),dataset);
        // Cross-person actions cannot be silently removed when projecting out local Owner history.
        for(String table:List.of("student_follow","student_block"))
            check(sourceOwner.get(table).isEmpty(),"OWNER_CROSS_PERSON_ACTION:"+table);
        for(String table:List.of("behavior_event","feed_visit_evidence"))for(JsonNode row:sourceOwner.get(table)) {
            for(String field:List.of("actor_id","viewer_id","target_author_id"))if(row.hasNonNull(field))
                check(row.get(field).asString().equals(OWNER),"OWNER_CROSS_PERSON_ACTION:"+table);
        }
        var omitted=new HashSet<String>();
        for(String table:candidate.propertyNames()) {
            if(table.startsWith("demo_simulation_") && !table.equals("demo_simulation_cash_event"))continue;
            var rows=JSON.createArrayNode();
            for(JsonNode row:candidate.get(table)) {
                if(!contains(sourceOwner.get(table),row))rows.add(row);
                else if(row.hasNonNull("id"))omitted.add(row.get("id").asString());
            }
            for(JsonNode row:currentOwner.get(table))rows.add(row.deepCopy());
            candidate.set(table,rows);
        }
        for(String table:candidate.propertyNames())for(JsonNode row:candidate.get(table))
            if(row.hasNonNull("id"))omitted.remove(row.get("id").asString());
        for(String table:candidate.propertyNames())for(JsonNode row:candidate.get(table))
            check(!references(row,omitted),"OWNER_LOCAL_HISTORY_REFERENCE:"+table);
        for(JsonNode a:candidate.get("demo_simulation_account"))if(a.get("is_owner").asBoolean()) {
            ((ObjectNode)a).put("card_funds",0).put("cash_sequence",0);
        }
    }
    private static ObjectNode select(JsonNode tables,Set<String> accounts,Set<String> students,String dataset) {
        var selected=JSON.createObjectNode();for(String table:tables.propertyNames())selected.putArray(table);
        boolean changed;
        do {
            changed=false;
            for(String table:tables.propertyNames())for(JsonNode row:tables.get(table)) {
                var rows=(tools.jackson.databind.node.ArrayNode)selected.get(table);
                if(contains(rows,row) || !owned(table,row,selected,accounts,students,dataset))continue;
                rows.add(row.deepCopy());changed=true;
            }
        } while(changed);
        return selected;
    }
    private static boolean owned(String table,JsonNode row,JsonNode selected,Set<String> accounts,Set<String> students,String dataset) {
        if(table.equals("academy"))return row.get("id").asString().equals(ACADEMY);
        if(table.equals("student"))return students.contains(row.get("id").asString());
        if(table.equals("card_balance_account"))return accounts.contains(row.get("id").asString());
        if(table.equals("demo_simulation_dataset"))return row.get("dataset_id").asString().equals(dataset);
        if(table.equals("feed_source_history"))return owned(row.get("source_kind").asString(),row.get("payload"),selected,accounts,students,dataset);
        if(table.equals("shared_card"))return selectedId(selected,"wish","id",row.get("wish_id"));
        if(table.equals("behavior_result_item"))return selectedId(selected,"behavior_result_context","id",row.get("context_id"));
        if(table.equals("feed_page_state"))return selectedId(selected,"feed_page_context","id",row.get("context_id"));
        if(table.equals("feed_page_transition"))return selectedId(selected,"feed_page_state","id",row.get("input_state_id"));
        if(row.hasNonNull("account_id") && accounts.contains(row.get("account_id").asString()))return true;
        for(String field:List.of("actor_id","student_id","viewer_id","source_id","blocker_id","blocked_id","target_author_id","target_id"))
            if(row.hasNonNull(field) && students.contains(row.get(field).asString()))return true;
        return false;
    }
    private static boolean selectedId(JsonNode selected,String table,String field,JsonNode value) {
        for(JsonNode row:selected.get(table))if(row.get(field).equals(value))return true;return false;
    }
    private static boolean contains(JsonNode array,JsonNode row) {for(JsonNode r:array)if(r.equals(row))return true;return false;}
    private static void assertNoRetainedReferences(JsonNode tables,JsonNode before,JsonNode after) {
        var removed=new HashSet<String>();var remaining=new HashSet<String>();
        for(String table:before.propertyNames())for(JsonNode row:before.get(table))if(row.hasNonNull("id"))removed.add(row.get("id").asString());
        for(String table:after.propertyNames())for(JsonNode row:after.get(table))if(row.hasNonNull("id"))remaining.add(row.get("id").asString());
        removed.removeAll(remaining);
        for(String table:tables.propertyNames())for(JsonNode row:tables.get(table))if(!contains(before.get(table),row))
            check(!references(row,removed),"RETAINED_REFERENCE:"+table);
    }
    private static boolean references(JsonNode node,Set<String> removed) {
        if(node.isString()) {
            if(removed.contains(node.asString()))return true;
            if(node.asString().startsWith("{"))try {return references(SimulationBundleReader.parse(node.asString().getBytes(StandardCharsets.UTF_8)),removed);}catch(RuntimeException ignored) {return false;}
        }
        if(node.isObject()||node.isArray())for(JsonNode child:node)if(references(child,removed))return true;
        return false;
    }
    private static void check(boolean condition,String code) {if(!condition)throw new IllegalStateException(code);}
}
