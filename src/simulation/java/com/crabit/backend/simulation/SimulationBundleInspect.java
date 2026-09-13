package com.crabit.backend.simulation;

import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Non-web, read-only manifest admission. This command never emits VALID/APPLIED/READY. */
public final class SimulationBundleInspect {
    private SimulationBundleInspect() {}
    public static void main(String[] args) {
        JsonMapper json = JsonMapper.builder().build();
        if (args.length != 3) {
            System.err.println("Usage: simulationInspect --args='<bundle-directory> <trusted-schema-path> <expected-manifest-sha256>'");
            System.exit(2); return;
        }
        try {
            var result = new SimulationBundleReader().read(Path.of(args[0]), Path.of(args[1]), args[2]);
            var output = new java.util.LinkedHashMap<String,Object>(Map.of(
                "schemaVersion",1,"schemaKind","simulation-bundle-admission",
                "status","ADMITTED","datasetId",result.datasetId(),"manifestDigest",result.manifestDigest(),
                "artifactCount",result.artifacts().size(),"eventAdmissionPerformed",true,"domainValidationPerformed",false,
                "databaseWritesPerformed",false,"readyForApplication",false));
            output.put("cashReconciliationPerformed",true);
            System.out.println(json.writeValueAsString(output));
        } catch (SimulationBundleReader.Rejection e) {
            System.out.println(json.writeValueAsString(Map.of("schemaVersion",1,"schemaKind","simulation-bundle-admission",
                "status","REJECTED","code",e.code(),"message",e.getMessage())));
            System.exit(2);
        } catch (Exception e) {
            // Never report filesystem contents, environment values, or parser excerpts.
            System.out.println(json.writeValueAsString(Map.of("schemaVersion",1,"schemaKind","simulation-bundle-admission",
                "status","FAILED","code","EXECUTION_NOT_BOUND","message","Bundle or trusted schema could not be read.")));
            System.exit(4);
        }
    }
}
