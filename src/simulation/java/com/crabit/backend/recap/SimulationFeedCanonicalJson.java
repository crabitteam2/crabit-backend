package com.crabit.backend.recap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Simulation-only access to the existing RFC 8785 encoder, including finite ranking scores. */
public final class SimulationFeedCanonicalJson {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private SimulationFeedCanonicalJson() {}
    public static byte[] encode(JsonNode value) {return JsonCanonicalizer.canonicalize(JSON,value);}
}
