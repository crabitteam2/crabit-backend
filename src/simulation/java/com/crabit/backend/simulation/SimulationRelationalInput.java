package com.crabit.backend.simulation;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Strict local export transport. Contains no SQL, target connection, or write operation. */
final class SimulationRelationalInput {
    private static final Set<String> FIELDS=Set.of("schemaVersion","schemaKind","datasetId","catalogDigest","tables");
    private static final Set<String> TYPES=Set.of("uuid","varchar","text","bool","int4","int8","timestamptz","date","jsonb");
    private SimulationRelationalInput() {}

    static SimulationRelationalState.State read(byte[] bytes) {
        check(bytes!=null && bytes.length>0 && bytes.length<=SimulationBundleReader.MAX_ARTIFACT_BYTES,"BYTE_LIMIT");
        JsonNode root=SimulationBundleReader.parse(bytes);
        check(root.isObject() && new HashSet<>(root.propertyNames()).equals(FIELDS),"FIELDS");
        check(root.get("schemaVersion").isIntegralNumber() && root.get("schemaVersion").bigIntegerValue().equals(BigInteger.ONE),"VERSION");
        for(String name:List.of("schemaKind","datasetId","catalogDigest"))check(root.get(name).isString(),"BINDING_TYPE");
        JsonNode tables=root.get("tables");
        check(tables.isObject() && new HashSet<>(tables.propertyNames()).equals(SimulationRelationalState.TABLES),"TABLES");
        var rows=new TreeMap<String,List<JsonNode>>();
        for(String table:tables.propertyNames()) {
            JsonNode array=tables.get(table);check(array.isArray(),"ROWS");
            var values=new ArrayList<JsonNode>();for(JsonNode row:array) {check(row.isObject(),"ROW");values.add(row);}
            rows.put(table,values);
        }
        return new SimulationRelationalState.State(1,root.get("schemaKind").asString(),root.get("datasetId").asString(),
            root.get("catalogDigest").asString(),rows);
    }

    static void supportedType(String type) {check(TYPES.contains(type),"UNSUPPORTED_TYPE");}

    /** Checks PostgreSQL JSON export types without coercion or truncation. */
    static void value(SimulationRelationalState.Column column,JsonNode value) {
        supportedType(column.type());
        check(value!=null,"VALUE_TYPE");
        if(value.isNull()) {check(column.nullable(),"NULL");return;}
        boolean valid;
        try {
            valid=switch(column.type()) {
                case "uuid" -> value.isString() && UUID.fromString(value.asString()).toString().equals(value.asString());
                case "text","varchar" -> value.isString() && !value.asString().contains("\u0000");
                case "bool" -> value.isBoolean();
                case "int4" -> integer(value,BigInteger.valueOf(Integer.MIN_VALUE),BigInteger.valueOf(Integer.MAX_VALUE));
                case "int8" -> integer(value,BigInteger.valueOf(Long.MIN_VALUE),BigInteger.valueOf(Long.MAX_VALUE));
                case "date" -> value.isString() && value.asString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    && LocalDate.parse(value.asString()).getYear()>=1;
                case "timestamptz" -> timestamp(value);
                case "jsonb" -> jsonValue(value);
                default -> false;
            };
        } catch(IllegalArgumentException | java.time.DateTimeException e) {valid=false;}
        check(valid,"VALUE_TYPE");
    }
    private static boolean integer(JsonNode value,BigInteger min,BigInteger max) {
        return value.isIntegralNumber() && value.bigIntegerValue().compareTo(min)>=0 && value.bigIntegerValue().compareTo(max)<=0;
    }
    private static boolean timestamp(JsonNode value) {
        if(!value.isString())return false;
        OffsetDateTime time=OffsetDateTime.parse(value.asString());
        return time.getYear()>=1 && time.getYear()<=9999 && time.getNano()%1000==0;
    }
    private static boolean jsonValue(JsonNode value) {
        if(value.isString())return !value.asString().contains("\u0000");
        if(value.isObject())for(String key:value.propertyNames())if(key.contains("\u0000"))return false;
        if((value.isObject() || value.isArray()))for(JsonNode child:value)if(!jsonValue(child))return false;
        return true;
    }
    private static void check(boolean condition,String code) {if(!condition)throw new IllegalStateException("RELATIONAL_INPUT_"+code);}
}
