package com.crabit.backend.simulation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Local process-clock preflight only; does not run or admit an application dataset. */
public final class SimulationClockCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No existing database or arguments accepted");
        List<Map<String,Object>> observations = new ArrayList<>();
        try(var db = new SimulationPostgresClock()) {
            for(Instant at : List.of(SimulationCashOracle.START,
                    Instant.parse("2026-06-30T15:00:00.123456Z"),
                    SimulationCashOracle.END.minusNanos(1000))) {
                observations.add(db.executeAt(at, step -> Map.of(
                    "expected", at.toString(), "javaClock", step.clock().instant().toString(),
                    "sqlClock", step.jdbc().queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant().toString(),
                    "postgresVersion", step.jdbc().queryForObject("SHOW server_version", String.class))));
            }
        }
        System.out.println(JsonMapper.builder().build().writeValueAsString(Map.of(
            "schemaVersion",1,"schemaKind","demo-simulation-clock-check",
            "status","CLOCK_VERIFIED","observations",observations,
            "fullDatasetReplayPerformed",false,"readyForApplication",false)));
    }
}
