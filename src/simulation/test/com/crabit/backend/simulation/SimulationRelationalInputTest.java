package com.crabit.backend.simulation;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SimulationRelationalInputTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    static Stream<Arguments> invalid() {
        return Stream.of(
            Arguments.of("int8","\"1\""), Arguments.of("int8","1.0"), Arguments.of("int8","9223372036854775808"),
            Arguments.of("int8","-9223372036854775809"), Arguments.of("int4","2147483648"), Arguments.of("int4","-2147483649"),
            Arguments.of("bool","1"), Arguments.of("bool","\"true\""), Arguments.of("uuid","\"1-1-1-1-1\""),
            Arguments.of("uuid","\"ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB\""), Arguments.of("uuid","12"),
            Arguments.of("text","{}"), Arguments.of("varchar","[]"), Arguments.of("text","\"bad\\u0000text\""),
            Arguments.of("date","\"2026-02-30\""), Arguments.of("date","\"2026-6-1\""), Arguments.of("date","\"0000-01-01\""),
            Arguments.of("date","\"infinity\""), Arguments.of("timestamptz","\"2026-06-01T00:00:00\""),
            Arguments.of("timestamptz","\"2026-06-01T00:00:00.0000001Z\""), Arguments.of("timestamptz","\"infinity\""),
            Arguments.of("jsonb","{\"nested\":[\"bad\\u0000\"]}"), Arguments.of("jsonb","{\"bad\\u0000\":1}"));
    }
    @ParameterizedTest @MethodSource("invalid") void rejectsLossyOrCoercedValues(String type,String raw) {
        assertThatThrownBy(()->SimulationRelationalInput.value(new SimulationRelationalState.Column("value",type,false),JSON.readTree(raw)))
            .hasMessage("RELATIONAL_INPUT_VALUE_TYPE");
    }
    static Stream<Arguments> valid() {
        return Stream.of(
            Arguments.of("int8","9223372036854775807"),Arguments.of("int8","-9223372036854775808"),
            Arguments.of("int4","2147483647"),Arguments.of("int4","-2147483648"),Arguments.of("bool","false"),
            Arguments.of("uuid","\"abcdefab-cdef-abcd-efab-cdefabcdefab\""),Arguments.of("varchar","\"한글 목적\""),
            Arguments.of("text","\"'; DROP TABLE student; --\""),Arguments.of("date","\"2024-02-29\""),
            Arguments.of("timestamptz","\"2026-06-01T00:00:00.123456+09:00\""),
            Arguments.of("jsonb","{\"nested\":[1,null,true,\"한글\"]}"));
    }
    @ParameterizedTest @MethodSource("valid") void preservesExactValidValues(String type,String raw) {
        JsonNode value=JSON.readTree(raw);String before=JSON.writeValueAsString(value);
        SimulationRelationalInput.value(new SimulationRelationalState.Column("value",type,false),value);
        assertThat(JSON.writeValueAsString(value)).isEqualTo(before);
    }
    @Test void nullableDoesNotPermitUnknownCatalogType() {
        assertThatThrownBy(()->SimulationRelationalInput.value(new SimulationRelationalState.Column("x","bytea",true),JSON.nullNode()))
            .hasMessage("RELATIONAL_INPUT_UNSUPPORTED_TYPE");
        assertThatThrownBy(()->SimulationRelationalInput.value(new SimulationRelationalState.Column("x","int4",false),JSON.nullNode()))
            .hasMessage("RELATIONAL_INPUT_NULL");
        SimulationRelationalInput.value(new SimulationRelationalState.Column("x","int4",true),JSON.nullNode());
    }
}
