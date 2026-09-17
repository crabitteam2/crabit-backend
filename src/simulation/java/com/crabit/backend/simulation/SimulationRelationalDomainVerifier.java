package com.crabit.backend.simulation;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Independent replay-only temporal, enrollment and source-history checks over preserved DB evidence. */
public final class SimulationRelationalDomainVerifier {
    private static final Set<String> SOURCES=Set.of("academy_membership","card_balance_account","shared_card","student_block","student_follow","wish");
    public record Person(UUID student,UUID account,UUID academy,UUID membership,String logicalStudent,String logicalAccount,
                         int grade,boolean owner,Instant joinedAt) {}
    public record Verification(int people,long timestamps,long sourceVersions,Instant replayThrough,
                               boolean fullDatasetValidationPerformed) {}
    private SimulationRelationalDomainVerifier() {}

    /** Requires the relational catalog/PK/FK validator to have passed against this same export first. */
    public static Verification verify(SimulationRelationalState.Export export,List<Person> people,Instant through) {
        check(!through.isBefore(SimulationCashOracle.START) && through.isBefore(SimulationCashOracle.END),"WATERMARK");
        var tables=export.state().tables();var catalog=export.catalog();
        Map<String,Person> accounts=new HashMap<>(),students=new HashMap<>();
        for(Person p:people) {
            check(accounts.put(p.account().toString(),p)==null && students.put(p.student().toString(),p)==null,"POPULATION");
            JsonNode account=one(tables,"card_balance_account","id",p.account().toString());
            check(text(account,"student_id").equals(p.student().toString()) && text(account,"academy_id").equals(p.academy().toString())
                && time(account.get("opened_at")).equals(p.joinedAt()) && account.get("closed_at").isNull(),"ACCOUNT_BINDING");
            JsonNode member=one(tables,"academy_membership","id",p.membership().toString());
            check(text(member,"student_id").equals(p.student().toString()) && text(member,"academy_id").equals(p.academy().toString())
                && time(member.get("joined_at")).equals(p.joinedAt()) && member.get("left_at").isNull(),"MEMBERSHIP_BINDING");
            JsonNode simulation=one(tables,"demo_simulation_account","account_id",p.account().toString());
            check(text(simulation,"logical_student_id").equals(p.logicalStudent()) && text(simulation,"logical_account_id").equals(p.logicalAccount())
                && simulation.get("grade").intValue()==p.grade() && simulation.get("is_owner").booleanValue()==p.owner(),"SIMULATION_IDENTITY");
        }
        check(tables.get("academy_membership").size()==people.size(),"MEMBERSHIP_SET");
        long timestamps=0;
        for(var table:tables.entrySet())for(JsonNode row:table.getValue()) {
            timestamps+=times(table.getKey(),row,catalog,through);
            // Row event time cannot precede the enrolled account or any referenced participant.
            List<Instant> participation=new ArrayList<>();
            if(row.hasNonNull("account_id")) {
                Person p=accounts.get(text(row,"account_id"));check(p!=null,"ACCOUNT_SCOPE");participation.add(p.joinedAt());
            }
            for(String field:List.of("student_id","actor_id","target_id","viewer_id","owner_student_id","target_author_id","source_id","blocker_id","blocked_id")) {
                if(table.getKey().equals("feed_source_history") && field.equals("source_id"))continue;
                if(row.hasNonNull(field)) {
                    Person p=students.get(text(row,field));check(p!=null,"STUDENT_SCOPE");participation.add(p.joinedAt());
                }
            }
            // Target of student_follow is target_id; all timestamps describe participation there.
            for(String field:List.of("created_at","occurred_at","observed_at","applied_at","started_at","blocked_at"))
                if(row.hasNonNull(field) && !table.getKey().endsWith("_collection"))
                    for(Instant joined:participation)check(!time(row.get(field)).isBefore(joined),"BEFORE_ENROLLMENT");
        }
        for(String collection:List.of("behavior_collection","feed_history_collection")) {
            check(tables.get(collection).size()==1 && time(tables.get(collection).getFirst().get("started_at")).equals(SimulationCashOracle.START),"HISTORY_BASELINE");
        }
        var dataset=tables.get("demo_simulation_dataset").getFirst();
        check(time(dataset.get("starts_at")).equals(SimulationCashOracle.START) && time(dataset.get("ends_at")).equals(SimulationCashOracle.END),"DATASET_PERIOD");
        Map<String,List<JsonNode>> versions=new HashMap<>();
        for(JsonNode row:tables.get("feed_source_history")) {
            String source=text(row,"source_kind"),id=text(row,"source_id");JsonNode payload=row.get("payload");
            check(SOURCES.contains(source) && payload.isObject() && payload.hasNonNull("id") && text(payload,"id").equals(id),"HISTORY_IDENTITY");
            Set<String> names=new HashSet<>();catalog.columns().get(source).forEach(c->names.add(c.name()));
            check(new HashSet<>(payload.propertyNames()).equals(names),"HISTORY_COLUMNS");
            timestamps+=times(source,payload,catalog,time(row.get("valid_from")));
            for(var fk:catalog.foreignKeys())if(fk.table().equals(source)) {
                List<JsonNode> values=fk.columns().stream().map(payload::get).toList();
                if(values.stream().noneMatch(JsonNode::isNull))check(tables.get(fk.target()).stream()
                    .anyMatch(target->fk.targetColumns().stream().map(target::get).toList().equals(values)),"HISTORY_REFERENCE");
            }
            versions.computeIfAbsent(source+":"+id,ignored->new ArrayList<>()).add(row);
        }
        for(var entry:versions.entrySet()) {
            var rows=entry.getValue();rows.sort(Comparator.comparingLong(r->r.get("version").longValue()));
            for(int i=1;i<rows.size();i++) {
                var prior=rows.get(i-1);var current=rows.get(i);
                check(prior.hasNonNull("valid_to") && time(prior.get("valid_to")).equals(time(current.get("valid_from"))),"HISTORY_INTERVAL_CHAIN");
            }
            JsonNode last=rows.getLast();String source=text(last,"source_kind"),id=text(last,"source_id");
            List<JsonNode> current=tables.get(source).stream().filter(r->text(r,"id").equals(id)).toList();
            check(last.get("valid_to").isNull() ? current.size()==1 && current.getFirst().equals(last.get("payload")) : current.isEmpty(),"HISTORY_CURRENT_SNAPSHOT");
        }
        for(String source:SOURCES)for(JsonNode row:tables.get(source))check(versions.containsKey(source+":"+text(row,"id")),"HISTORY_MISSING_SOURCE");
        return new Verification(people.size(),timestamps,tables.get("feed_source_history").size(),through,false);
    }
    private static long times(String table,JsonNode row,SimulationRelationalState.Catalog catalog,Instant through) {
        long count=0;
        for(var c:catalog.columns().get(table))if(c.type().equals("timestamptz") && row.hasNonNull(c.name())) {
            Instant value=time(row.get(c.name()));count++;
            if(table.equals("demo_simulation_dataset") && c.name().equals("ends_at"))continue;
            if(table.equals("feed_page_context") && c.name().equals("expires_at")) {
                check(value.equals(time(row.get("created_at")).plusSeconds(300)),"PAGE_EXPIRY");continue;
            }
            check(!value.isBefore(SimulationCashOracle.START) && !value.isAfter(through)
                && value.isBefore(SimulationCashOracle.END),"TIME_RANGE:"+table+":"+c.name());
        }
        for(var pair:List.of(List.of("opened_at","closed_at"),List.of("joined_at","left_at"),List.of("started_at","ended_at"),
            List.of("blocked_at","released_at"),List.of("valid_from","valid_to"),List.of("created_at","updated_at"),
            List.of("created_at","completed_at"),List.of("created_at","abandoned_at"),List.of("created_at","deleted_at"),
            List.of("occurred_at","received_at")))
            if(row.hasNonNull(pair.get(0)) && row.hasNonNull(pair.get(1)))check(!time(row.get(pair.get(1))).isBefore(time(row.get(pair.get(0)))),"TIME_ORDER");
        return count;
    }
    private static JsonNode one(Map<String,List<JsonNode>> tables,String table,String column,String id) {
        var matches=tables.get(table).stream().filter(r->text(r,column).equals(id)).toList();check(matches.size()==1,"IDENTITY_MISSING");return matches.getFirst();
    }
    private static String text(JsonNode row,String field) { return row.get(field).asString(); }
    private static Instant time(JsonNode value) {
        try { Instant time=OffsetDateTime.parse(value.asString()).toInstant();check(time.getNano()%1000==0,"TIME_PRECISION");return time; }
        catch(java.time.format.DateTimeParseException e) { throw new IllegalStateException("RELATIONAL_DOMAIN_TIME_FORMAT"); }
    }
    private static void check(boolean ok,String code) { if(!ok)throw new IllegalStateException("RELATIONAL_DOMAIN_"+code); }
}
