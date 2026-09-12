package com.crabit.backend.simulation;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Typed import preflight in transaction-local tables. Never writes public domain rows. */
public final class SimulationRelationalStaging {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public record Report(int tables,long rows,int foreignKeys,String sourceDigest,String readBackDigest,
                         boolean publicRowsWritten,boolean readyForApplication) {}
    private SimulationRelationalStaging() {}

    // Called only after the dispatcher has validated input against its own migrated catalog.
    static Report stage(JdbcTemplate jdbc,SimulationRelationalState.Export export) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("RELATIONAL_STAGING_TRANSACTION_REQUIRED");
        var catalog=export.catalog();var state=export.state();
        if(!catalog.columns().keySet().equals(SimulationRelationalState.TABLES))
            throw new IllegalStateException("RELATIONAL_STAGING_TABLE_SET");
        String prefix="simulation_stage_"+UUID.randomUUID().toString().replace("-","")+"_";
        var actual=new TreeMap<String,List<JsonNode>>();long total=0;int ordinal=0;
        var targets=new TreeMap<String,String>();
        for(String table:new TreeSet<>(SimulationRelationalState.TABLES)) {
            String target=identifier(prefix+(ordinal++));targets.put(table,target);
            // No defaults/identity/sequences, foreign keys or triggers are copied. Public names are qualified.
            jdbc.execute("CREATE TEMPORARY TABLE "+target+" (LIKE public."+identifier(table)
                +" INCLUDING CONSTRAINTS INCLUDING INDEXES) ON COMMIT DROP");
            var columns=catalog.columns().get(table);
            String names=String.join(",",columns.stream().map(c->identifier(c.name())).toList());
            String parameters=String.join(",",columns.stream().map(c->{
                SimulationRelationalInput.supportedType(c.type());return "CAST(? AS "+c.type()+")";
            }).toList());
            String sql="INSERT INTO pg_temp."+target+" ("+names+") VALUES ("+parameters+")";
            for(JsonNode row:state.tables().get(table)) {
                jdbc.update(connection->{
                    PreparedStatement statement=connection.prepareStatement(sql);
                    try {for(int i=0;i<columns.size();i++)bind(statement,i+1,columns.get(i),row.get(columns.get(i).name()));}
                    catch(SQLException | RuntimeException failure) {statement.close();throw failure;}
                    return statement;
                });total++;
            }
            List<JsonNode> rows=jdbc.query("SELECT to_jsonb(t)::text FROM pg_temp."+target+" t",(r,n)->JSON.readTree(r.getString(1)));
            actual.put(table,rows);
        }
        // Add validated constraints only after every row is loaded, so table order and cycles are safe.
        // Both sides use pg_temp explicitly; a missing staged parent cannot resolve against public rows.
        for(var fk:catalog.foreignKeys()) {
            if(!targets.containsKey(fk.table()) || !targets.containsKey(fk.target())
                || !Set.of("s","f").contains(fk.match()) || fk.columns().isEmpty()
                || fk.columns().size()!=fk.targetColumns().size())
                throw new IllegalStateException("RELATIONAL_STAGING_FOREIGN_KEY_CATALOG");
            String sourceColumns=String.join(",",fk.columns().stream().map(SimulationRelationalStaging::identifier).toList());
            String targetColumns=String.join(",",fk.targetColumns().stream().map(SimulationRelationalStaging::identifier).toList());
            jdbc.execute("ALTER TABLE pg_temp."+targets.get(fk.table())+" ADD FOREIGN KEY ("+sourceColumns
                +") REFERENCES pg_temp."+targets.get(fk.target())+" ("+targetColumns+") MATCH "
                +(fk.match().equals("f")?"FULL":"SIMPLE"));
        }
        String source=digest(state.tables(),catalog),readBack=digest(actual,catalog);
        if(!source.equals(readBack))throw new IllegalStateException("RELATIONAL_STAGING_READ_BACK_MISMATCH");
        return new Report(SimulationRelationalState.TABLES.size(),total,catalog.foreignKeys().size(),source,readBack,false,false);
    }
    private static void bind(PreparedStatement statement,int index,SimulationRelationalState.Column column,JsonNode value) throws SQLException {
        SimulationRelationalInput.value(column,value);
        if(value.isNull()) {statement.setNull(index,Types.VARCHAR);return;}
        switch(column.type()) {
            case "int4" -> statement.setInt(index,value.intValue());
            case "int8" -> statement.setLong(index,value.longValue());
            case "bool" -> statement.setBoolean(index,value.booleanValue());
            case "jsonb" -> statement.setString(index,JSON.writeValueAsString(value));
            default -> statement.setString(index,value.asString());
        }
    }
    private static String digest(Map<String,List<JsonNode>> tables,SimulationRelationalState.Catalog catalog) {
        var normalized=new TreeMap<String,List<String>>();
        tables.forEach((name,rows)->normalized.put(name,rows.stream().map(row->{
            var copy=(tools.jackson.databind.node.ObjectNode)row.deepCopy();
            for(var column:catalog.columns().get(name))if(column.type().equals("timestamptz") && !copy.get(column.name()).isNull())
                copy.put(column.name(),OffsetDateTime.parse(copy.get(column.name()).asString()).toInstant().toString());
            return JSON.writeValueAsString(ordered(copy));
        }).sorted().toList()));
        return SimulationBundleReader.digest(JSON.writeValueAsString(ordered(JSON.valueToTree(normalized)))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    // SQL int8 transport covers all signed 64-bit values; the bundle canonicalizer is JS-safe-integer bounded.
    // This local read-back digest preserves exact JSON numeric values and never changes bundle digest semantics.
    private static JsonNode ordered(JsonNode node) {
        if(node.isObject()) {
            var result=JSON.createObjectNode();
            for(String key:new TreeSet<>(node.propertyNames()))result.set(key,ordered(node.get(key)));
            return result;
        }
        if(node.isArray()) {
            var result=JSON.createArrayNode();for(JsonNode child:node)result.add(ordered(child));return result;
        }
        return node.deepCopy();
    }
    private static String identifier(String value) {
        if(!value.matches("[a-z_][a-z0-9_]*") || value.length()>63)throw new IllegalStateException("RELATIONAL_STAGING_IDENTIFIER");
        return '"'+value+'"';
    }
}
