package com.crabit.backend.simulation;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Local replay diagnostics. No SQL/import input, target connection, or credential export. */
public final class SimulationRelationalState {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    static final Set<String> TABLES=Set.of(
        "academy","student","academy_membership","student_block","card_balance_account","wish",
        "ledger_event","balance_observation","ledger_wish_effect","balance_adjustment_case",
        "balance_adjustment_case_event","mismatch_notification_outbox","shared_card","representative_wish_selection",
        "wish_photo","wish_photo_upload_receipt","wish_photo_processing_attempt","wish_photo_cleanup_work",
        "behavior_collection","behavior_result_context","behavior_result_item","behavior_impression","behavior_event",
        "recap_generation","historical_ledger_application","historical_balance_checkpoint","student_follow",
        "feed_history_collection","feed_source_history","feed_visit_evidence","feed_page_context","feed_page_state",
        "feed_page_transition","demo_simulation_dataset","demo_simulation_account","demo_simulation_persona",
        "demo_simulation_cash_event","demo_simulation_application");
    static final Set<String> EMPTY_ONLY=Set.of("wish_photo","wish_photo_upload_receipt","wish_photo_processing_attempt",
        "wish_photo_cleanup_work","demo_simulation_application");
    public record Column(String name,String type,boolean nullable) {}
    public record Key(String table,List<String> columns,boolean primary) {
        public Key { columns=List.copyOf(columns); }
    }
    public record ForeignKey(String table,List<String> columns,String target,List<String> targetColumns,String match) {
        public ForeignKey { columns=List.copyOf(columns);targetColumns=List.copyOf(targetColumns); }
    }
    public record Catalog(Map<String,List<Column>> columns,List<Key> keys,List<ForeignKey> foreignKeys) {
        public Catalog { var copy=new TreeMap<String,List<Column>>();columns.forEach((k,v)->copy.put(k,List.copyOf(v)));
            columns=Collections.unmodifiableMap(copy);keys=List.copyOf(keys);foreignKeys=List.copyOf(foreignKeys); }
    }
    public record State(int schemaVersion,String schemaKind,String datasetId,String catalogDigest,Map<String,List<JsonNode>> tables) {
        public State { var copy=new TreeMap<String,List<JsonNode>>();tables.forEach((k,v)->copy.put(k,v.stream().map(JsonNode::deepCopy).toList()));
            tables=Collections.unmodifiableMap(copy); }
    }
    public record Export(Catalog catalog,State state) {}
    public record Verification(int tables,long rows,int uniqueKeys,int foreignKeys,long references) {}
    private SimulationRelationalState() {}
    private static String digest(Catalog catalog) {
        return SimulationBundleReader.digest(SimulationBundleReader.canonical(JSON.valueToTree(catalog)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static String identifier(String value) {
        check(value.matches("[a-z_][a-z0-9_]*"),"IDENTIFIER");return '"'+value+'"';
    }
    private static List<String> strings(java.sql.Array array) throws java.sql.SQLException {
        return Arrays.stream((Object[])array.getArray()).map(Object::toString).toList();
    }
    static Export capture(JdbcTemplate jdbc,String dataset) {
        Set<String> inventory=new HashSet<>(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'",String.class));
        var expected=new HashSet<>(TABLES);expected.addAll(Set.of("flyway_schema_history","relationship_cursor_key"));
        check(inventory.equals(expected),"TABLE_INVENTORY");
        // Never read key material or unsupported media/provider work, even into an intermediate object.
        for(String table:EMPTY_ONLY)check(jdbc.queryForObject("SELECT count(*) FROM "+identifier(table),Long.class)==0,"UNSUPPORTED_ROWS");
        var catalog=catalog(jdbc);var tables=new TreeMap<String,List<JsonNode>>();
        for(String table:catalog.columns().keySet()) {
            List<JsonNode> rows=jdbc.query("SELECT to_jsonb(t)::text FROM "+identifier(table)+" t",(r,n)->JSON.readTree(r.getString(1)));
            rows.sort(Comparator.comparing(SimulationBundleReader::canonical));tables.put(table,rows);
        }
        return new Export(catalog,new State(1,"simulation-relational-state",dataset,digest(catalog),tables));
    }
    static Catalog catalog(JdbcTemplate jdbc) {
        Map<String,List<Column>> columns=new TreeMap<>();
        for(String table:new TreeSet<>(TABLES))columns.put(table,jdbc.query("""
            SELECT column_name,udt_name,is_nullable='YES' FROM information_schema.columns
            WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position
            """,(r,n)->new Column(r.getString(1),r.getString(2),r.getBoolean(3)),table));
        var keys=jdbc.query("""
            SELECT t.relname,c.contype='p',array_agg(a.attname ORDER BY k.ord)
            FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace ns ON ns.oid=t.relnamespace
            CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY k(attnum,ord)
            JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum
            WHERE ns.nspname='public' AND c.contype IN ('p','u') AND t.relname NOT IN ('flyway_schema_history','relationship_cursor_key')
            GROUP BY c.oid,t.relname,c.contype ORDER BY t.relname,c.conname
            """,(r,n)->new Key(r.getString(1),strings(r.getArray(3)),r.getBoolean(2)));
        var fks=jdbc.query("""
            SELECT t.relname,array_agg(a.attname ORDER BY k.ord),target.relname,
                array_agg(b.attname ORDER BY k.ord),c.confmatchtype::text
            FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace ns ON ns.oid=t.relnamespace
            JOIN pg_class target ON target.oid=c.confrelid
            CROSS JOIN LATERAL unnest(c.conkey,c.confkey) WITH ORDINALITY k(src,dst,ord)
            JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.src
            JOIN pg_attribute b ON b.attrelid=target.oid AND b.attnum=k.dst
            WHERE ns.nspname='public' AND c.contype='f'
            GROUP BY c.oid,t.relname,target.relname,c.confmatchtype ORDER BY t.relname,c.conname
            """,(r,n)->new ForeignKey(r.getString(1),strings(r.getArray(2)),r.getString(3),strings(r.getArray(4)),r.getString(5)));
        return new Catalog(columns,keys,fks);
    }
    /** Catalog is obtained from this runtime's migrated DB, never from a caller-supplied bundle. */
    public static Verification verify(State state,Catalog trusted,String dataset,Set<UUID> students,Set<UUID> accounts,Set<UUID> academies) {
        check(state.schemaVersion()==1 && state.schemaKind().equals("simulation-relational-state")
            && state.datasetId().equals(dataset) && state.catalogDigest().equals(digest(trusted)),"BINDING");
        check(trusted.columns().keySet().equals(TABLES) && state.tables().keySet().equals(TABLES),"TABLE_SET");
        long rows=0,refs=0;
        for(var entry:trusted.columns().entrySet()) {
            Set<String> names=new HashSet<>();entry.getValue().forEach(c->{
                names.add(c.name());SimulationRelationalInput.supportedType(c.type());
            });
            for(JsonNode row:state.tables().get(entry.getKey())) {
                rows++;check(row.isObject() && new HashSet<>(row.propertyNames()).equals(names),"COLUMNS");
                for(Column c:entry.getValue()) {
                    if(!c.nullable())check(!row.get(c.name()).isNull(),"NULL_COLUMN");
                    SimulationRelationalInput.value(c,row.get(c.name()));
                }
            }
        }
        for(String table:EMPTY_ONLY)check(state.tables().get(table).isEmpty(),"UNSUPPORTED_ROWS");
        for(Key key:trusted.keys()) {
            Set<List<JsonNode>> seen=new HashSet<>();
            for(JsonNode row:state.tables().get(key.table())) {
                var values=tuple(row,key.columns());boolean anyNull=values.stream().anyMatch(JsonNode::isNull);
                if(key.primary())check(!anyNull,"NULL_PRIMARY_KEY");
                if(!anyNull)check(seen.add(values),"DUPLICATE_KEY");
            }
        }
        for(ForeignKey fk:trusted.foreignKeys()) {
            check(TABLES.contains(fk.table()) && TABLES.contains(fk.target()) && Set.of("s","f").contains(fk.match()),"FOREIGN_KEY_CATALOG");
            Set<List<JsonNode>> targets=new HashSet<>();
            for(JsonNode row:state.tables().get(fk.target()))targets.add(tuple(row,fk.targetColumns()));
            for(JsonNode row:state.tables().get(fk.table())) {
                var values=tuple(row,fk.columns());long nulls=values.stream().filter(JsonNode::isNull).count();
                if(nulls>0) { check(fk.match().equals("s") || nulls==values.size(),"PARTIAL_NULL_KEY");continue; }
                check(targets.contains(values),"DANGLING_REFERENCE");refs++;
            }
        }
        check(ids(state,"student","id").equals(students) && ids(state,"card_balance_account","id").equals(accounts)
            && ids(state,"academy","id").equals(academies) && ids(state,"demo_simulation_account","account_id").equals(accounts),"POPULATION");
        var datasets=state.tables().get("demo_simulation_dataset");
        check(datasets.size()==1 && datasets.getFirst().get("dataset_id").asString().equals(dataset)
            && datasets.getFirst().get("state").asString().equals("BUILDING"),"DATASET_STATE");
        for(String table:List.of("demo_simulation_account","demo_simulation_cash_event","demo_simulation_persona"))
            for(JsonNode row:state.tables().get(table))check(row.get("dataset_id").asString().equals(dataset),"DATASET_SCOPE");
        return new Verification(TABLES.size(),rows,trusted.keys().size(),trusted.foreignKeys().size(),refs);
    }
    private static Set<UUID> ids(State state,String table,String column) {
        Set<UUID> ids=new HashSet<>();for(JsonNode row:state.tables().get(table))check(ids.add(UUID.fromString(row.get(column).asString())),"DUPLICATE_ID");return ids;
    }
    private static List<JsonNode> tuple(JsonNode row,List<String> columns) { return columns.stream().map(row::get).toList(); }
    private static void check(boolean condition,String code) { if(!condition)throw new IllegalStateException("RELATIONAL_"+code); }
}
